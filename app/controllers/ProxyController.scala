package controllers

import java.time.{LocalDateTime, ZoneId}
import javax.inject._

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials, DefaultAWSCredentialsProviderChain}
import io.ticofab.AwsSigner
import play.api.http.HttpEntity
import play.api.libs.ws._
import play.api.mvc._
import com.netaporter.uri.Uri
import com.netaporter.uri.config.UriConfig

import scala.concurrent.ExecutionContext.Implicits.global
import com.netaporter.uri.encoding._
import play.api.Configuration

@Singleton
class ProxyController @Inject()(ws: WSClient, configuration: Configuration) extends Controller {

  def any(path: String) = Action.async(parse.raw) { implicit request =>
    streamResponse(proxyRequest(request))
  }

  private def proxyRequest(incomingRequest: Request[RawBuffer]) = {

    val queryStringParams = incomingRequest.queryString.map {
      // TODO: Review loss of multiple query string values caused by .head
      case (key, values) => (key, values.head)
    }.toSeq.sortBy(_._1)

    // remove headers we don't want to sign and send to AWS
    val filteredHeaders = incomingRequest.headers.headers.filterNot {
      // we need to override the Host header
      case ("Host", _) => false
      // AWS doesn't seem to like when we include the Accept-Encoding header, not sure why yet
      case ("Accept-Encoding", _) => false
      // allow all other headers
      case _ => true
    }

    // store max 1024 KB in memory todo review this limit
    val maybeBody = incomingRequest.body.asBytes()

    val body: WSBody = maybeBody match {
      // TODO: Switch to using a StreamedBody so we don't have to hold anything in memory
      case Some(b) => InMemoryBody(b)
      case None => EmptyBody
    }

    val serviceDomain = Uri.parse(configuration.getString("proxy.aws.serviceDomain").get)
    val maybeUrl = for {
      scheme <- serviceDomain.scheme
      host <- serviceDomain.host
    } yield {
      // TODO: Make sure I didn't inadvertently miss ports here
      s"$scheme://$host${incomingRequest.path}"
    }

    // TODO: Fail if we're missing parts of the URL

    // TODO: Add query params
    // is Content-Type required?
    val outgoingRequest = ws
        .url(maybeUrl.get)
        .withMethod(incomingRequest.method)
        .withHeaders(filteredHeaders: _*)
        .withHeaders("Host" -> serviceDomain.host.get)
        .withQueryString(queryStringParams: _*)
        .withBody(body)

    // TODO: Document "relative path" portion here
    val allSignedHeaders = generateSignedHeaders(
      incomingRequest.path,
      incomingRequest.method,
      incomingRequest.queryString,
      outgoingRequest.headers,
      maybeBody.map(_.toArray)
    )

    val onlyNewHeaders = allSignedHeaders -- outgoingRequest.headers.keys
    outgoingRequest.withHeaders(onlyNewHeaders.toSeq: _*)
  }

  /**
    * Generate AWS signed headers
    *
    * @param uri URL of incoming request
    * @param method HTTP method of incoming request
    * @param queryParameters Query parameters of incoming request
    * @param headers Headers of incoming request
    * @param payload Payload of incoming request
    * @return All headers, including new headers containing signing details
    */
  private def generateSignedHeaders(
    uri: String,
    method: String,
    queryParameters: Map[String, Seq[String]],
    headers: Map[String, Seq[String]],
    payload: Option[Array[Byte]]
  ) = {
    def clock(): LocalDateTime = LocalDateTime.now(ZoneId.of("UTC"))

    // we must encode asterisks in paths when we sign the requests
    // see https://github.com/ticofab/aws-request-signer/issues/17
    // for a good explanation of the need for this behavior
    val signingEncodingConfig = UriConfig(encoder = percentEncode ++ '*')
    val correctlyEncodedUri = Uri.parse(uri).toString(signingEncodingConfig) // TODO: Move this encoding logic to the signing plugin

    // take only the first value of each query string parameter
    val queryStringParameters = queryParameters.mapValues(_.headOption.getOrElse(""))
    // TODO: Don't like this map->seq->map conversion
    // TODO: Combine this ordering with the ordering above
    val sortedQueryStringParameters = queryStringParameters.toSeq.sortBy(_._1).toMap

    AwsSigner(
      awsCredentialsProvider,
      configuration.getString("proxy.aws.region").get,
      configuration.getString("proxy.aws.service").get,
      clock
    ).getSignedHeaders(
      correctlyEncodedUri,
      method,
      sortedQueryStringParameters,
      headers.map {
        // take only the first value of each header
        // TODO: We're clobbering headers with multiple values
        case (key, values) => (key, values.head)
      },
      payload
    )
  }

  private def awsCredentialsProvider = {
    (configuration.getString("proxy.aws.accessKey"), configuration.getString("proxy.aws.secretkey")) match {
      // use the credentials specified in configuration if they exist
      case (Some(accessKey), Some(secretKey)) => new AWSStaticCredentialsProvider(
        new BasicAWSCredentials(
          accessKey,
          secretKey
        )
      )

      // allow the AWS SDK to retrieve the credentials from the environment
      case _ => new DefaultAWSCredentialsProviderChain
    }
  }

  private def streamResponse(request: WSRequest) = {
    request.stream.map {
      case StreamedResponse(response, body) =>

        val status = Status(response.status)

        val contentType = response.headers
            .get("Content-Type")
            .flatMap(_.headOption)
            .getOrElse("application/octet-stream")

        // if there's a content length, send that, otherwise return the body in chunks
        response.headers.get("Content-Length") match {
          case Some(Seq(length)) =>
            status.sendEntity(HttpEntity.Streamed(body, Some(length.toLong), Some(contentType)))
          case _ =>
            status.chunked(body).as(contentType)
        }
    }
  }

}
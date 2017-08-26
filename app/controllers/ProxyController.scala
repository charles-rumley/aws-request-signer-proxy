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
    // we must encode asterisks in paths when we sign the requests
    val signingEncodingConfig = UriConfig(encoder = percentEncode ++ '*')

    val queryStringParams = incomingRequest.queryString.map {
      // TODO: Review loss of multiple query string values caused by .head
      case (key, values) => (key, values.head)
    }.toSeq.sortBy(_._1)

    // TODO: we must override the Host header
    // TODO: figure out why I can't have Accept-Encoding
    val acceptableHeaders = incomingRequest.headers.headers.filterNot {
      case (k, _) => Seq("Host", "Accept-Encoding").contains(k)
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
        .withHeaders(acceptableHeaders: _*)
        .withHeaders("Host" -> serviceDomain.host.get)
        .withQueryString(queryStringParams: _*)
        .withBody(body)

    // drop query string params with multiple values
    val queryStringParameters = incomingRequest.queryString.mapValues(_.headOption.getOrElse(""))
    // TODO: Don't like this map->seq->map conversion
    // TODO: Combine this ordering with the ordering above
    val sortedQueryStringParameters = queryStringParameters.toSeq.sortBy(_._1).toMap

    if (incomingRequest.method.isEmpty) {
      throw new Exception("A method must be provided before signing the request!")
    }

    def clock(): LocalDateTime = LocalDateTime.now(ZoneId.of("UTC"))

    // TODO: Document "relative path" portion here
    val allSignedHeaders = AwsSigner(
      awsCredentialsProvider,
      configuration.getString("proxy.aws.region").get,
      configuration.getString("proxy.aws.service").get,
      clock
    ).getSignedHeaders(
      Uri.parse(incomingRequest.path).toString(signingEncodingConfig), // TODO: Move this encoding logic to the signing plugin
      incomingRequest.method,
      sortedQueryStringParameters,
      outgoingRequest.headers.map {
        // TODO: We're clobbering headers with multiple values
        case (key, values) => (key, values.head)
      },
      maybeBody.map(_.toArray)
    )

    val newHeaders = allSignedHeaders -- outgoingRequest.headers.keys
    outgoingRequest.withHeaders(newHeaders.toSeq: _*)
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
package controllers

import java.time.{LocalDateTime, ZoneId}
import javax.inject._

import akka.util.ByteString
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials, DefaultAWSCredentialsProviderChain}
import com.amazonaws.internal.StaticCredentialsProvider
import io.ticofab.AwsSigner
import play.api.http.HttpEntity
import play.api.libs.ws._
import play.api.mvc._
import com.netaporter.uri.{Uri, encoding}
import com.netaporter.uri.Uri.parse
import com.netaporter.uri.config.UriConfig
import com.netaporter.uri.dsl._
import play.api.libs.streams.Accumulator
import play.utils.UriEncoding

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.io.Source
import com.netaporter.uri.dsl._
import com.netaporter.uri.encoding._
import play.api.Configuration

@Singleton
class ProxyController @Inject()(ws: WSClient, configuration: Configuration) extends Controller {

  def any(path: String) = Action.async(parse.raw) { implicit request =>
    if (request.method == "PUT") {
      println("We've got a putter!")
    }

    streamResponse(proxyRequest(request))
  }

  private def proxyRequest(incomingRequest: Request[RawBuffer]) = {
    val esDomain = Uri.parse(configuration.getString("proxy.aws.serviceDomain").get)
    // we must encode asterisks in paths when we sign the requests
    val signingEncodingConfig = UriConfig(encoder = percentEncode ++ '*')
    val queryStringParams = incomingRequest.queryString.map {
      // todo be careful, I may be erasing important data here
      case (key, values) => (key, values.head)
    }.toSeq.sortBy(_._1)

    // store max 1024 KB in memory todo review this limit
    val maybeBody = incomingRequest.body.asBytes()

    // we must override the Host header
    // why figure out why I can't have Accept-Encoding
    val acceptableHeaders = incomingRequest.headers.headers.filterNot {
      case (k, _) => Seq("Host", "Accept-Encoding").contains(k)
    }

    val body: WSBody = maybeBody match {
      // todo switch to using a StreamedBody so we don't have to hold anything in memory
      case Some(b) => InMemoryBody(b)
      case None => EmptyBody
    }

    val maybeUrl = for {
      scheme <- esDomain.scheme
      host <- esDomain.host
    } yield {
      // todo make sure I didn't inadvertently miss ports here
      s"$scheme://$host${incomingRequest.path}"
    }

    // todo fail if we're missing parts of the URL

    // todo add query params
    // is Content-Type required?
    val outgoingRequest = ws
        .url(maybeUrl.get)
        .withMethod(incomingRequest.method)
        //        .withHeaders("Content-Type" -> "application/json") // this header should only be added if it doesn't exist yet
        .withHeaders(acceptableHeaders: _*)
        .withHeaders("Host" -> "")
        .withQueryString(queryStringParams: _*)
        .withBody(body)


    // drop query string params with multiple values
    val queryStringParameters = incomingRequest.queryString.mapValues(_.headOption.getOrElse(""))
    // todo don't like this map->seq->map conversion
    // todo combine this ordering with the ordering above
    val sortedQueryStringParameters = queryStringParameters.toSeq.sortBy(_._1).toMap

    val payload = maybeBody.map(_.toArray)

    if (incomingRequest.method.isEmpty) {
      throw new Exception("A method must be provided before signing the request!")
    }

    def clock(): LocalDateTime = LocalDateTime.now(ZoneId.of("UTC"))

    // todo remove mention of ES in here
    // todo document "relative path" portion here
    val allSignedHeaders = AwsSigner(
      awsCredentialsProvider,
      configuration.getString("proxy.aws.region").get,
      configuration.getString("proxy.aws.service").get,
      clock
    ).getSignedHeaders(
      Uri.parse(incomingRequest.path).toString(signingEncodingConfig), // todo move this encoding logic to the signing plugin
      incomingRequest.method,
      sortedQueryStringParameters,
      outgoingRequest.headers.map {
        // todo clobber headers with multiple values
        case (key, values) => (key, values.head)
      },
      payload
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

        val contentType = response.headers.get("Content-Type").flatMap(_.headOption)
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
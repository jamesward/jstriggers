package utils

import java.util.concurrent.TimeUnit

import com.ning.http.util.Base64
import play.api.Application
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee.Iteratee
import play.api.libs.json._
import play.api.libs.ws.{WSResponse, WSRequestHolder, WS}
import play.api.http.{MimeTypes, Status, HeaderNames}
import play.api.mvc.RequestHeader
import play.api.mvc.Results.EmptyContent
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Promise, Future}
import scala.concurrent.duration.{Duration, FiniteDuration}

class Force(implicit app: Application) {

  val API_VERSION = "33.0"

  val defaultTimeout = FiniteDuration(60, TimeUnit.SECONDS)
  val defaultPollInterval = FiniteDuration(1, TimeUnit.SECONDS)

  val consumerKey = app.configuration.getString("force.oauth.consumer-key").get
  val consumerSecret = app.configuration.getString("force.oauth.consumer-secret").get

  val ENV_PROD = "prod"
  val ENV_SANDBOX = "sandbox"
  val SALESFORCE_ENV = "salesforce-env"

  def loginUrl(env: String)(implicit request: RequestHeader): String = env match {
    case e @ ENV_PROD => "https://login.salesforce.com/services/oauth2/authorize?response_type=code&client_id=%s&redirect_uri=%s&state=%s".format(consumerKey, redirectUri, e)
    case e @ ENV_SANDBOX => "https://test.salesforce.com/services/oauth2/authorize?response_type=code&client_id=%s&redirect_uri=%s&state=%s".format(consumerKey, redirectUri, e)
  }

  def tokenUrl(env: String): String = env match {
    case ENV_PROD => "https://login.salesforce.com/services/oauth2/token"
    case ENV_SANDBOX => "https://test.salesforce.com/services/oauth2/token"
  }

  def userinfoUrl(env: String): String = env match {
    case ENV_PROD => "https://login.salesforce.com/services/oauth2/userinfo"
    case ENV_SANDBOX => "https://test.salesforce.com/services/oauth2/userinfo"
  }

  def redirectUri(implicit request: RequestHeader): String = {
    controllers.routes.Application.forceOAuthCallback("", "").absoluteURL(request.secure).stripSuffix("?code=&state=")
  }

  def ws(path: String, authInfo: AuthInfo): WSRequestHolder = {
    WS.
      url(s"${authInfo.instanceUrl}/services/data/v$API_VERSION/$path").
      withHeaders(HeaderNames.AUTHORIZATION -> s"Bearer ${authInfo.accessToken}")
  }

  def login(code: String, env: String)(implicit request: RequestHeader): Future[AuthInfo] = {
    val wsFuture = WS.url(tokenUrl(env)).withQueryString(
      "grant_type" -> "authorization_code",
      "client_id" -> consumerKey,
      "client_secret" -> consumerSecret,
      "redirect_uri" -> redirectUri,
      "code" -> code
    ).post(EmptyContent())

    wsFuture.flatMap { response =>
      val maybeAuthInfo = for {
        idUrl <- (response.json \ "id").asOpt[String]
        accessToken <- (response.json \ "access_token").asOpt[String]
        refreshToken <- (response.json \ "refresh_token").asOpt[String]
        instanceUrl <- (response.json \ "instance_url").asOpt[String]
      } yield AuthInfo(idUrl, accessToken, refreshToken, instanceUrl)

      maybeAuthInfo.fold {
        Future.failed[AuthInfo](UnauthorizedError(response.body))
      } {
        Future.successful
      }
    }
  }

  /*
  def refreshToken(org: Org): Future[String] = {
    val wsFuture = WS.url(tokenUrl(org.env)).withQueryString(
      "grant_type" -> "refresh_token",
      "refresh_token" -> org.refreshToken,
      "client_id" -> consumerKey,
      "client_secret" -> consumerSecret
    ).post(EmptyContent())

    wsFuture.flatMap { response =>
      (response.json \ "access_token").asOpt[String].fold {
        Future.failed[String](UnauthorizedError(response.body))
      } {
        Future.successful
      }
    }
  }
  */

  def userInfo(authInfo: AuthInfo): Future[JsValue] = {
    WS
      .url(authInfo.idUrl)
      .withHeaders(HeaderNames.AUTHORIZATION -> s"Bearer ${authInfo.accessToken}")
      .get()
      .ok(_.json)
  }

  def sobjects(authInfo: AuthInfo): Future[JsValue] = {
    ws("sobjects", authInfo).get().ok(_.json)
  }

  def toolingQuery(authInfo: AuthInfo, q: String): Future[JsValue] = {
    ws("tooling/query/", authInfo).withQueryString("q" -> q).get().ok(_.json)
  }

  def entityDefinitions(authInfo: AuthInfo): Future[JsValue] = {
    ws("tooling/sobjects/EntityDefinition", authInfo).get().ok(_.json)
  }

  def triggerableObjects(authInfo: AuthInfo): Future[JsValue] = {
    toolingQuery(authInfo, "SELECT FullName FROM EntityDefinition WHERE IsApexTriggerable = TRUE")
  }

  def createWorkflowOutboundMessage(name: String, sobject: String, endpointUrl: String, includeSessionId: Boolean = true)(authInfo: AuthInfo): Future[JsValue] = {

    /*

    val json = Json.obj(
      "Name" -> name,
      "Metadata" -> Json.obj(
        "ApiVersion" -> API_VERSION

        "fields" -> Json.arr(
          "Id"
        )
      )
        "fields" -> Json.arr(
          "Id"
        ),
        //"includeSessionId" -> includeSessionId,
        //"endpointUrl" -> endpointUrl
      //)
          //"EntityDefinitionId" -> sobject


      "IntegrationUserId": "005o0000000QkBUAA0",
      "integrationUser": "a@a.test",
      "Metadata": {
        "apiVersion": 33,
        "fields": [
          ...
          "Website",
          "YearStarted"
        ],
    )

    println(json)

    ws("tooling/sobjects/WorkflowOutboundMessage", authInfo).post(json).ok(_.json)
    //ws("tooling/sobjects/WorkflowOutboundMessage/04ko0000000Cjg5", authInfo).get().ok(_.json)
    */




  }

}

object Force {
  def apply(implicit app: Application) = new Force()
}

case class AuthInfo(idUrl: String, accessToken: String, refreshToken: String, instanceUrl: String)
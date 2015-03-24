package utils

import java.util.concurrent.TimeUnit

import play.api.Application
import play.api.http.HeaderNames
import play.api.libs.json._
import play.api.libs.ws.{WS, WSRequestHolder}
import play.api.mvc.RequestHeader
import play.api.mvc.Results.EmptyContent

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.xml.{NamespaceBinding, TopScope}

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

  def sobjectDescribe(authInfo: AuthInfo, sobject: String): Future[JsObject] = {
    ws(s"sobjects/$sobject/describe/", authInfo).get().ok(_.json.as[JsObject])
  }

  def createWorkflowOutboundMessage(authInfo: AuthInfo, name: String, sobject: String, endpointUrl: String, includeSessionId: Boolean = true): Future[String] = {
    /*
    RequestError: [{"message":"Cannot deserialize instance of complexvalue from VALUE_STRING value https://jstriggers-00do0000000iilweaw.herokuapp.com/Contract at [line:1, column:81]","errorCode":"JSON_PARSER_ERROR"}]

    val json = Json.obj(
      "FullName" -> name,
      "Metadata" -> Json.obj(
        "apiVersion" -> API_VERSION,
        "endpointUrl" -> endpointUrl,
        "integrationUser" -> "a@a.test"
      )
    )

    ws("tooling/sobjects/WorkflowOutboundMessage", authInfo).post(json).ok(_.json)
    //ws("tooling/sobjects/WorkflowOutboundMessage/04ko0000000Cjg5", authInfo).get().ok(_.json)
    */

    userInfo(authInfo).flatMap { userInfo =>

      val username = (userInfo \ "username").as[String]

      sobjectDescribe(authInfo, sobject).flatMap { sobjectDescribe =>
        val fields = (sobjectDescribe \ "fields").as[Seq[JsObject]].filter(_.\("byteLength").as[Int] > 0)

        val fieldNames: Seq[String] = fields.map(_.\("name").as[String])

        val xml = <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:tns="urn:tooling.soap.sforce.com" xmlns:mns="urn:metadata.tooling.soap.sforce.com" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <soapenv:Header>
            <tns:SessionHeader>
              <tns:sessionId>00Do0000000IIlW!AQsAQMJwIgqhTpRWQdHYfkluKOFpdNeMC62csnevJ.6UuIGzsvlJeDuims5bBVbSj_ZztBfrrZ4ENTbVroO0LYblVHpDp67v</tns:sessionId>
            </tns:SessionHeader>
          </soapenv:Header>
          <soapenv:Body>
            <tns:create>
              <tns:sObjects xsi:type="WorkflowOutboundMessage">
                <mns:FullName>{sobject}.{name}</mns:FullName>
                <mns:Metadata>
                  <mns:apiVersion>{API_VERSION}</mns:apiVersion>
                  <mns:name>{name}</mns:name>
                  <mns:endpointUrl>{endpointUrl}</mns:endpointUrl>
                  <mns:integrationUser>{username}</mns:integrationUser>
                  <mns:includeSessionId>{includeSessionId}</mns:includeSessionId>
                  { fieldNames.map { field =>
                    <mns:fields>{field}</mns:fields>
                  }}
                </mns:Metadata>
              </tns:sObjects>
            </tns:create>
          </soapenv:Body>
        </soapenv:Envelope>

        val url = authInfo.instanceUrl + "/services/Soap/T/" + API_VERSION

        val headers = Seq(HeaderNames.CONTENT_TYPE -> "text/xml", "SOAPAction" -> "create")

        WS.url(url).withHeaders(headers: _*).post(xml).flatMap { response =>
          implicit val SoapNS = NamespaceBinding("soapenv", "http://schemas.xmlsoap.org/soap/envelope/", TopScope)

          val success: Boolean = (response.xml \ "Body" \ "createResponse" \ "result" \ "success").headOption.map(_.text.toBoolean).exists(identity)

          if (success) {
            val createId = (response.xml \ "Body" \ "createResponse" \ "result" \ "id").head.text
            Future.successful(createId)
          }
          else {
            val error = (response.xml \ "Body" \ "createResponse" \ "result" \ "errors").head
            Future.failed(new Exception(error.toString()))
          }
        }
      }
    }
  }

  def createWorkflowRule(authInfo: AuthInfo, name: String, sobject: String)(): Future[JsValue] = {

    val json = Json.obj(
      "FullName" -> (sobject + "." + name),
      "Metadata" -> Json.obj(
        "actions" -> Json.obj(
          "name" -> name,
          "type" -> "OutboundMessage"
        ),
        "active" -> true,
        "triggerType" -> "onAllChanges",
        "formula" -> "$User.IsActive"
      )
    )

    ws("tooling/sobjects/WorkflowRule", authInfo).post(json).created(_.json)
  }

}

object Force {
  def apply(implicit app: Application) = new Force()
}

case class AuthInfo(idUrl: String, accessToken: String, refreshToken: String, instanceUrl: String)
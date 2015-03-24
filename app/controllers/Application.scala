package controllers

import java.io.ByteArrayInputStream

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.{ArchiveEntry, ArchiveInputStream}
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import play.api.Play
import play.api.libs.json._
import play.api.libs.ws.WS
import play.api.mvc.Results.EmptyContent
import play.api.mvc._
import utils.{AuthInfo, Force, Heroku, NotFoundError}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}


object Application extends Controller {

  val FORCE_ID_URL        = "X-FORCE-ID-URL"
  val FORCE_ACCESS_TOKEN  = "X-FORCE-ACCESS-TOKEN"
  val FORCE_REFRESH_TOKEN = "X-FORCE-REFRESH-TOKEN"
  val FORCE_INSTANCE_URL  = "X-FORCE-INSTANCE-URL"
  val HEROKU_ACCESS_TOKEN = "X-HEROKU-ACCESS-TOKEN"

  lazy val force = Force(Play.current)
  lazy val heroku = Heroku(ExecutionContext.global, WS.client(Play.current), Play.current.configuration)

  /*
  private def errorsToJson(errors: Seq[(JsPath, Seq[ValidationError])]): JsObject = {
    Json.obj("errors" -> errors.toString())
  }
  */

  class RequestWithAuthInfo[A](val forceAuthInfo: AuthInfo, val herokuAccessToken: String, request: Request[A]) extends WrappedRequest[A](request)

  object ForceAuthInfoAction extends ActionBuilder[RequestWithAuthInfo] with ActionRefiner[Request, RequestWithAuthInfo] {
    override def refine[A](request: Request[A]): Future[Either[Result, RequestWithAuthInfo[A]]] = {
      Future.successful {
        val maybeAuthInfo = for {
          idUrl <- request.headers.get(FORCE_ID_URL)
          accessToken <- request.headers.get(FORCE_ACCESS_TOKEN)
          refreshToken <- request.headers.get(FORCE_REFRESH_TOKEN)
          instanceUrl <- request.headers.get(FORCE_INSTANCE_URL)
          herokuAcccessToken <- request.headers.get(HEROKU_ACCESS_TOKEN)
        } yield (AuthInfo(idUrl, accessToken, refreshToken, instanceUrl), herokuAcccessToken)

        maybeAuthInfo.map { case (forceAuthInfo, herokuAccessToken) =>
          new RequestWithAuthInfo(forceAuthInfo, herokuAccessToken, request)
        } toRight {
          render {
            // todo: logout
            case Accepts.Html() => Redirect(routes.Application.index()).flashing(
              FORCE_ID_URL -> null,
              FORCE_ACCESS_TOKEN -> null,
              FORCE_REFRESH_TOKEN -> null,
              FORCE_INSTANCE_URL -> null,
              HEROKU_ACCESS_TOKEN -> null
            )
            case Accepts.Json() => Unauthorized(Json.obj("error" -> s"The auth info was not set"))
          } (request)
        }
      }
    }
  }


  def index() = Action { implicit request =>

    val serverModel: JsObject = Json.obj(
      FORCE_ID_URL -> request.flash.get(FORCE_ID_URL),
      FORCE_ACCESS_TOKEN -> request.flash.get(FORCE_ACCESS_TOKEN),
      FORCE_REFRESH_TOKEN -> request.flash.get(FORCE_REFRESH_TOKEN),
      FORCE_INSTANCE_URL -> request.flash.get(FORCE_INSTANCE_URL),
      HEROKU_ACCESS_TOKEN -> request.flash.get(HEROKU_ACCESS_TOKEN)
    )

    Ok(views.html.index(force.loginUrl(force.ENV_PROD), force.loginUrl(force.ENV_SANDBOX), heroku.loginUrl, serverModel))
  }

  def forceOAuthCallback(code: String, env: String) = Action.async { implicit request =>

    val loginFuture = force.login(code, env)

    loginFuture.map { authInfo =>
      Redirect(routes.Application.index())
        .flashing(
          FORCE_ID_URL -> authInfo.idUrl,
          FORCE_ACCESS_TOKEN -> authInfo.accessToken,
          FORCE_REFRESH_TOKEN -> authInfo.refreshToken,
          FORCE_INSTANCE_URL -> authInfo.instanceUrl
        )
    } recover { case e: Error =>
      Redirect(routes.Application.index())
    }
  }

  def herokuOAuthCallback(code: String) = Action.async { implicit request =>
    heroku.login(code).map { json =>
      Redirect(routes.Application.index()).flashing(HEROKU_ACCESS_TOKEN -> (json \ "access_token").as[String])
    } recover { case e: Error =>
      Redirect(routes.Application.index())
    }
  }

  // todo: deal with other users in the same org sharing a single app
  private def appName(authInfo: AuthInfo): Future[String] = {
    force.userInfo(authInfo).map { json =>
      val orgId = (json \ "organization_id").as[String]
      s"jstriggers-$orgId".toLowerCase
    }
  }

  private def workflowEnabledSobjects(authInfo: AuthInfo): Future[Set[JsObject]] = {
    force.sobjects(authInfo).map { json =>
      (json \ "sobjects").as[Set[JsObject]].filter { sobject =>
        sobject.\("triggerable").as[Boolean] &&
        sobject.\("createable").as[Boolean] &&
        sobject.\("updateable").as[Boolean]
      }
    }
  }

  def sobjects() = ForceAuthInfoAction.async { request =>
    workflowEnabledSobjects(request.forceAuthInfo).map { sobject =>
      Ok(Json.toJson(sobject))
    }
  }

  // since this is the first call that has to do with the heroku app, if the app hasn't been created, it happens here
  def deps() = ForceAuthInfoAction.async { request =>
    val defaultDeps = Json.obj("jsforce" -> "1.4.1")

    appName(request.forceAuthInfo).flatMap { appName =>
      heroku.appInfo(appName)(request.herokuAccessToken).flatMap { appInfo =>
        latestSources(appName, Set("package.json"))(request.herokuAccessToken).map { sources =>
          val deps: JsObject = sources.get("package.json").fold(defaultDeps) { packageJsonBytes =>
            (Json.parse(packageJsonBytes) \ "dependencies").asOpt[JsObject].getOrElse(defaultDeps)
          }

          val userDeps = deps.value.filterNot { case (name, version) =>
            internalDeps.keys.contains(name)
          }

          Ok(JsObject(userDeps.toSeq))
        }
      } recoverWith {
        // app not found
        // initialize the app and the sources
        case e: NotFoundError =>
          heroku.createApp(appName)(request.herokuAccessToken).flatMap { _ =>
            val packageJsonContents = packageJson(appName, internalDeps ++ defaultDeps).toString()
            val serverjsContents = templates.js.server(Set.empty[String], request.forceAuthInfo.instanceUrl)

            val sources = Map(
              "package.json" -> packageJsonContents.getBytes,
              "server.js" -> serverjsContents.toString().getBytes
            )

            heroku.updateApp(appName, sources)(request.herokuAccessToken).map { _ =>
              Ok(defaultDeps)
            }
          }
      }
    }
  }

  private val internalDeps = Json.obj(
    "express" -> "4.12.3",
    "express-xml-bodyparser" -> "0.0.7"
  )

  private def packageJson(appName: String, deps: JsObject): JsObject = {
    Json.obj(
      "name" -> appName,
      "version" -> "0.0.0",
      "dependencies" -> deps,
      "engines" -> Json.obj("node" -> "0.12.x", "npm" -> "2.5.x")
    )
  }

  private def readZipEntry(zipInputStream: ArchiveInputStream, zipEntry: ArchiveEntry): Array[Byte] = {
    val buffer = Array.ofDim[Byte](zipEntry.getSize.toInt)
    zipInputStream.read(buffer, 0, zipEntry.getSize.toInt)
    buffer
  }


  private def latestSources(app: String, files: Set[String])(implicit accessToken: String): Future[Map[String, Array[Byte]]] = {
    heroku.latestSlugBlob(app).map { blob =>
      val archiveInputStream = new TarArchiveInputStream(new GzipCompressorInputStream(new ByteArrayInputStream(blob)))

      val sources = Stream
        .continually(archiveInputStream.getNextEntry)
        .takeWhile(_ != null)
        .filter { ze =>
          files.contains(ze.getName.stripPrefix("./app/"))
        }
        .map { ze =>
          val newName = ze.getName.stripPrefix("./app/")
          val contents = readZipEntry(archiveInputStream, ze)
          newName -> contents
        }
        .toMap

      archiveInputStream.close()

      sources
    }
  }

  private val internalFiles = Set("server.js", "package.json")

  private def defaultTrigger(sobject: String) = templates.js.trigger(sobject).toString()

  def trigger(sobject: String) = ForceAuthInfoAction.async { request =>
    val fileName = sobject + ".js"
    appName(request.forceAuthInfo).flatMap { appName =>
      latestSources(appName, Set(fileName))(request.herokuAccessToken).map { sources =>
        val triggerContents = sources.get(fileName).fold(defaultTrigger(sobject))(new String(_))
        Ok(triggerContents)
      } recover {
        case e: NotFoundError => Ok(defaultTrigger(sobject))
      }
      Future.successful(Ok(defaultTrigger(sobject)))
    }
  }

  def saveTrigger(sobject: String) = ForceAuthInfoAction.async(parse.json) { request =>
    val deps = (request.body \ "deps").as[JsObject]
    val trigger = (request.body \ "trigger").as[String]

    appName(request.forceAuthInfo).flatMap { appName =>
      workflowEnabledSobjects(request.forceAuthInfo).flatMap { sobjects =>
        val sobjectNames = sobjects.map(_.\("name").as[String])
        val sobjectFileNames = sobjectNames.map(_ + ".js")

        latestSources(appName, sobjectFileNames ++ internalFiles)(request.herokuAccessToken).flatMap { sources =>
          val newPackageJson = packageJson(appName, internalDeps ++ deps)
          val existingSObjectNames = sources.keys.map(_.stripSuffix(".js")).toSet.intersect(sobjectNames)
          val newSources = sources
            .updated("package.json", newPackageJson.toString().getBytes)
            .updated(sobject + ".js", trigger.getBytes)
            .updated("server.js", templates.js.server(existingSObjectNames + sobject, request.forceAuthInfo.instanceUrl).toString().getBytes)

          val updateAppFuture = heroku.updateApp(appName, newSources)(request.herokuAccessToken)

          val addWorkflowFuture = if (existingSObjectNames.contains(sobject)) {
            Future.successful(Json.obj())
          }
          else {
            val name = "jstrigger_" + sobject
            val endpointUrl = s"https://$appName.herokuapp.com/$sobject"
            force.createWorkflowOutboundMessage(request.forceAuthInfo, name, sobject, endpointUrl).flatMap { workflowOutboundMessageId =>
              force.createWorkflowRule(request.forceAuthInfo, name, sobject)
            }
          }

          for {
            _ <- updateAppFuture
            _ <- addWorkflowFuture
          } yield Ok(EmptyContent())
        }
      }
    }
  }

}

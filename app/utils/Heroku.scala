package utils

import java.io.ByteArrayOutputStream

import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveOutputStream}
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import play.api.Configuration
import play.api.http.{HeaderNames, Status}
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.libs.ws.{InMemoryBody, WSClient, WSRequestHolder}
import play.api.mvc.Results.EmptyContent

import scala.concurrent.{ExecutionContext, Future}

class Heroku(implicit ec: ExecutionContext, ws: WSClient, config: Configuration) {

  val oauthId = config.getString("heroku.oauth.id").get
  val oauthSecret = config.getString("heroku.oauth.secret").get

  val loginUrl = "https://id.heroku.com/oauth/authorize?client_id=%s&response_type=code&scope=%s".format(oauthId, "global")

  private def ws(path: String)(implicit accessToken: String): WSRequestHolder = {
    ws
      .url("https://api.heroku.com" + path)
      .withHeaders(
        HeaderNames.ACCEPT -> "application/vnd.heroku+json; version=3",
        HeaderNames.AUTHORIZATION -> s"Bearer $accessToken"
      )
  }

  def login(code: String): Future[JsValue] = {
    ws
    .url("https://id.heroku.com/oauth/token")
    .withQueryString(
      "grant_type" -> "authorization_code",
      "code" -> code,
      "client_secret" -> oauthSecret
    )
    .post(EmptyContent())
    .flatMap { response =>
      response.status match {
        case Status.OK => Future.successful(response.json)
        case _ => Future.failed(new Exception(response.body))
      }
    }
  }

  def appInfo(app: String)(implicit accessToken: String): Future[JsValue] = {
    ws(s"/apps/$app").get().ok(_.json)
  }

  def createApp(app: String)(implicit accessToken: String): Future[JsValue] = {
    ws(s"/apps").post(Json.obj("name" -> app)).created(_.json)
  }

  def releases(app: String)(implicit accessToken: String): Future[JsArray] = {
    ws(s"/apps/$app/releases").get().ok(_.json.as[JsArray])
  }

  def latestSlugBlob(app: String)(implicit accessToken: String): Future[Array[Byte]] = {
    releases(app).flatMap { releases =>
      releases.as[Seq[JsObject]].filter(_.\("slug").asOpt[JsObject].isDefined).sortBy(_.\("version").as[Int]).lastOption.fold {
        Future.failed[Array[Byte]](NotFoundError("No releases"))
      } { release =>
        val slugId = (release \ "slug" \ "id").as[String]
        slug(app, slugId).flatMap(slugBlob)
      }
    }
  }

  def slug(app: String, slugId: String)(implicit accessToken: String): Future[JsValue] = {
    ws(s"/apps/$app/slugs/$slugId").get().ok(_.json)
  }

  def slugBlob(jsValue: JsValue)(implicit accessToken: String): Future[Array[Byte]] = {
    val url = (jsValue \ "blob" \ "url").as[String]
    ws.url(url).getStream().flatMap { case (headers, enumerator) =>
      headers.status match {
        case Status.OK =>
          enumerator |>>> Iteratee.consume[Array[Byte]]()
        case Status.NOT_FOUND =>
          Future.failed(NotFoundError(""))
      }
    }
  }

  def createSource(app: String)(implicit accessToken: String): Future[JsValue] = {
    ws(s"/apps/$app/sources").post(EmptyContent()).created(_.json)
  }

  def createBuild(app: String, sourceUrl: String)(implicit accessToken: String): Future[JsValue] = {
    val json = Json.obj(
      "source_blob" -> Json.obj("url" -> sourceUrl)
    )
    ws(s"/apps/$app/builds").post(json).created(_.json)
  }

  def updateApp(app: String, sources: Map[String, Array[Byte]])(implicit accessToken: String): Future[JsValue] = {
    createSource(app).flatMap { source =>
      val getUrl = (source \ "source_blob" \ "get_url").as[String]
      val putUrl = (source \ "source_blob" \ "put_url").as[String]

      val byteArrayOutputStream = new ByteArrayOutputStream()
      val gzipOutputStream = new GzipCompressorOutputStream(byteArrayOutputStream)
      val tarArchiveOutputStream = new TarArchiveOutputStream(gzipOutputStream)

      sources.foreach { case (name, bytes) =>
        val entry = new TarArchiveEntry(name)
        entry.setSize(bytes.length)
        tarArchiveOutputStream.putArchiveEntry(entry)
        tarArchiveOutputStream.write(bytes)
        tarArchiveOutputStream.closeArchiveEntry()
      }

      tarArchiveOutputStream.finish()
      tarArchiveOutputStream.close()
      gzipOutputStream.close()

      val tgzBytes = byteArrayOutputStream.toByteArray

      byteArrayOutputStream.close()

      // must override the Content-Type with an empty value for some reason
      ws.url(putUrl).withMethod("PUT").withBody(InMemoryBody(tgzBytes)).execute().okF { _ =>
        createBuild(app, getUrl)
      }
    }
  }

}

object Heroku {
  def apply(implicit ec: ExecutionContext, ws: WSClient, config: Configuration) = new Heroku()
}
package utils

import java.io.InputStream
import java.net.{URL, URLEncoder}
import javax.inject.Inject

import play.api.http.{HeaderNames, MimeTypes, Status}
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._
import play.api.libs.ws._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

class Bower @Inject() (ws: WSClient, git: Git, licenseDetector: LicenseDetector, maven: Maven) (implicit ec: ExecutionContext) {

  val BASE_URL = "https://bower-as-a-service.herokuapp.com"

  def convertDependenciesToMaven(dependencies: Map[String, String]): Future[Map[String, String]] = {
    maven.convertNpmBowerDependenciesToMaven(dependencies) { case (providedName, _, gitUrl) =>
      rawInfo(providedName, "latest").filter { packageInfo =>
        packageInfo.gitHubUri.map(_.toString) == Success(gitUrl)
      } map (_ => providedName)
    }
  }

  def versions(packageNameOrGitRepo: String): Future[Seq[String]] = {
    if (git.isGit(packageNameOrGitRepo)) {
      git.gitUrl(packageNameOrGitRepo).flatMap(git.versions)
    }
    else {
      val maybeName = Try {
        URLEncoder.encode(packageNameOrGitRepo, "UTF-8")
      }

      maybeName.toOption.fold[Future[Seq[String]]] {
        Future.failed(new Exception("Could not encode the URL for the specified package"))
      } { name =>
        ws.url(s"$BASE_URL/info/$name").get().flatMap { response =>
          response.status match {
            case Status.OK =>
              val versions = (response.json \ "versions").as[Seq[String]]
              val cleanVersions = versions.filterNot(_.contains("sha"))
              Future.successful(cleanVersions)
            case _ =>
              Future.failed(new Exception(response.body))
          }
        }
      }
    }
  }

  def versionsOnBranch(gitRepo: String, branch: String): Future[Seq[String]] = {
    git.gitUrl(gitRepo).flatMap(git.versionsOnBranch(_, branch))
  }

  def rawInfo(packageNameOrGitRepo: String, version: String): Future[PackageInfo] = {
    if (git.isGit(packageNameOrGitRepo)) {
      git.gitUrl(packageNameOrGitRepo).flatMap { gitUrl =>
        git.file(gitUrl, Some(version), "bower.json").map { bowerJson =>
          // add the gitUrl into the json since it is not in the file, just the json served by the Bower index
          val json = Json.parse(bowerJson).as[JsObject] + ("_source" -> JsString(gitUrl))

          val jsonWithCorrectVersion = (json \ "version").asOpt[String].fold {
            // the version was not in the json so add the specified version
            json + ("version" -> JsString(version))
          } { version =>
            // todo: resolve conflicts?
            // for now just use the version from the json
            json
          }

          jsonWithCorrectVersion.as[PackageInfo](Bower.jsonReads)
        }
      }
    }
    else {
      ws.url(s"$BASE_URL/info/$packageNameOrGitRepo/$version").get().flatMap { response =>
        response.status match {
          case Status.OK =>
            Future.successful(response.json.as[PackageInfo](Bower.jsonReads))
          case _ =>
            Future.failed(new Exception(response.body))
        }
      }
    }
  }

  def info(packageNameOrGitRepo: String, maybeVersion: Option[String] = None): Future[PackageInfo] = {

    // if no version was specified use the latest
    val versionFuture: Future[String] = maybeVersion.fold {
      versions(packageNameOrGitRepo).flatMap { versions =>
        versions.headOption.fold(Future.failed[String](new Exception("The latest version could not be determined.")))(Future.successful)
      }
    } (Future.successful)

    versionFuture.flatMap { version =>

      rawInfo(packageNameOrGitRepo, version).flatMap { initialInfo =>
        // deal with GitHub redirects

        val infoFuture: Future[PackageInfo] = initialInfo.gitHubHome.toOption.fold(Future.successful(initialInfo)) { gitHubHome =>
          ws.url(gitHubHome).withFollowRedirects(false).get().flatMap { homeTestResponse =>
            homeTestResponse.status match {
              case Status.MOVED_PERMANENTLY =>
                homeTestResponse.header(HeaderNames.LOCATION).fold(Future.successful(initialInfo)) { actualHome =>
                  val newSource = actualHome.replaceFirst("https://", "git://") + ".git"
                  Future.successful(initialInfo.copy(sourceUrl = newSource, homepage = actualHome))
                }
              case _ =>
                Future.successful(initialInfo)
            }
          }
        }

        infoFuture.flatMap { info =>
          // detect licenses if they are not specified in the bower.json
          if (info.licenses.isEmpty) {
            licenseDetector.detectLicense(info, maybeVersion)
          }
          else {
            val resolvedLicensesFuture = Future.sequence {
              info.licenses.map { license =>
                if (license.contains("/")) {
                  val contentsFuture = if (license.startsWith("http")) {

                    // Some license references point to an HTML GitHub page.  We need the raw text/plain content.
                    val hopefullyTextLicense = if (license.contains("github.com") && license.contains("/blob/")) {
                      license.replaceAllLiterally("/blob/", "/raw/")
                    }
                    else {
                      license
                    }

                    ws.url(hopefullyTextLicense).get().flatMap { response =>
                      response.status match {
                        case Status.OK if response.header(HeaderNames.CONTENT_TYPE).exists(_.startsWith(MimeTypes.TEXT)) => Future.successful(response.body)
                        case Status.OK => Future.failed(new Exception(s"License at $hopefullyTextLicense was not plain text"))
                        case _ => Future.failed(new Exception(s"Could not fetch license at $hopefullyTextLicense - response was: ${response.body}"))
                      }
                    }
                  }
                  else {
                    git.file(info.sourceConnectionUrl, Some(info.version), license)
                  }

                  contentsFuture.flatMap { contents =>
                    licenseDetector.licenseDetect(contents)
                  }
                }
                else {
                  Future.successful(license)
                }
              }
            }

            resolvedLicensesFuture.map { resolvedLicenses =>
              info.copy(licenses = resolvedLicenses)
            }
          }
        }

      }
    }
  }

  def zip(packageNameOrGitRepo: String, version: String): Future[InputStream] = {
    if (git.isGit(packageNameOrGitRepo)) {
      git.gitUrl(packageNameOrGitRepo).flatMap { gitUrl =>
        git.tar(gitUrl, Some(version), Set("bower_modules"))
      }
    }
    else {
      Future.fromTry {
        Try {
          val url = new URL(s"$BASE_URL/download/$packageNameOrGitRepo/$version")
          url.openConnection().getInputStream
        }
      }
    }
  }

}

object Bower {
  val sourceReads = (__ \ "_source").read[String].map(_.replace("git://", "https://").stripSuffix(".git"))

  implicit def jsonReads: Reads[PackageInfo] = (
    (__ \ "name").read[String] ~
    (__ \ "version").read[String] ~
    (__ \ "homepage").read[String].orElse(sourceReads) ~
    sourceReads ~
    (__ \ "_source").read[String] ~
    sourceReads.map(_ + "/issues") ~
    (__ \ "license").read[Seq[String]].orElse((__ \ "license").read[String].map(Seq(_))).orElse(Reads.pure(Seq.empty[String])) ~
    (__ \ "dependencies").read[Map[String, String]].orElse(Reads.pure(Map.empty[String, String])) ~
    Reads.pure(WebJarType.Bower)
  )(PackageInfo.apply _)
}


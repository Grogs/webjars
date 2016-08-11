package utils

import java.io.{File, InputStream}
import java.net.{URI, URL}
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import javax.inject.Inject

import org.eclipse.jgit.api.{Git => GitApi}
import org.eclipse.jgit.lib.Repository
import org.springframework.util.FileSystemUtils
import play.api.http.{HeaderNames, Status}
import play.api.libs.ws.WSClient

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.{Codec, Source}
import scala.util.Try

class Git @Inject() (ws: WSClient) (implicit ec: ExecutionContext) {

  val cacheDir = Files.createTempDirectory("git")

  def isGit(packageNameOrGitRepo: String): Boolean = {
    packageNameOrGitRepo.contains("/") && !packageNameOrGitRepo.startsWith("@")
  }

  def resolveRedir(httpUrl: String): Future[String] = {
    ws.url(httpUrl).withFollowRedirects(false).head().flatMap { response =>
      response.status match {
        case Status.MOVED_PERMANENTLY | Status.FOUND =>
          // todo: max redirects
          response.header(HeaderNames.LOCATION).fold(Future.failed[String](new Exception("Could not get redir location"))) { location =>
            val redirUrl = if (location.startsWith("/")) {
              // relative url
              val url = new URL(httpUrl)
              url.getProtocol + "://" + url.getHost + location
            }
            else {
              location
            }

            // keep resolving until there is an OK
            resolveRedir(redirUrl)
          }
        case Status.OK =>
          Future.successful(httpUrl)
        case _ =>
          Future.failed(new Exception(s"Could not get HEAD for url: $httpUrl"))
      }
    }
  }

  def gitUrl(gitRepo: String): Future[String] = {
    val resolvedUrl = if (gitRepo.contains("://")) {
      gitRepo
    }
    else if (gitRepo.contains("github:")) {
      gitRepo.replaceAllLiterally("github:", "https://github.com/")
    }
    else {
      // infer github
      s"https://github.com/$gitRepo"
    }

    val jgitReadyUrl = resolvedUrl.replaceAllLiterally("git+", "")

    if (jgitReadyUrl.startsWith("http")) {
      resolveRedir(jgitReadyUrl)
    }
    else {
      Future.successful(jgitReadyUrl)
    }
  }

  def versions(gitRepo: String): Future[Seq[String]] = {
    gitUrl(gitRepo).flatMap { url =>
      Future.fromTry {
        Try {
          val tags = GitApi.lsRemoteRepository()
            .setRemote(url)
            .setTags(true)
            .call()

          tags.asScala.map(_.getName.stripPrefix("refs/tags/")).toSeq.sorted(VersionOrdering).reverse
        }
      }
    }
  }

  def versionsOnBranch(gitRepo: String, branch: String): Future[Seq[String]] = {
    gitUrl(gitRepo).flatMap { url =>
      cloneOrCheckout(gitRepo, Some(s"origin/$branch")).flatMap { baseDir =>
        Future.fromTry {
          Try {
            val commits = GitApi.open(baseDir).log().call()
            commits.asScala.map(_.getId.abbreviate(10).name()).toSeq
          }
        }
      }
    }
  }

  def branches(gitRepo: String): Future[Seq[String]] = {
    gitUrl(gitRepo).flatMap { url =>
      Future {
        GitApi.lsRemoteRepository()
          .setHeads(true)
          .setTags(false)
          .setRemote(url)
          .call()
          .asScala
          .toSeq
          .map { ref =>
            ref.getName.stripPrefix("refs/heads/")
          }
      }
    }
  }

  def cloneOrCheckout(gitRepo: String, version: Option[String]): Future[File] = {
    val baseDir = new File(cacheDir.toFile, gitRepo)

    val baseDirFuture = if (!baseDir.exists()) {
      // clone the repo
      gitUrl(gitRepo).flatMap { url =>
        val cloneFuture = Future.fromTry {
          Try {
            val clone = GitApi.cloneRepository()
              .setURI(url)
              .setDirectory(baseDir)
              .setCloneAllBranches(true)

            clone.call()

            baseDir
          }
        }

        cloneFuture.onFailure {
          case _ => FileSystemUtils.deleteRecursively(baseDir)
        }

        cloneFuture
      }
    }
    else {
      Future.successful(baseDir)
    }

    baseDirFuture.flatMap { baseDir =>
      Future.fromTry {
        Try {
          // checkout the version
          val checkout = GitApi.open(baseDir).checkout()

          version.fold(checkout.setName("origin/master"))(checkout.setName)

          checkout.call()

          baseDir
        }
      }
    }

  }

  def file(gitRepo: String, version: Option[String], file: String): Future[String] = {
    cloneOrCheckout(gitRepo, version).flatMap { baseDir =>
      Future.fromTry {
        Try {
          val decoder = Codec.UTF8.decoder.onMalformedInput(CodingErrorAction.IGNORE)
          Source.fromFile(new File(baseDir, file))(decoder).mkString
        }
      }
    }
  }

  def tar(gitRepo: String, version: Option[String], excludes: Set[String]): Future[InputStream] = {
    cloneOrCheckout(gitRepo, version).flatMap { baseDir =>
      // todo: apply .npmignore

      val resolvedExcludes = excludes.map(new File(baseDir, _))

      val allExcludes = Set(new File(baseDir, ".git")) ++ resolvedExcludes

      Future.fromTry {
        ArchiveCreator.tarDir(baseDir, allExcludes)
      }
    }
  }

}

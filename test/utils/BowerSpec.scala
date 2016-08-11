package utils

import java.io.BufferedInputStream

import akka.util.Timeout
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import play.api.i18n.MessagesApi
import play.api.test._

import scala.concurrent.duration._

class BowerSpec extends PlaySpecification with GlobalApplication {

  override implicit def defaultAwaitTimeout: Timeout = 300.seconds

  lazy val messages = application.injector.instanceOf[MessagesApi]
  lazy val bower = application.injector.instanceOf[Bower]

  "jquery info" should {
    "work with a correct version" in {
      await(bower.info("jquery", Some("1.11.1"))).name must equalTo("jquery")
    }
    "fail with an invalid version" in {
      await(bower.info("jquery", Some("0.0.0"))) must throwA[Exception]
    }
    "have a license" in {
      await(bower.info("jquery", Some("1.11.1"))).licenses must contain("MIT")
    }
  }
  "bootstrap" should {
    val info = await(bower.info("bootstrap", Some("3.3.2")))
    "have a license" in {
      info.licenses must contain("MIT")
    }
    "have a dependency on jquery" in {
      info.dependencies must contain("jquery" -> ">= 1.9.1")
    }
  }
  "dc.js" should {
    "have the corrected source url" in {
      await(bower.info("dc.js", Some("1.7.3"))).sourceUrl must beEqualTo("git://github.com/dc-js/dc.js.git")
    }
  }
  "sjcl" should {
    "download" in {
      val is = new BufferedInputStream(await(bower.zip("sjcl", "1.0.2")))
      val zis = new ArchiveStreamFactory().createArchiveInputStream(is)
      val files = Stream.continually(zis.getNextEntry).takeWhile(_ != null).map(_.getName).toSeq
      files must contain ("sjcl.js")
    }
  }
  "angular" should {
    "have an MIT license" in {
      await(bower.info("angular", Some("1.4.0"))).licenses must contain("MIT")
    }
  }
  "angular-equalizer" should {
    "have an MIT license" in {
      await(bower.info("angular-equalizer", Some("2.0.1"))).licenses must contain("MIT")
    }
  }

  "valid git short url" should {
    "have versions" in {
      val versions = await(bower.versions("PolymerElements/iron-elements"))
      versions.length must beGreaterThan (0)
      versions.contains("v1.0.0") must beTrue
    }
  }
  "invalid git url" should {
    "fail" in {
      await(bower.versions("foo/bar")) must throwA[Exception]
    }
  }
  "git repo master info" should {
    "work" in {
      val info = await(bower.info("PolymerElements/iron-elements"))
      info.name must beEqualTo ("iron-elements")
      info.version mustNotEqual ""
      info.sourceConnectionUrl must beEqualTo ("https://github.com/PolymerElements/iron-elements")
      info.sourceUrl must beEqualTo ("https://github.com/PolymerElements/iron-elements")
    }
  }
  "git repo tagged version info" should {
    "work" in {
      val info = await(bower.info("PolymerElements/iron-elements", Some("v1.0.0")))
      info.name must beEqualTo ("iron-elements")
      info.version must beEqualTo ("1.0.0")
    }
  }
  "git repo tagged version zip" should {
    "work" in {
      val zip = await(bower.zip("PolymerElements/iron-elements", "v1.0.0"))
      val bufferedInputStream = new BufferedInputStream(zip)
      val archiveStream = new ArchiveStreamFactory().createArchiveInputStream(bufferedInputStream)

      bufferedInputStream.available() must beEqualTo (10240)
    }
  }
  "git fork - github short url" should {
    "have the correct urls" in {
      val info = await(bower.info("PolymerElements/iron-elements"))
      info.name must beEqualTo ("iron-elements")
      info.version mustNotEqual ""
      info.homepage must beEqualTo ("https://github.com/PolymerElements/iron-elements")
      info.sourceConnectionUrl must beEqualTo ("https://github.com/PolymerElements/iron-elements")
      info.sourceUrl must beEqualTo ("https://github.com/PolymerElements/iron-elements")
      info.issuesUrl must beEqualTo ("https://github.com/PolymerElements/iron-elements/issues")
      info.gitHubHome must beASuccessfulTry("https://github.com/PolymerElements/iron-elements")
    }
  }
  "git fork - git url" should {
    "have the correct urls" in {
      val info = await(bower.info("git://github.com/PolymerElements/iron-elements"))
      info.name must beEqualTo ("iron-elements")
      info.version mustNotEqual ""
      info.homepage must beEqualTo ("https://github.com/PolymerElements/iron-elements")
      info.sourceConnectionUrl must beEqualTo ("git://github.com/PolymerElements/iron-elements")
      info.sourceUrl must beEqualTo ("https://github.com/PolymerElements/iron-elements")
      info.issuesUrl must beEqualTo ("https://github.com/PolymerElements/iron-elements/issues")
      info.gitHubHome must beASuccessfulTry("https://github.com/PolymerElements/iron-elements")
    }
  }

  "git commits" should {
    "work as version" in {
      val info = await(bower.info("git://github.com/dc-js/dc.js", Some("6e95388b9a")))
      info.version must beEqualTo ("6e95388b9a")
    }
  }
  "zeroclipboard 2.2.0" should {
    "have an MIT license" in {
      await(bower.info("zeroclipboard", Some("2.2.0"))).licenses must beEqualTo (Seq("MIT"))
    }
  }

  /*

  // This is broken due to upstream: https://github.com/webjars/webjars/issues/1265

  "tinymce-dist 4.2.5" should {
    "have an LGPL-2.1 license" in {
      await(bower.info("tinymce-dist", Some("4.2.5"))).licenses must beEqualTo (Seq("LGPL-2.1"))
    }
  }
  */

  "homepage" should {
    "be have a default" in {
      await(bower.info("git://github.com/millermedeiros/requirejs-plugins")).homepage must beEqualTo ("https://github.com/millermedeiros/requirejs-plugins")
    }
  }

  "angular-translate 2.7.2" should {
    "fail with a useful error" in {
      await(bower.info("angular-translate", Some("2.7.2"))) must throwA[LicenseNotFoundException](messages("licensenotfound.bower"))
    }
  }

  "git repo with branch" should {
    "fetch the versions" in {
      await(bower.versionsOnBranch("git://github.com/mdedetrich/requirejs-plugins", "jsonSecurityVulnerability")) must contain ("d9c103e7a0")
    }
  }

  "licenses" should {
    "be able to be fetched from git repos" in {
      await(bower.info("git://github.com/mdedetrich/requirejs-plugins", Some("d9c103e7a0"))).licenses must beEqualTo(Seq("MIT"))
    }
  }

  "long file names" should {
    "work" in {
      val is = new BufferedInputStream(await(bower.zip("highcharts/highcharts", "v4.2.5")))
      val zis = new ArchiveStreamFactory().createArchiveInputStream(is)
      val files = Stream.continually(zis.getNextEntry).takeWhile(_ != null).map(_.getName)
      files must contain ("tools/ant-contrib-0.6-bin/docs/api/net/sf/antcontrib/perf/AntPerformanceListener.StopWatchComparator.html")
    }
  }

  "converting npm deps to maven" should {
    "work with github short references" in {
      val deps = Map(
        "iron-a11y-announcer" -> "PolymerElements/iron-a11y-announcer"
      )
      val mavenDeps = await(bower.convertDependenciesToMaven(deps))
      mavenDeps.get("iron-a11y-announcer") must beSome ("[0,)")
    }
    "work with semver deps" in {
      val deps = Map(
        "iron-a11y-announcer" -> "PolymerElements/iron-a11y-announcer#^1.0.0"
      )
      val mavenDeps = await(bower.convertDependenciesToMaven(deps))
      mavenDeps.get("iron-a11y-announcer") must beSome ("[1.0.0,2)")
    }
    "work with tag deps" in {
      val deps = Map(
        "iron-a11y-announcer" -> "PolymerElements/iron-a11y-announcer#v1.0.0"
      )
      val mavenDeps = await(bower.convertDependenciesToMaven(deps))
      mavenDeps.get("iron-a11y-announcer") must beSome ("1.0.0")
    }
    "work with branch deps" in {
      val deps = Map(
        "iron-a11y-announcer" -> "PolymerElements/iron-a11y-announcer#add-polylint"
      )
      val mavenDeps = await(bower.convertDependenciesToMaven(deps))
      mavenDeps.get("iron-a11y-announcer") must beSome ("[0,)")
    }
    "work with sha deps" in {
      val deps = Map(
        "iron-a11y-announcer" -> "PolymerElements/iron-a11y-announcer#2432d39a1693ccd728cbe7eb55810063737d3403"
      )
      val mavenDeps = await(bower.convertDependenciesToMaven(deps))
      mavenDeps.get("iron-a11y-announcer") must beSome ("2432d39a1693ccd728cbe7eb55810063737d3403")
    }
    "avoid invalid names without version" in {
      val deps = Map(
        "iron-a11y-announcer" -> "https://github.com/PolymerElements/iron-a11y-keys.git"
      )
      val mavenDeps = await(bower.convertDependenciesToMaven(deps))
      mavenDeps.get("github-com-PolymerElements-iron-a11y-keys") must beSome ("[0,)")
    }
    "avoid invalid names with sem version" in {
      val deps = Map(
        "iron-a11y-announcer" -> "https://github.com/PolymerElements/iron-a11y-keys.git#^1.0.6"
      )
      val mavenDeps = await(bower.convertDependenciesToMaven(deps))
      mavenDeps.get("github-com-PolymerElements-iron-a11y-keys") must beSome ("[1.0.6,2)")
    }
    "avoid invalid names with tag version" in {
      val deps = Map(
        "iron-a11y-announcer" -> "https://github.com/PolymerElements/iron-a11y-keys.git#v1.0.6"
      )
      val mavenDeps = await(bower.convertDependenciesToMaven(deps))
      mavenDeps.get("github-com-PolymerElements-iron-a11y-keys") must beSome ("1.0.6")
    }
  }

}

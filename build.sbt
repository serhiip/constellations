import Dependencies.*
import ReleaseTransformations.*

inThisBuild(
  List(
    organization := "io.github.serhiip",
    homepage     := Some(url("https://github.com/serhiip/constellations")),
    licenses     := List("MIT" -> url("https://opensource.org/licenses/MIT")),
    scmInfo      := Some(
      ScmInfo(
        browseUrl  = url("https://github.com/serhiip/constellations"),
        connection = "scm:git:https://github.com/serhiip/constellations.git",
        devConnection = Some("scm:git:git@github.com:serhiip/constellations.git")
      )
    ),
    developers := List(
      Developer(
        id = "serhiip",
        name = "Serhii P",
        email = "serhiip@github.com",
        url = url("https://github.com/serhiip")
      )
    )
  )
)

ThisBuild / scalaVersion      := "3.7.1"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

ThisBuild / versionScheme := Some("early-semver")

// Route releases to local staging, and snapshots directly to the new Central Portal
ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

ThisBuild / pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toArray)

// sbt-release plugin defines these at project scope via projectSettings, so ThisBuild is overridden.
// Must be applied to each published project at project scope to take effect.
lazy val commonReleaseSettings = Seq[Setting[?]](
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  releaseVersionBump            := sbtrelease.Version.Bump.Next,
  releaseIgnoreUntrackedFiles   := true,
  releaseProcess                := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    publishArtifacts,
    releaseStepCommandAndRemaining("sonaRelease"),
    setNextVersion,
    commitNextVersion,
    pushChanges
  )
)

lazy val syncApiDocs = taskKey[Unit]("Sync generated unidoc into website/static/api")

lazy val root = (project in file("."))
  .settings(commonReleaseSettings)
  .settings(
    name              := "constellations",
    semanticdbVersion := scalafixSemanticdb.revision,
    publish / skip    := true
  )
  .aggregate(
    `constellations-core`,
    `constellations-openrouter`,
    `constellations-google-genai`,
    `constellations-examples`
  )

lazy val `constellations-core` = (project in file("core"))
  .settings(commonReleaseSettings)
  .settings(
    name := "constellations-core",
    libraryDependencies ++= Dependencies.constellationsCore
  )

lazy val `constellations-openrouter` = (project in file("openrouter"))
  .settings(commonReleaseSettings)
  .settings(
    name := "constellations-openrouter",
    libraryDependencies ++= Dependencies.constellationsOpenRouter,
    scalacOptions += "-Xmax-inlines:100"
  )
  .dependsOn(`constellations-core`)

lazy val `constellations-google-genai` = (project in file("google-genai"))
  .settings(commonReleaseSettings)
  .settings(
    name := "constellations-google-genai",
    libraryDependencies ++= Dependencies.constellationsGoogleGenai
  )
  .dependsOn(`constellations-core`)

lazy val `constellations-examples` = (project in file("examples"))
  .settings(
    name           := "constellations-examples",
    libraryDependencies ++= Dependencies.logging ++ Dependencies.logback ++ Dependencies.googleCloudNio,
    publish / skip := true
  )
  .dependsOn(`constellations-core`, `constellations-google-genai`, `constellations-openrouter`)

lazy val docs = project
  .in(file("docs"))
  .settings(
    name                                       := "constellations-docs",
    moduleName                                 := "constellations-docs",
    publish / skip                            := true,
    scalacOptions += "-experimental",
    mdocIn                                     := baseDirectory.value / "src" / "main" / "mdoc",
    mdocOut                                    := baseDirectory.value / ".." / "website" / "docs",
    mdocVariables                              := Map(
      "VERSION"       -> version.value,
      "SCALA_VERSION" -> scalaVersion.value
    ),
    // Scaladoc configuration - exclude non-library projects
    ScalaUnidoc / unidoc / unidocProjectFilter :=
      inProjects(`constellations-core`, `constellations-openrouter`, `constellations-google-genai`),
    syncApiDocs                                := {
      val _           = (Compile / unidoc).value
      val generated   = baseDirectory.value / "target" / s"scala-${scalaVersion.value}" / "unidoc"
      val destination = baseDirectory.value / ".." / "website" / "static" / "api"
      IO.delete(destination)
      IO.createDirectory(destination)
      IO.copyDirectory(generated, destination)
    },
    cleanFiles += (baseDirectory.value / ".." / "website" / "static" / "api"),
    // Docusaurus tasks
    docusaurusCreateSite                       := docusaurusCreateSite.dependsOn(mdoc.toTask(""), syncApiDocs).value,
    docusaurusPublishGhpages                   := docusaurusPublishGhpages.dependsOn(mdoc.toTask(""), syncApiDocs).value
  )
  .enablePlugins(MdocPlugin, DocusaurusPlugin, ScalaUnidocPlugin)
  .dependsOn(`constellations-core`, `constellations-openrouter`, `constellations-google-genai`)

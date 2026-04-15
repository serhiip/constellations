import Dependencies.*
import ReleaseTransformations.*

inThisBuild(
  List(
    organization := "io.github.serhiip",
    homepage     := Some(url("https://github.com/serhiip/constellations")),
    licenses     := List("MIT" -> url("https://opensource.org/licenses/MIT")),
    developers   := List(
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

// PGP Passphrase
ThisBuild / pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toArray)

// sbt-release configuration
releaseIgnoreUntrackedFiles               := true
ThisBuild / releaseVersionBump            := sbtrelease.Version.Bump.Next
ThisBuild / releasePublishArtifactsAction := PgpKeys.publishSigned.value

ThisBuild / releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  publishArtifacts,
  releaseStepCommand("sonaRelease"),
  setNextVersion,
  commitNextVersion,
  pushChanges
)

lazy val root = (project in file("."))
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
  .settings(
    name := "constellations-core",
    libraryDependencies ++= Dependencies.constellationsCore
  )

lazy val `constellations-openrouter` = (project in file("openrouter"))
  .settings(
    name := "constellations-openrouter",
    libraryDependencies ++= Dependencies.constellationsOpenRouter,
    scalacOptions += "-Xmax-inlines:100"
  )
  .dependsOn(`constellations-core`)

lazy val `constellations-google-genai` = (project in file("google-genai"))
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

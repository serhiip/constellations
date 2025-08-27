import Dependencies.*

inThisBuild(
  List(
    organization := "io.github.serhiip",
    homepage := Some(url("https://github.com/serhiip/constellations")),
    licenses := List("MIT" -> url("https://opensource.org/licenses/MIT")),
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

ThisBuild / scalaVersion := "3.7.1"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

// ThisBuild / publishTo := {
//   val centralSnapshots =
//     "https://central.sonatype.com/repository/maven-snapshots/"
//   if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
//   else localStaging.value
// }

ThisBuild / versionScheme := Some("early-semver")
// ThisBuild / Test / publishArtifact := false
// ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
ThisBuild / sonatypeRepository := "https://s01.oss.sonatype.org/service/local"
ThisBuild / versionScheme := Some("early-semver")

lazy val root = (project in file("."))
  .settings(
    name := "constellations",
    scalacOptions += "-experimental",
    semanticdbVersion := scalafixSemanticdb.revision,
    publish / skip := true
  )
  .aggregate(`constellations-core`, `constellations-openrouter`)

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

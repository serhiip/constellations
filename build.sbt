import Dependencies.*

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

ThisBuild / versionScheme          := Some("early-semver")
ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
ThisBuild / sonatypeRepository     := "https://s01.oss.sonatype.org/service/local"
ThisBuild / versionScheme          := Some("early-semver")

lazy val root = (project in file("."))
  .settings(
    name              := "constellations",
    scalacOptions += "-experimental",
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
    libraryDependencies ++= Dependencies.logging ++ Dependencies.logback,
    publish / skip := true
  )
  .dependsOn(`constellations-core`, `constellations-google-genai`)

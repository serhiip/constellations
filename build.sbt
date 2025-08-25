import Dependencies.*

ThisBuild / organization := "io.github.serhiip"
ThisBuild / scalaVersion := "3.7.1"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

// Publishing metadata for Maven Central
ThisBuild / homepage := Some(url("https://github.com/serhiip/constellations"))
ThisBuild / licenses := List(
  "MIT" -> url("https://opensource.org/licenses/MIT")
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/serhiip/constellations"),
    "scm:git@github.com:serhiip/constellations.git"
  )
)
ThisBuild / developers := List(
  Developer(
    id = "serhiip",
    name = "Serhii P",
    email = "serhiip@github.com",
    url = url("https://github.com/serhiip")
  )
)

ThisBuild / Test / publishArtifact := false
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

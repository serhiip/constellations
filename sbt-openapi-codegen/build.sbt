import ReleaseTransformations.*

ThisBuild / organization := "io.github.serhiip"
ThisBuild / homepage := Some(uri("https://github.com/serhiip/constellations"))
ThisBuild / licenses := List(License.MIT)
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / developers := List(
  Developer("serhiip", "Serhii P", "serhiip@github.com", uri("https://github.com/serhiip"))
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    browseUrl = uri("https://github.com/serhiip/constellations"),
    connection = "scm:git:https://github.com/serhiip/constellations.git",
    devConnection = Some("scm:git:git@github.com:serhiip/constellations.git")
  )
)

ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
ThisBuild / pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toArray)

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-openapi-codegen",
    version := "0.1.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % "0.14.10",
      "io.circe" %% "circe-parser" % "0.14.10",
      "io.circe" %% "circe-yaml" % "0.15.2",
      "org.scalameta" %% "munit" % "1.0.0" % Test
    ),
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++ Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog := false,
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,
    releaseVersionBump := sbtrelease.Version.Bump.Next,
    releaseIgnoreUntrackedFiles := true,
    releaseProcess := Seq[ReleaseStep](
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

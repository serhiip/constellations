import sbt.*

object Dependencies {
  val catsEffectVersion      = "3.6.0"
  val otel4sVersion          = "0.12.0"
  val munitCatsEffectVersion = "2.0.0"
  val circeVersion           = "0.14.10"
  val http4sVersion          = "0.23.30"
  val catsRetryVersion       = "4.0.0"
  val log4catsCoreVersion    = "2.7.1"
  val log4catsSlf4jVersion   = "2.7.1"

  val catsEffect = Seq(
    "org.typelevel"    %% "cats-effect"        % catsEffectVersion,
    "org.typelevel"    %% "cats-effect-kernel" % catsEffectVersion,
    "org.typelevel"    %% "cats-effect-std"    % catsEffectVersion,
    "org.typelevel"    %% "cats-mtl"           % "1.5.0",
    "com.github.cb372" %% "cats-retry"         % catsRetryVersion
  )

  val otel4sCore = Seq("org.typelevel" %% "otel4s-core" % otel4sVersion)

  val otel4s = Seq(
    "org.typelevel" %% "otel4s-oteljava"                 % otel4sVersion,
    "org.typelevel" %% "otel4s-oteljava-context-storage" % otel4sVersion,
    "org.typelevel" %% "otel4s-instrumentation-metrics"  % otel4sVersion
  )

  val logging = Seq(
    "org.typelevel" %% "log4cats-core"  % log4catsCoreVersion,
    "org.typelevel" %% "log4cats-slf4j" % log4catsSlf4jVersion
  )

  val logback = Seq(
    "ch.qos.logback" % "logback-classic" % "1.5.16"
  )

  val circe = Seq(
    "io.circe" %% "circe-core"    % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "io.circe" %% "circe-parser"  % circeVersion
  )

  val http4s = Seq(
    "org.http4s" %% "http4s-circe"        % http4sVersion,
    "org.http4s" %% "http4s-ember-server" % http4sVersion,
    "org.http4s" %% "http4s-ember-client" % http4sVersion,
    "org.http4s" %% "http4s-dsl"          % http4sVersion
  )

  val testing = Seq(
    "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test,
    "org.scalameta" %% "munit"             % "1.0.0"                % Test
  )

  val googleGenai = Seq(
    "com.google.genai" % "google-genai" % "1.18.0"
  )

  val constellationsCore =
    catsEffect ++ otel4s ++ logging ++ circe ++ testing ++ otel4sCore

  val constellationsOpenRouter = http4s ++ circe ++ testing

  val constellationsGoogleGenai = googleGenai ++ testing
}

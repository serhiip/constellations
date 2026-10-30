addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.2")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.2")
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.3.0")
addSbtPlugin("com.github.sbt" % "sbt-release" % "1.4.0")
addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.5.0")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.9.2")

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core"   % "0.14.10",
  "io.circe" %% "circe-parser" % "0.14.10"
)

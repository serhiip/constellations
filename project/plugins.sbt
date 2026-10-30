addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.7")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.9")
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.3.2")
addSbtPlugin("com.github.sbt" % "sbt-release" % "1.5.0")
addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.6.1")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.9.2")

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core"   % "0.14.10",
  "io.circe" %% "circe-parser" % "0.14.10"
)

lazy val rememberStamps = taskKey[Unit]("Record OpenAPI stamp modification times")
lazy val checkStamps = taskKey[Unit]("Fail when a later generation rewrites OpenAPI stamps")

def stampFiles(base: File): Seq[File] =
  IO.listFiles(base / "openapi-cache").toSeq.map(_ / "stamp").filter(_.exists())

lazy val root = (project in file("."))
  .enablePlugins(OpenApiCodegenPlugin)
  .settings(
    scalaVersion := "3.7.1",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % "0.14.10",
      "io.circe" %% "circe-parser" % "0.14.10",
      "org.scalameta" %% "munit" % "1.0.0" % Test
    ),
    rememberStamps := {
      val lines = stampFiles(target.value).map(file => s"${file.getAbsolutePath}=${file.lastModified()}")
      IO.write(baseDirectory.value / "stamp-times.txt", lines.mkString("\n"))
    },
    checkStamps := {
      val previous = IO.readLines(baseDirectory.value / "stamp-times.txt").map { line =>
        val split = line.lastIndexOf('=')
        line.take(split) -> line.drop(split + 1)
      }.toMap
      stampFiles(target.value).foreach { file =>
        val before = previous.getOrElse(file.getAbsolutePath, sys.error(s"missing recorded stamp ${file.getAbsolutePath}"))
        if before != file.lastModified().toString then sys.error(s"${file.getAbsolutePath} was rewritten")
      }
    },
    openApiSpecs := Seq(
      OpenApiSpec(
        name = "Widgets",
        source = OpenApiSource.FromFile(baseDirectory.value / "specs" / "widgets.json"),
        packageName = "demo.api"
      ),
      OpenApiSpec(
        name = "Gadgets",
        source = OpenApiSource.FromFile(baseDirectory.value / "specs" / "gadgets.yaml"),
        packageName = "demo.api"
      )
    )
  )

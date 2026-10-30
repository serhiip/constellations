import sbt.*
import sbt.Keys.*

import java.security.MessageDigest
import java.time.{Duration, Instant}
import scala.util.control.NonFatal

object OpenRouterCodegenPlugin extends AutoPlugin {
  override def trigger = noTrigger
  override def requires = sbt.plugins.JvmPlugin

  object autoImport {
    val openRouterSpecUrl = settingKey[String]("URL for the OpenRouter OpenAPI JSON specification")
    val openRouterSpecTtl = settingKey[Duration]("How long a cached OpenRouter spec is reused before refetching")
    val openRouterSchemaPackage = settingKey[String]("Package for generated OpenRouter schema types")
    val openRouterTypeOverrides = settingKey[Map[String, String]]("Schema path to Scala type, used when generation would fail")
    val openRouterExampleExclusions = settingKey[Set[String]]("Schema names whose spec examples are not round-tripped")
    val updateOpenRouterSpec = taskKey[File]("Force download of the latest OpenRouter OpenAPI spec and regenerate Scala sources")
    val openRouterGenerate = taskKey[Seq[File]]("Generate OpenRouter schema sources from the cached spec")
  }

  import autoImport.*

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    openRouterSpecUrl := "https://openrouter.ai/openapi.json",
    openRouterSpecTtl := Duration.ofHours(24),
    openRouterSchemaPackage := "io.github.serhiip.constellations.openrouter.schema",
    openRouterTypeOverrides := Map.empty,
    openRouterExampleExclusions := Set.empty,
    openRouterGenerate := Def.uncached {
      generate(forceFetch = false)
    },
    updateOpenRouterSpec := Def.uncached {
      val cache = specCache.value
      IO.createDirectory(cache.getParentFile)
      streams.value.log.info(s"Fetching latest OpenRouter spec from ${openRouterSpecUrl.value}")
      val raw = OpenRouterSchemaModel.fetchSpec(openRouterSpecUrl.value)
      IO.write(cache, raw)
      val generated = openRouterGenerate.value
      generated.find(_.getName == "UnionCodecs.scala").getOrElse(cache)
    },
    Compile / sourceGenerators += Def.task {
      Def.uncached {
        val dir = (Compile / sourceManaged).value.toPath
        openRouterGenerate.value.filter(file => file.toPath.startsWith(dir) && file.getName.endsWith(".scala"))
      }
    }.taskValue,
    Test / sourceGenerators += Def.task {
      Def.uncached {
        val dir = (Test / sourceManaged).value.toPath
        openRouterGenerate.value.filter(file => file.toPath.startsWith(dir) && file.getName.endsWith(".scala"))
      }
    }.taskValue,
    Test / resourceGenerators += Def.task {
      Def.uncached {
        val dir = (Test / resourceManaged).value.toPath
        openRouterGenerate.value.filter(file => file.toPath.startsWith(dir))
      }
    }.taskValue
  )

  private inline def generate(forceFetch: Boolean): Seq[File] = {
    val log = streams.value.log
    val url = openRouterSpecUrl.value
    val ttl = openRouterSpecTtl.value
    val pkg = openRouterSchemaPackage.value
    val overrides = openRouterTypeOverrides.value
    val exclusions = openRouterExampleExclusions.value
    val cache = specCache.value
    val stampFile = cache.getParentFile / "stamp"
    val mainDir = (Compile / sourceManaged).value / "openrouter" / "schema"
    val testDir = (Test / sourceManaged).value / "openrouter" / "schema"
    val resourceDir = (Test / resourceManaged).value
    val root = (LocalRootProject / baseDirectory).value

    val raw = loadSpec(url, cache, ttl, log, forceFetch)
    val key = stampKey(raw, pkg, overrides, exclusions, root)
    val stamped = readStamp(stampFile)
    if stamped.exists(_._1 == key) && stamped.forall(_._2.forall(_.exists())) then stamped.map(_._2).getOrElse(Nil)
    else {
      OpenRouterSchemaModel.analyze(raw, overrides, exclusions) match {
        case Left(failures) =>
          sys.error(failures.mkString("OpenRouter schema generation failed:\n", "\n", ""))
        case Right(model) =>
          val generated = OpenRouterSchemaEmitter.emit(model, pkg, url)
          val mainFiles = generated.main.toList.sortBy(_._1).map { (name, content) =>
            val file = mainDir / name
            writeIfChanged(file, content)
            file
          }
          val testFile = testDir / "SchemaExamplesSuite.scala"
          writeIfChanged(testFile, generated.testSource)
          val resourceFile = resourceDir / "openrouter-schema-examples.json"
          writeIfChanged(resourceFile, generated.examplesJson)
          deleteStale(mainDir, mainFiles.map(_.getName).toSet)
          val files = mainFiles :+ testFile :+ resourceFile
          writeStamp(stampFile, key, files)
          val records = model.definitions.collect { case _: OpenRouterSchemaModel.RecordDef => () }.size
          val enums = model.definitions.collect { case _: OpenRouterSchemaModel.EnumDef => () }.size
          val sums = model.definitions.collect { case _: OpenRouterSchemaModel.SumDef => () }.size
          log.info(s"Generated OpenRouter schema: $records records, $enums enums, $sums sums, ${mainFiles.size} files")
          files
      }
    }
  }

  private def specCache: Def.Initialize[File] = Def.setting {
    target.value / "openrouter-cache" / "openapi.json"
  }

  private def loadSpec(url: String, cache: File, ttl: Duration, log: sbt.internal.util.ManagedLogger, forceFetch: Boolean): String = {
    val expired = !cache.exists() || Duration.between(Instant.ofEpochMilli(cache.lastModified()), Instant.now()).compareTo(ttl) > 0
    if forceFetch || expired then
      try {
        log.info(s"Downloading OpenRouter spec from $url (cache missing or older than ${ttl.toHours}h)")
        val fetched = OpenRouterSchemaModel.fetchSpec(url)
        IO.createDirectory(cache.getParentFile)
        IO.write(cache, fetched)
        fetched
      } catch {
        case NonFatal(error) if !forceFetch && cache.exists() =>
          log.warn(s"Could not reach $url (${error.getMessage}); falling back to cached openapi.json")
          IO.read(cache)
      }
    else IO.read(cache)
  }

  private def stampKey(
      raw: String,
      pkg: String,
      overrides: Map[String, String],
      exclusions: Set[String],
      root: File
  ): String = {
    val sources = Seq(
      "OpenRouterSchemaModel.scala",
      "OpenRouterSchemaEmitter.scala",
      "OpenRouterCodegenPlugin.scala"
    ).map(name => root / "project" / name).map(file => if file.exists() then IO.read(file) else "").mkString
    val settings = overrides.toSeq.sorted.mkString + exclusions.toSeq.sorted.mkString + pkg + OpenRouterSchemaModel.GeneratorVersion
    sha256(sha256(raw) + sha256(settings) + sha256(sources))
  }

  private def readStamp(stampFile: File): Option[(String, List[File])] =
    if !stampFile.exists() then None
    else {
      val lines = IO.readLines(stampFile)
      lines.headOption.map { key =>
        key -> lines.tail.filter(_.nonEmpty).map(path => file(path))
      }
    }

  private def writeStamp(stampFile: File, key: String, files: Seq[File]): Unit = {
    IO.createDirectory(stampFile.getParentFile)
    IO.write(stampFile, (key +: files.map(_.getAbsolutePath)).mkString("\n"))
  }

  private def writeIfChanged(file: File, content: String): Unit = {
    if !file.exists() || IO.read(file) != content then {
      IO.createDirectory(file.getParentFile)
      IO.write(file, content)
    }
  }

  private def deleteStale(dir: File, keep: Set[String]): Unit =
    if dir.exists() then
      IO.listFiles(dir).foreach { file =>
        if file.getName.startsWith("OpenRouterSchema") && file.getName.endsWith(".scala") && !keep.contains(file.getName) then
          IO.delete(file)
      }

  private def sha256(text: String): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    digest.map("%02x".format(_)).mkString
  }
}

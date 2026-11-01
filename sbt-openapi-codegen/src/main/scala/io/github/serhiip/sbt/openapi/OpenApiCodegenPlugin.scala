package io.github.serhiip.sbt.openapi

import sbt.*
import sbt.Keys.*

import scala.util.control.NonFatal

object OpenApiCodegenPlugin extends AutoPlugin {
  val Version = "0.1.0-SNAPSHOT"

  override def trigger = noTrigger
  override def requires = sbt.plugins.JvmPlugin

  object autoImport {
    val openApiSpecs = settingKey[Seq[OpenApiSpec]]("OpenAPI specifications to generate Scala codecs for")
    val openApiGenerate = taskKey[Seq[File]]("Generate Scala sources from the configured OpenAPI specifications")
    val openApiUpdateSpecs = taskKey[Seq[File]]("Refetch remote OpenAPI specifications and regenerate Scala sources")

    val OpenApiSpec = _root_.io.github.serhiip.sbt.openapi.OpenApiSpec
    type OpenApiSpec = _root_.io.github.serhiip.sbt.openapi.OpenApiSpec
    val OpenApiSource = _root_.io.github.serhiip.sbt.openapi.OpenApiSource
    type OpenApiSource = _root_.io.github.serhiip.sbt.openapi.OpenApiSource
    val GeneratorOptions = _root_.io.github.serhiip.sbt.openapi.GeneratorOptions
    type GeneratorOptions = _root_.io.github.serhiip.sbt.openapi.GeneratorOptions
  }

  import autoImport.*

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    openApiSpecs := Nil,
    openApiGenerate := Def.uncached {
      generate(openApiSpecs.value, forceFetch = false)
    },
    openApiUpdateSpecs := Def.uncached {
      val log = streams.value.log
      val cacheRoot = target.value / "openapi-cache"
      openApiSpecs.value.foreach {
        case spec @ OpenApiSpec(_, OpenApiSource.FromUrl(url, _), _, _, _, _) =>
          val cache = cacheFile(cacheRoot, spec)
          log.info(s"Fetching latest ${spec.name} spec from $url")
          IO.createDirectory(cache.getParentFile)
          IO.write(cache, SpecLoader.fetch(url, userAgent(spec.name)))
          IO.delete(stampFile(cache))
        case _ => ()
      }
      openApiGenerate.value
    },
    Compile / sourceGenerators += Def.task {
      Def.uncached {
        val dir = (Compile / sourceManaged).value.toPath
        openApiGenerate.value.filter(file => file.toPath.startsWith(dir) && file.getName.endsWith(".scala"))
      }
    }.taskValue,
    Test / sourceGenerators += Def.task {
      Def.uncached {
        val dir = (Test / sourceManaged).value.toPath
        openApiGenerate.value.filter(file => file.toPath.startsWith(dir) && file.getName.endsWith(".scala"))
      }
    }.taskValue,
    Test / resourceGenerators += Def.task {
      Def.uncached {
        val dir = (Test / resourceManaged).value.toPath
        openApiGenerate.value.filter(file => file.toPath.startsWith(dir))
      }
    }.taskValue
  )

  private inline def generate(specs: Seq[OpenApiSpec], forceFetch: Boolean): Seq[File] = {
    val log = streams.value.log
    val mainRoot = (Compile / sourceManaged).value / "openapi"
    val testRoot = (Test / sourceManaged).value / "openapi"
    val resourceRoot = (Test / resourceManaged).value
    val cacheRoot = target.value / "openapi-cache"
    val fingerprint = codeFingerprint()
    checkDistinct(specs)
    val loaded = specs.map { spec =>
      val label = sourceLabel(spec)
      val fileName = sourceFileName(spec)
      val raw = load(spec, cacheRoot, log, forceFetch)
      spec -> (raw, label, fileName)
    }
    val generated = loaded.map { case (spec, loadedSpec) =>
      val (raw, label, fileName) = loadedSpec
      Generator.generate(raw, fileName, spec, label, Version) match {
        case Left(failures) =>
          sys.error(failures.mkString(s"OpenAPI schema generation failed for ${spec.name}:\n", "\n", ""))
        case Right(result) => (spec, raw, label, result)
      }
    }
    val collisions = Generator.collisions(generated.map { case (spec, _, _, result) => (spec.name, spec.packageName, result.definitionNames) })
    if collisions.nonEmpty then sys.error(collisions.mkString("OpenAPI schema generation failed:\n", "\n", ""))
    generated.flatMap { case (spec, raw, label, result) =>
      val scalaName = Names.scalaName(spec.name)
      val kebab = Names.kebab(spec.name)
      val mainDir = mainRoot / kebab
      val cache = cacheFile(cacheRoot, spec)
      val stamp = stampFile(cache)
      val key = Generator.stampKey(raw, spec, label, fingerprint)
      val stamped = readStamp(stamp)
      if stamped.exists(_._1 == key) && stamped.forall(_._2.forall(_.exists())) then {
        log.debug(s"OpenAPI schema $scalaName is up to date")
        stamped.map(_._2).getOrElse(Nil)
      } else {
        val mainFiles = result.files.main.toList.sortBy(_._1).map { case (name, content) =>
          val file = mainDir / name
          writeIfChanged(file, content)
          file
        }
        val testFile = testRoot / kebab / s"${scalaName}SchemaExamplesSuite.scala"
        val resourceFile = resourceRoot / Names.examplesResource(spec.name)
        val extra = (result.files.testSource, result.files.examplesJson) match {
          case (Some(source), Some(json)) =>
            writeIfChanged(testFile, source)
            writeIfChanged(resourceFile, json)
            List(testFile, resourceFile)
          case _ =>
            IO.delete(testFile)
            IO.delete(resourceFile)
            Nil
        }
        deleteStale(mainDir, mainFiles.map(_.getName).toSet, scalaName)
        val files = mainFiles ++ extra
        writeStamp(stamp, key, files)
        val records = result.files.main.keys.count(_.startsWith(s"${scalaName}Schema"))
        log.info(s"Generated OpenAPI schema $scalaName: ${result.definitionNames.size} definitions, $records files")
        files
      }
    }
  }

  private def checkDistinct(specs: Seq[OpenApiSpec]): Unit = {
    val names = specs.groupBy(spec => Names.kebab(spec.name)).collect { case (kebab, group) if group.size > 1 => kebab }
    if names.nonEmpty then sys.error(s"OpenAPI spec names are not unique: ${names.mkString(", ")}")
  }

  private def load(spec: OpenApiSpec, cacheRoot: File, log: sbt.internal.util.ManagedLogger, forceFetch: Boolean): String =
    spec.source match {
      case OpenApiSource.FromFile(file) =>
        if !file.exists() then sys.error(s"OpenAPI spec file does not exist: ${file.getAbsolutePath}")
        else IO.read(file)
      case OpenApiSource.FromUrl(url, ttl) =>
        val cache = cacheFile(cacheRoot, spec)
        val expired = !cache.exists() || (System.currentTimeMillis() - cache.lastModified()) > ttl.toMillis
        if forceFetch || expired then
          try {
            log.info(s"Downloading ${spec.name} spec from $url")
            val fetched = SpecLoader.fetch(url, userAgent(spec.name))
            IO.createDirectory(cache.getParentFile)
            IO.write(cache, fetched)
            fetched
          } catch {
            case NonFatal(error) if !forceFetch && cache.exists() =>
              log.warn(s"Could not reach $url (${error.getMessage}); falling back to cached spec")
              IO.read(cache)
          }
        else IO.read(cache)
    }

  private def sourceLabel(spec: OpenApiSpec): String = spec.source match {
    case OpenApiSource.FromFile(file)   => file.getAbsolutePath
    case OpenApiSource.FromUrl(url, _)  => url
  }

  private def sourceFileName(spec: OpenApiSpec): String = spec.source match {
    case OpenApiSource.FromFile(file)  => file.getName
    case OpenApiSource.FromUrl(url, _) => url
  }

  private def userAgent(name: String): String = s"sbt-openapi-codegen/${Version} ($name)"

  private def cacheFile(cacheRoot: File, spec: OpenApiSpec): File =
    cacheRoot / Names.kebab(spec.name) / "openapi.txt"

  private def stampFile(cache: File): File = cache.getParentFile / "stamp"

  private def codeFingerprint(): String = {
    val location = Option(getClass.getProtectionDomain).flatMap(domain => Option(domain.getCodeSource)).map(_.getLocation)
    location match {
      case None => "unknown"
      case Some(url) =>
        val file = new java.io.File(url.toURI)
        if !file.exists() then url.toString
        else if file.isDirectory then directoryFingerprint(file)
        else s"${file.getAbsolutePath}:${file.lastModified()}:${file.length()}"
    }
  }

  private def directoryFingerprint(root: File): String = {
    def walk(dir: File): List[File] = {
      val children = Option(dir.listFiles()).toList.flatten
      val (directories, files) = children.partition(_.isDirectory)
      files.filter(_.getName.endsWith(".class")).toList ++ directories.toList.flatMap(walk)
    }
    walk(root).map { file =>
      val relative = root.toPath.relativize(file.toPath).toString
      s"$relative:${file.lastModified()}:${file.length()}"
    }.sorted.mkString("\n")
  }

  private def readStamp(stampFile: File): Option[(String, List[File])] =
    if !stampFile.exists() then None
    else {
      val lines = IO.readLines(stampFile)
      lines.headOption.map { key =>
        key -> lines.tail.filter(_.nonEmpty).map(path => file(path))
      }
    }

  private def writeStamp(stamp: File, key: String, files: Seq[File]): Unit = {
    IO.createDirectory(stamp.getParentFile)
    IO.write(stamp, (key +: files.map(_.getAbsolutePath)).mkString("\n"))
  }

  private def writeIfChanged(file: File, content: String): Unit =
    if !file.exists() || IO.read(file) != content then {
      IO.createDirectory(file.getParentFile)
      IO.write(file, content)
    }

  private def deleteStale(dir: File, keep: Set[String], scalaName: String): Unit =
    if dir.exists() then
      IO.listFiles(dir).foreach { file =>
        if file.getName.startsWith(s"${scalaName}Schema") && file.getName.endsWith(".scala") && !keep.contains(file.getName) then
          IO.delete(file)
      }
}

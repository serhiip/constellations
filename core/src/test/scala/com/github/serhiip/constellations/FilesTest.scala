package io.github.serhiip.constellations

import java.nio.file.{Files as JFiles}

import cats.effect.{IO, Resource}
import com.google.common.jimfs.{Configuration, Jimfs}
import java.util.Base64
import munit.CatsEffectSuite

class FilesTest extends CatsEffectSuite:

  private val fs = ResourceSuiteLocalFixture("fs", Resource.make(IO(Jimfs.newFileSystem(Configuration.unix())))(fs => IO(fs.close())))

  override def munitFixtures = List(fs)

  test("Files.readFileAsBase64 should correctly read file and encode to base64") {
    val path           = fs().getPath("/test.txt")
    val content        = "a" * 42
    val expectedBase64 = Base64.getEncoder.encodeToString(content.getBytes)

    for
      _      <- IO(JFiles.write(path, content.getBytes))
      files  <- Files[IO](fs().getPath("/").toUri)
      result <- files.readFileAsBase64(path.toUri)
    yield assertEquals(result, expectedBase64)
  }

  test("Files.readFileAsBase64 should handle empty files") {
    val path           = fs().getPath("empty.txt")
    val expectedBase64 = ""

    for
      _      <- IO(JFiles.createFile(path))
      files  <- Files[IO](fs().getPath("/").toUri)
      result <- files.readFileAsBase64(path.toUri)
    yield assertEquals(result, expectedBase64)
  }

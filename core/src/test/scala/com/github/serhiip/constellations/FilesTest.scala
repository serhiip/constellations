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

  test("Files.writeStream should correctly write stream to file") {
    val path    = fs().getPath("/streamed.txt")
    val content = "stream content"
    val bytes   = fs2.Stream.emits(content.getBytes).covary[IO]

    for
      files  <- Files[IO](fs().getPath("/").toUri)
      _      <- files.writeStream(path.toUri, bytes)
      result <- IO(JFiles.readString(path))
    yield assertEquals(result, content)
  }

  test("Files.writeStream should fail if target file already exists") {
    val path  = fs().getPath("/collision.txt")
    val bytes = fs2.Stream.emits("new content".getBytes).covary[IO]

    for
      _      <- IO(JFiles.writeString(path, "existing content"))
      files  <- Files[IO](fs().getPath("/").toUri)
      result <- files.writeStream(path.toUri, bytes).attempt
    yield assert(result.isLeft)
  }

  test("Files.writeStream should handle sequential writes to different files") {
    val path1    = fs().getPath("/seq1.txt")
    val path2    = fs().getPath("/seq2.txt")
    val content1 = "first file"
    val content2 = "second file"

    for
      files   <- Files[IO](fs().getPath("/").toUri)
      _       <- files.writeStream(path1.toUri, fs2.Stream.emits(content1.getBytes).covary[IO])
      _       <- files.writeStream(path2.toUri, fs2.Stream.emits(content2.getBytes).covary[IO])
      result1 <- IO(JFiles.readString(path1))
      result2 <- IO(JFiles.readString(path2))
    yield
      assertEquals(result1, content1)
      assertEquals(result2, content2)
  }

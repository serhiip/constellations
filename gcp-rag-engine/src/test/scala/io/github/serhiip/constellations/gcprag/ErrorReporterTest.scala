package io.github.serhiip.constellations.gcprag

import cats.data.Ior
import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

final class ErrorReporterTest extends CatsEffectSuite:

  private val sinkPath = "gs://bucket/import-results/batch.ndjson"

  private val sampleNdjson =
    """{"OperationId": 3656045212267970560, "CreateTimestamp": "2026-08-03T16:55:31.210475", "Filename": "gs://bucket/doc.txt", "Status": "OK", "FileId": 5756803919416990370}
      |{"OperationId": 3656045212267970560, "CreateTimestamp": "2026-08-03T16:55:31.210475", "Filename": "gs://bucket/corrupt.pdf", "Status": "INVALID_ARGUMENT", "Message": "PDF was invalid or file contains no text pages."}
      |""".stripMargin

  private val allFailedNdjson =
    """{"OperationId": 1, "CreateTimestamp": "2026-08-03T16:55:31.210475", "Filename": "gs://bucket/corrupt.pdf", "Status": "INVALID_ARGUMENT", "Message": "bad pdf"}
      |""".stripMargin

  private val importResult = ImportResult(importedCount = 1, failedCount = 0, skippedCount = 0, files = Nil)

  private val handle = LroHandle("projects/p/locations/l/operations/1", LroKind.ImportFiles)

  private def started(await: IO[ImportResult]): StartedLro[IO, ImportResult] =
    StartedLro(handle, await)

  private def reporter(content: String): IO[ErrorReporter[IO]] =
    ErrorReporter.core(ImportResultLedger.core[IO](_ => content.pure[IO])).pure[IO]

  test("success path returns Ior.Right(ImportResult)") {
    reporter(sampleNdjson).flatMap { reporter =>
      reporter.report(started(importResult.pure[IO])).map(assertEquals(_, Ior.Right(importResult)))
    }
  }

  test("ImportFailed with partial success returns Ior.Both") {
    val cause = RagClient.Error.ImportFailed(
      importedCount = 1,
      failedCount = 1,
      skippedCount = 0,
      partialFailuresGcsPath = Some(sinkPath),
      partialFailuresBigQueryTable = None
    )
    reporter(sampleNdjson).flatMap { reporter =>
      reporter.report(started(cause.raiseError)).map {
        case Ior.Both(failures, result) =>
          assertEquals(failures.size, 1)
          assertEquals(failures.head.status, "INVALID_ARGUMENT")
          assertEquals(failures.head.filename, "gs://bucket/corrupt.pdf")
          assertEquals(result.importedCount, 1L)
          assertEquals(result.failedCount, 1L)
          assertEquals(result.partialFailuresGcsPath, Some(sinkPath))
        case other                      => fail(s"expected Ior.Both, got $other")
      }
    }
  }

  test("ImportFailed with no successes returns Ior.Left") {
    val cause = RagClient.Error.ImportFailed(
      importedCount = 0,
      failedCount = 1,
      skippedCount = 0,
      partialFailuresGcsPath = Some(sinkPath),
      partialFailuresBigQueryTable = None
    )
    reporter(allFailedNdjson).flatMap { reporter =>
      reporter.report(started(cause.raiseError)).map {
        case Ior.Left(failures) =>
          assertEquals(failures.size, 1)
          assertEquals(failures.head.status, "INVALID_ARGUMENT")
        case other              => fail(s"expected Ior.Left, got $other")
      }
    }
  }

  test("ImportFailed without sink path re-raises ImportFailed") {
    val cause = RagClient.Error.ImportFailed(
      importedCount = 0,
      failedCount = 1,
      skippedCount = 0,
      partialFailuresGcsPath = None,
      partialFailuresBigQueryTable = None
    )
    reporter(sampleNdjson).flatMap { reporter =>
      reporter.report(started(cause.raiseError)).attempt.map {
        case Left(err: RagClient.Error.ImportFailed) => assertEquals(err, cause)
        case other                                   => fail(s"expected ImportFailed, got $other")
      }
    }
  }

  test("bad NDJSON line raises DecodeFailed") {
    val cause = RagClient.Error.ImportFailed(
      importedCount = 0,
      failedCount = 1,
      skippedCount = 0,
      partialFailuresGcsPath = Some(sinkPath),
      partialFailuresBigQueryTable = None
    )
    reporter("""{"not": "valid for schema"}""").flatMap { reporter =>
      reporter.report(started(cause.raiseError)).attempt.map {
        case Left(err: ErrorReporter.Error.DecodeFailed) =>
          assertEquals(err.path, sinkPath)
          assert(err.message.nonEmpty)
        case other                                       => fail(s"expected DecodeFailed, got $other")
      }
    }
  }

  test("non-ImportFailed error is re-raised unchanged") {
    val boom = RuntimeException("boom")
    reporter(sampleNdjson).flatMap { reporter =>
      reporter.report(started(boom.raiseError)).attempt.map {
        case Left(err) => assertEquals(err, boom)
        case Right(_)  => fail("expected failure")
      }
    }
  }

  test("report does not execute await until the returned F is run") {
    var ran = false
    val lro = started(IO.delay { ran = true; importResult })
    reporter(sampleNdjson).flatMap { reporter =>
      val reported = reporter.report(lro)
      assertEquals(ran, false)
      reported.map { result =>
        assertEquals(ran, true)
        assertEquals(result, Ior.Right(importResult))
      }
    }
  }

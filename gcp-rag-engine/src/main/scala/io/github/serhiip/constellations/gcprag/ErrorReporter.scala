package io.github.serhiip.constellations.gcprag

import cats.data.{Ior, NonEmptyList}
import cats.effect.Sync
import cats.syntax.all.*

import ImportResultLedger.ImportFileOutcome

trait ErrorReporter[F[_]]:
  def report(lro: StartedLro[F, ImportResult]): F[ErrorReporter.ImportReport]

object ErrorReporter:

  type ImportReport = Ior[NonEmptyList[ImportFileOutcome], ImportResult]

  export ImportResultLedger.ImportFileOutcome
  export ImportResultLedger.Error

  def apply[F[_]: Sync](): F[ErrorReporter[F]] = gcs()

  def core[F[_]: Sync](ledger: ImportResultLedger[F]): ErrorReporter[F] =
    CoreErrorReporter(ledger)

  def gcs[F[_]: Sync](): F[ErrorReporter[F]] = ImportResultLedger.gcs[F]().map(core)

  private final class CoreErrorReporter[F[_]: Sync](ledger: ImportResultLedger[F]) extends ErrorReporter[F]:
    def report(lro: StartedLro[F, ImportResult]): F[ImportReport] =
      lro.await.attempt.flatMap {
        case Left(err: RagClient.Error.ImportFailed) =>
          err.partialFailuresGcsPath.liftTo[F](err).flatMap(path => ledger.read(path).map(toReport(err, _)))
        case Right(result)                           => result.rightIor.pure
        case Left(other)                             => other.raiseError[F, ImportReport]
      }

    private def toReport(err: RagClient.Error.ImportFailed, outcomes: List[ImportFileOutcome]): ImportReport =
      val result   =
        ImportResult(
          importedCount = err.importedCount,
          failedCount = err.failedCount,
          skippedCount = err.skippedCount,
          files = Nil,
          partialFailuresGcsPath = err.partialFailuresGcsPath,
          partialFailuresBigQueryTable = err.partialFailuresBigQueryTable
        )
      val failures = outcomes.filter(_.status != "OK")
      failures.toNel match
        case None                               => Ior.Right(result)
        case Some(nel) if err.importedCount > 0 => Ior.Both(nel, result)
        case Some(nel)                          => Ior.Left(nel)

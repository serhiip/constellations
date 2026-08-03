package io.github.serhiip.constellations.gcprag

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files as JFiles, FileSystemNotFoundException, FileSystems}
import java.nio.file.spi.FileSystemProvider
import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

import cats.data.{Ior, NonEmptyList}
import cats.effect.Sync
import cats.syntax.all.*
import io.circe.derivation.{Configuration, ConfiguredDecoder}
import io.circe.parser.decode

trait ErrorReporter[F[_]]:
  def report(lro: StartedLro[F, ImportResult]): F[ErrorReporter.ImportReport]

object ErrorReporter:

  type ImportReport = Ior[NonEmptyList[ImportFileOutcome], ImportResult]

  enum Error extends RuntimeException:
    case DecodeFailed(path: String, message: String)

    override def getMessage(): String = this match
      case DecodeFailed(path, message) => s"Failed to decode import result sink at $path: $message"

  private given Configuration = Configuration.default.copy(transformMemberNames = _.capitalize)

  final case class ImportFileOutcome(
      operationId: Long,
      createTimestamp: String,
      filename: String,
      status: String,
      message: Option[String] = None,
      fileId: Option[Long] = None
  ) derives ConfiguredDecoder

  def apply[F[_]: Sync](): F[ErrorReporter[F]] = gcs()

  def core[F[_]: Sync](readObject: URI => F[String]): F[ErrorReporter[F]] =
    CoreErrorReporter(readObject).pure[F]

  def gcs[F[_]: Sync](): F[ErrorReporter[F]] = core(defaultReadObject[F])

  private class CoreErrorReporter[F[_]: Sync](readObject: URI => F[String]) extends ErrorReporter[F]:
    def report(lro: StartedLro[F, ImportResult]): F[ImportReport] =
      lro.await.attempt.flatMap {
        case Left(err: RagClient.Error.ImportFailed) =>
          err.partialFailuresGcsPath.liftTo[F](err).flatMap(path => readAndParse(path).map(toReport(err, _)))
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

    private def readAndParse(path: String): F[List[ImportFileOutcome]] =
      readObject(URI.create(path)).flatMap { content =>
        content.linesIterator
          .map(_.trim)
          .filter(_.nonEmpty)
          .toList
          .traverse(line => decode[ImportFileOutcome](line).leftMap(err => Error.DecodeFailed(path, err.getMessage)))
          .liftTo[F]
      }

  private def defaultReadObject[F[_]: Sync](uri: URI): F[String] =
    for
      providerFound <- Sync[F].blocking:
                         Option
                           .when(uri.getScheme == "file")(FileSystems.getDefault.provider())
                           .orElse(
                             ServiceLoader
                               .load(classOf[FileSystemProvider], Thread.currentThread.getContextClassLoader)
                               .asScala
                               .find(_.getScheme == uri.getScheme)
                           )
      provider      <- providerFound.liftTo[F](FileSystemNotFoundException(s"Provider '${uri.getScheme}' not found"))
      content       <- Sync[F].blocking(String(JFiles.readAllBytes(provider.getPath(uri)), UTF_8))
    yield content

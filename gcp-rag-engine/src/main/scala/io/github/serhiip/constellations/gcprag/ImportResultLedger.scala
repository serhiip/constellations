package io.github.serhiip.constellations.gcprag

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files as JFiles, FileSystemNotFoundException, FileSystems}
import java.nio.file.spi.FileSystemProvider
import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

import cats.effect.Sync
import cats.syntax.all.*
import io.circe.derivation.{Configuration, ConfiguredDecoder}
import io.circe.parser.decode

trait ImportResultLedger[F[_]]:
  def read(path: String): F[List[ImportResultLedger.ImportFileOutcome]]

object ImportResultLedger:

  private given Configuration = Configuration.default.copy(transformMemberNames = _.capitalize)

  final case class ImportFileOutcome(
      operationId: Long,
      createTimestamp: String,
      filename: String,
      status: String,
      message: Option[String] = None,
      fileId: Option[Long] = None
  ) derives ConfiguredDecoder

  enum Error extends RuntimeException:
    case DecodeFailed(path: String, message: String)

    override def getMessage(): String = this match
      case DecodeFailed(path, message) => s"Failed to decode import result sink at $path: $message"

  def apply[F[_]: Sync](): F[ImportResultLedger[F]] = gcs()

  def gcs[F[_]: Sync](): F[ImportResultLedger[F]] =
    Sync[F].blocking(loadProviders()).map(providers => core(readObject(providers)))

  def core[F[_]: Sync](readObject: URI => F[String]): ImportResultLedger[F] =
    CoreImportResultLedger(readObject)

  def ragFileName(corpusName: String, fileId: Long): String =
    s"$corpusName/ragFiles/$fileId"

  def parse(path: String, content: String): Either[Error, List[ImportFileOutcome]] =
    content.linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList
      .traverse(line => decode[ImportFileOutcome](line).leftMap(err => Error.DecodeFailed(path, err.getMessage)))

  private def loadProviders(): Map[String, FileSystemProvider] =
    val loaded =
      ServiceLoader
        .load(classOf[FileSystemProvider], Thread.currentThread.getContextClassLoader)
        .asScala
        .map(provider => provider.getScheme -> provider)
        .toMap
    loaded + ("file" -> FileSystems.getDefault.provider())

  private def readObject[F[_]: Sync](providers: Map[String, FileSystemProvider])(uri: URI): F[String] =
    for
      provider <- providers.get(uri.getScheme).liftTo[F](FileSystemNotFoundException(s"Provider '${uri.getScheme}' not found"))
      content  <- Sync[F].blocking(String(JFiles.readAllBytes(provider.getPath(uri)), UTF_8))
    yield content

  private final class CoreImportResultLedger[F[_]: Sync](readObject: URI => F[String]) extends ImportResultLedger[F]:
    def read(path: String): F[List[ImportFileOutcome]] =
      readObject(URI.create(path)).flatMap(content => parse(path, content).liftTo[F])

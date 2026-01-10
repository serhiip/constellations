package io.github.serhiip.constellations

import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.spi.FileSystemProvider
import java.nio.file.{FileSystemNotFoundException}
import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

import cats.{Applicative, MonadThrow, ~>}
import cats.effect.Async
import cats.syntax.all.*
import fs2.io.file.{Files as Fs2Files, Path}
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.{Counter, Meter}
import org.typelevel.otel4s.trace.Tracer

import io.github.serhiip.constellations.common.Observability
import io.github.serhiip.constellations.common.Observability.*

trait Files[F[_]]:
  def readFileAsBase64(uri: URI): F[String]
  def writeStream(target: URI, bytes: fs2.Stream[F, Byte]): F[Unit]
  def resolve(relative: String): F[URI]

object Files:
  def apply[F[_]: Async: Fs2Files](baseUri: URI): F[Files[F]] =
    for
      providerFound <- Async[F]
                         .delay(
                           if (baseUri.getScheme == "file") Some(FileSystems.getDefault().provider())
                           else
                             ServiceLoader
                               .load(classOf[FileSystemProvider], Thread.currentThread().getContextClassLoader)
                               .asScala
                               .find(_.getScheme == baseUri.getScheme)
                         )
      provider      <- providerFound.map(_.pure).getOrElse(FileSystemNotFoundException(s"Provider '${baseUri.getScheme}' not found").raiseError)
    yield new:
      override def resolve(relative: String): F[URI] = Async[F].delay(baseUri.resolve(relative))

      override def readFileAsBase64(uri: URI): F[String] =
        for
          nioPath <- Async[F].delay(provider.getPath(uri))
          content <- Fs2Files[F]
                       .readAll(Path.fromNioPath(nioPath))
                       .through(fs2.text.base64.encode)
                       .compile
                       .string
        yield content

      override def writeStream(target: URI, bytes: fs2.Stream[F, Byte]): F[Unit] =
        for
          nioPath <- Async[F].delay(provider.getPath(target))
          exists  <- Async[F].delay(java.nio.file.Files.exists(nioPath))
          _       <- if exists then Async[F].raiseError(new java.io.IOException(s"File '$target' already exists"))
                     else Async[F].unit
          _       <- bytes.through(Fs2Files[F].writeAll(Path.fromNioPath(nioPath))).compile.drain
        yield ()

  def metered[F[_]: MonadThrow](delegate: Files[F], meters: Meters[F]): Files[F] =
    new Files[F]:
      override def resolve(relative: String): F[URI] =
        delegate.resolve(relative).withOperationCounters(meters.operationCounter, meters.errorCounter)

      override def readFileAsBase64(uri: URI): F[String] =
        delegate.readFileAsBase64(uri).withOperationCounters(meters.operationCounter, meters.errorCounter)

      override def writeStream(target: URI, bytes: fs2.Stream[F, Byte]): F[Unit] =
        delegate.writeStream(target, bytes).withOperationCounters(meters.operationCounter, meters.errorCounter)

  def traced[F[_]: MonadThrow: Tracer: StructuredLogger](delegate: Files[F]): Files[F] =
    new Files[F]:
      override def resolve(relative: String): F[URI] =
        Tracer[F]
          .span("files", "resolve")()
          .logged: logger =>
            delegate.resolve(relative).flatTap(result => logger.trace(s"Resolved $relative to $result"))

      override def readFileAsBase64(uri: URI): F[String] =
        Tracer[F]
          .span("files", "read-file-as-base64")()
          .logged: logger =>
            delegate.readFileAsBase64(uri).flatTap(_ => logger.trace(s"Read file $uri as base64"))

      override def writeStream(target: URI, bytes: fs2.Stream[F, Byte]): F[Unit] =
        Tracer[F]
          .span("files", "write-stream")()
          .logged: logger =>
            delegate.writeStream(target, bytes).flatTap(_ => logger.trace(s"Wrote bytes to $target"))

  def observed[F[_]: MonadThrow: Tracer: StructuredLogger](delegate: Files[F], meters: Meters[F]): Files[F] =
    traced(metered(delegate, meters))

  final case class Meters[F[_]](operationCounter: Counter[F, Long], errorCounter: Counter[F, Long])

  object Meters:
    def create[F[_]: Meter: Applicative]: F[Meters[F]] =
      (
        Meter[F].counter[Long](Observability.Metrics.name("files_operation_count")).create,
        Meter[F].counter[Long](Observability.Metrics.name("files_error_count")).create
      ).mapN(Meters(_, _))

  def mapK[F[_], G[_]](files: Files[F])(f: F ~> G, g: G ~> F): Files[G] = new Files[G]:
    override def resolve(relative: String): G[URI]                             = f(files.resolve(relative))
    override def readFileAsBase64(uri: URI): G[String]                         = f(files.readFileAsBase64(uri))
    override def writeStream(target: URI, bytes: fs2.Stream[G, Byte]): G[Unit] =
      f(files.writeStream(target, bytes.translate(g)))

package io.github.serhiip.constellations

import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.spi.FileSystemProvider
import java.nio.file.{FileSystemNotFoundException}
import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

import cats.~>
import cats.effect.Async
import cats.syntax.all.*
import fs2.io.file.{Files as Fs2Files, Path}

trait Files[F[_]]:
  def readFileAsBase64(uri: URI): F[String]
  def writeStream(target: URI, bytes: fs2.Stream[F, Byte]): F[Unit]
  def resolve(relative: String): URI

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
      override def resolve(relative: String): URI = baseUri.resolve(relative)

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

  def mapK[F[_], G[_]](files: Files[F])(f: F ~> G): Files[G] = new Files[G]:
    override def resolve(relative: String): URI        = files.resolve(relative)
    override def readFileAsBase64(uri: URI): G[String] = f(files.readFileAsBase64(uri))
    override def writeStream(target: URI, bytes: fs2.Stream[G, Byte]): G[Unit] =
      // writeStream cannot be mapped without an inverse FunctionK (G ~> F)
      ???

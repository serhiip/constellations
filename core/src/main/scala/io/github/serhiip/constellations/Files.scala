package io.github.serhiip.constellations

import java.net.URI
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

object Files:
  def apply[F[_]: Async: Fs2Files](baseUri: URI): F[Files[F]] =
    for
      providerFound <- Async[F]
                         .delay(
                           ServiceLoader
                             .load(classOf[FileSystemProvider], Thread.currentThread().getContextClassLoader)
                             .asScala
                             .find(_.getScheme == baseUri.getScheme)
                         )
      provider      <- providerFound.map(_.pure).getOrElse(FileSystemNotFoundException(s"Provider '${baseUri.getScheme}' not found").raiseError)
    yield new:
      override def readFileAsBase64(uri: URI): F[String] =
        for
          nioPath <- Async[F].delay(provider.getPath(uri))
          content <- Fs2Files[F]
                       .readAll(Path.fromNioPath(nioPath))
                       .through(fs2.text.base64.encode)
                       .compile
                       .string
        yield content

  def mapK[F[_], G[_]](files: Files[F])(f: F ~> G): Files[G] = new Files[G]:
    override def readFileAsBase64(uri: URI): G[String] = f(files.readFileAsBase64(uri))

package io.github.serhiip.constellations

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*

import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.trace.Tracer

import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Observability.*

trait Handling[F[_], T]:
  def getTextFromResponse(response: T): F[Option[String]]
  def getFunctinoCalls(response: T): F[List[FunctionCall]]
  def finishReason(response: T): F[FinishReason]
  def structuredOutput(response: T): F[Struct]
  def getImages(response: T): F[List[GeneratedImage[F]]]

object Handling:
  def apply[F[_]: Tracer: StructuredLogger: Monad, T](delegate: Handling[F, T]): Handling[F, T] = observed(delegate)

  private def observed[F[_]: Monad: Tracer: StructuredLogger, T](delegate: Handling[F, T]): Handling[F, T] =
    new Handling[F, T]:
      override def structuredOutput(response: T): F[Struct] =
        Tracer[F]
          .span("handling", "structured-output")
          .logged: logger =>
            for
              _      <- logger.trace("Extracting structured output from response")
              result <- delegate.structuredOutput(response)
              _      <- logger.trace(s"Structured output: $result")
            yield result

      override def getTextFromResponse(response: T): F[Option[String]] =
        Tracer[F]
          .span("handling", "get-text-from-response")
          .logged: logger =>
            for
              _      <- logger.trace("Extracting text from response")
              result <- delegate.getTextFromResponse(response)
              _      <- logger.trace(s"Text length: ${result.map(_.length).getOrElse(0)}")
            yield result

      override def getFunctinoCalls(response: T): F[List[FunctionCall]] =
        Tracer[F]
          .span("handling", "get-function-calls")
          .logged: logger =>
            for
              _      <- logger.trace("Extracting function calls from response")
              result <- delegate.getFunctinoCalls(response)
              _      <- logger.trace(s"Extracted ${result.size} function calls")
            yield result

      override def finishReason(response: T): F[FinishReason] =
        Tracer[F]
          .span("handling", "finish-reason")
          .logged: logger =>
            for
              _      <- logger.trace("Determining finish reason")
              result <- delegate.finishReason(response)
              _      <- logger.trace(s"Finish reason: $result")
            yield result

      override def getImages(response: T): F[List[GeneratedImage[F]]] =
        Tracer[F]
          .span("handling", "get-images")
          .logged: logger =>
            for
              _      <- logger.trace("Extracting images from response")
              result <- delegate.getImages(response)
              _      <- logger.trace(s"Extracted ${result.size} images")
            yield result

package io.github.serhiip.constellations.invoker

import cats.{MonadThrow, Monad}
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.trace.Tracer

import io.github.serhiip.constellations.{Handling, Invoker}
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.dispatcher.Decoder
import io.github.serhiip.constellations.schema.ToSchema
import cats.data.NonEmptyChain as NEC
import cats.syntax.all.*
import io.github.serhiip.constellations.common.Observability.*

trait StructuredInvoker[F[_], R, T: ToSchema] extends Invoker[F, T]:
  protected val schema: Schema = ToSchema[T].schema

object StructuredInvoker:
  enum Error(message: String) extends RuntimeException(message):
    case StructuredDecodingFailed(errors: NEC[Decoder.Error]) extends Error(errors.toNonEmptyList.toList.map(_.show).mkString("; "))

  def apply[F[_]: MonadThrow, R, T: ToSchema: Decoder.FromStruct](
      delegate: Invoker[F, R],
      handling: Handling[F, R]
  ): StructuredInvoker[F, R, T] = new:

    private val responseAsStruct = delegate.generate andThenF handling.structuredOutput

    override def generate(history: NEC[Message]): F[T] =
      for
        struct    <- responseAsStruct(history)
        converted <- Decoder[Struct, T].decode(struct).leftMap(StructuredInvoker.Error.StructuredDecodingFailed.apply).liftTo[F]
      yield converted

  def observed[F[_]: Monad: Tracer: StructuredLogger, R, T: ToSchema](delegate: StructuredInvoker[F, R, T]): StructuredInvoker[F, R, T] =
    new:
      override def generate(history: NEC[Message]): F[T] =
        Tracer[F]
          .span("structured-invoker", "generate")
          .logged: logger =>
            for
              _      <- logger.debug(s"Using $schema")
              result <- delegate.generate(history)
              _      <- logger.trace(s"Generated: $result")
            yield result

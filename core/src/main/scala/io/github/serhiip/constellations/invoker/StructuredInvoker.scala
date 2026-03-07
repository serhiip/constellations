package io.github.serhiip.constellations.invoker

import cats.Monad
import cats.data.NonEmptyChain as NEC
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.option.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.trace.Tracer

import io.github.serhiip.constellations.Invoker
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Observability.*
import io.github.serhiip.constellations.schema.ToSchema
import org.typelevel.otel4s.Attribute

abstract class StructuredInvoker[F[_], T: ToSchema](val underlying: Invoker[F, T]):
  private[StructuredInvoker] val schema       = ToSchema[T].schema
  def structured(history: NEC[Message]): F[T] = underlying.generate(history, schema.some)

object StructuredInvoker:
  def apply[F[_]: Tracer: StructuredLogger: Monad, T: ToSchema](delegate: StructuredInvoker[F, T]): StructuredInvoker[F, T] =
    observed(delegate)

  private def observed[F[_]: Monad: Tracer: StructuredLogger, T: ToSchema](delegate: StructuredInvoker[F, T]): StructuredInvoker[F, T] =
    new StructuredInvoker[F, T](delegate.underlying):
      override def structured(history: NEC[Message]): F[T] =
        Tracer[F]
          .span("invoker", "structured")
          .logged: logger =>
            for
              _      <- logger.trace(s"Generating structured with ${history.length} messages in history using ${schema}")
              span   <- Tracer[F].currentSpanOrNoop
              _      <- span.addAttribute(Attribute("response_schema", schema.toString))
              result <- delegate.structured(history)
              _      <- logger.trace(s"Structured result: $result")
            yield result

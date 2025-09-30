package io.github.serhiip.constellations

import scala.concurrent.duration.*

import cats.Monad
import cats.data.NonEmptyChain as NEC
import cats.effect.Temporal
import cats.syntax.flatMap.*
import cats.syntax.functor.*

import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.trace.Tracer

import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Observability.*
import retry.*

trait Invoker[F[_], T]:
  def generate(history: NEC[Message], responseSchema: Option[Schema] = None): F[T]

object Invoker:
  def apply[F[_]: Tracer: StructuredLogger: Monad, T](delegate: Invoker[F, T]): Invoker[F, T] = observed(delegate)

  private def observed[F[_]: Monad: Tracer: StructuredLogger, T](delegate: Invoker[F, T]): Invoker[F, T] =
    new Invoker[F, T]:
      override def generate(history: NEC[Message], responseSchema: Option[Schema]): F[T] =
        Tracer[F]
          .span("invoker", "generate")
          .logged: logger =>
            for
              _      <- logger.trace(s"Generating with ${history.length} messages in history")
              result <- delegate.generate(history, responseSchema)
              _      <- logger.trace(s"Generate result: $result")
            yield result

  def retrying[F[_]: StructuredLogger: Temporal, T](
      maxDelay: FiniteDuration,
      initialDelay: FiniteDuration,
      delegate: Invoker[F, T]
  ): Invoker[F, T] = new:

    def logError(err: Throwable, details: RetryDetails): F[Unit] =
      val logger = StructuredLogger[F]
      details.nextStepIfUnsuccessful match
        case RetryDetails.NextStep.DelayAndRetry(nextDelay: FiniteDuration) =>
          logger.trace(s"Invoker retry after ${details.retriesSoFar} attemps")
        case RetryDetails.NextStep.GiveUp                                   => logger.error(err)(s"Giving up after ${details.retriesSoFar} retries")

    override def generate(prompt: NEC[Message], responseSchema: Option[Schema]): F[T] =
      retryingOnErrors(delegate.generate(prompt, responseSchema))(
        RetryPolicies.limitRetriesByCumulativeDelay(maxDelay, RetryPolicies.exponentialBackoff(initialDelay)),
        ResultHandler.retryOnAllErrors(logError)
      )

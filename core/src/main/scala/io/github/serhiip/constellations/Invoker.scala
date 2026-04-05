package io.github.serhiip.constellations

import scala.concurrent.duration.*

import cats.Monad
import cats.data.NonEmptyChain as NEC
import cats.effect.Temporal
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import org.typelevel.log4cats.{StructuredLogger, LoggerFactory}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.Tracer

import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Observability.*
import retry.*

trait Invoker[F[_], T]:
  def generate(history: NEC[Message]): F[T]

object Invoker:
  def observed[F[_]: Monad: Tracer: LoggerFactory, T](delegate: Invoker[F, T]): F[Invoker[F, T]] =
    LoggerFactory[F].create.map: logger =>
      given StructuredLogger[F] = logger
      new Observed[F, T](delegate)

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

    override def generate(prompt: NEC[Message]): F[T] =
      retryingOnErrors(delegate.generate(prompt))(
        RetryPolicies.limitRetriesByCumulativeDelay(maxDelay, RetryPolicies.exponentialBackoff(initialDelay)),
        ResultHandler.retryOnAllErrors(logError)
      )

private[constellations] final class Observed[F[_]: Monad: Tracer: StructuredLogger, T](delegate: Invoker[F, T]) extends Invoker[F, T]:
  override def generate(history: NEC[Message]): F[T] =
    Tracer[F]
      .span("invoker", "generate")
      .logged: logger =>
        val invokerName = Option(delegate.getClass.getCanonicalName()).getOrElse(delegate.getClass.getName)
        for
          _      <- logger.trace(s"Generating structured with ${history.length} messages in history using $invokerName")
          span   <- Tracer[F].currentSpanOrNoop
          _      <- span.addAttribute(Attribute("invoker_name", invokerName))
          result <- delegate.generate(history)
          _      <- logger.trace(s"Invocation result: $result")
        yield result

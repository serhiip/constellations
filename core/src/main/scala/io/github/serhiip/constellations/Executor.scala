package io.github.serhiip.constellations

import java.time.OffsetDateTime
import java.util.UUID

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*

import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.trace.Tracer

import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Observability.*
import java.net.URI

trait Executor[F[_], E, T]:
  def execute(dispatcher: Dispatcher[F], memory: Memory[F, ?], query: String, assets: List[URI] = List.empty): F[Either[E, T]]
  def resume(dispatcher: Dispatcher[F], memory: Memory[F, ?]): F[Either[E, T]]

object Executor:
  enum Step:
    case UserQuery(text: String, at: OffsetDateTime, assets: List[URI] = List.empty)
    case UserReply(text: String, at: OffsetDateTime, parent: UUID)
    case ModelResponse(text: Option[String], at: OffsetDateTime, assets: List[URI] = List.empty)
    case Call(call: FunctionCall, at: OffsetDateTime)
    case Response(result: FunctionResponse, at: OffsetDateTime)

  def apply[F[_]: Tracer: StructuredLogger: Monad, E, T](delegate: Executor[F, E, T]): Executor[F, E, T] = observed(
    delegate
  )

  private def observed[F[_]: Monad: Tracer: StructuredLogger, E, T](delegate: Executor[F, E, T]): Executor[F, E, T] =
    new Executor[F, E, T]:
      def execute(dispatcher: Dispatcher[F], memory: Memory[F, ?], query: String, assets: List[URI]): F[Either[E, T]] =
        Tracer[F]
          .span("executor", "execute")
          .logged: logger =>
            for
              _      <- logger.trace(s"Executing query $query with ${assets.size} assets")
              result <- delegate.execute(dispatcher, memory, query, assets)
              _      <- logger.trace(s"Result is $result")
            yield result

      def resume(dispatcher: Dispatcher[F], memory: Memory[F, ?]): F[Either[E, T]] =
        Tracer[F]
          .span("executor", "resume")
          .logged: logger =>
            for
              _      <- logger.trace(s"Continuing the query")
              result <- delegate.resume(dispatcher, memory)
              _      <- logger.trace(s"Result of resuming is $result")
            yield result

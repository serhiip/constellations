package io.github.serhiip.constellations

import cats.data.Chain
import cats.effect.{Async, Ref}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.{Monad, ~>}

import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.trace.Tracer

import io.github.serhiip.constellations.common.IDGen
import io.github.serhiip.constellations.common.Observability.*

trait Memory[F[_], Id]:
  def record(message: Executor.Step): F[Unit]
  def retrieve: F[Chain[Executor.Step]]
  def last: F[Option[(Id, Executor.Step)]]

object Memory:

  def apply[F[_]: Tracer: StructuredLogger: Monad, I](delegate: Memory[F, I]): Memory[F, I] = observed(delegate)

  private def observed[F[_]: Monad: Tracer: StructuredLogger, I](delegate: Memory[F, I]): Memory[F, I] = new Memory[F, I]:
    override def record(step: Executor.Step): F[Unit] =
      Tracer[F]
        .span("memory", "record")
        .logged: logger =>
          for
            _ <- logger.trace(s"Recording step: $step")
            r <- delegate.record(step)
            _ <- logger.trace("Recorded step")
          yield r

    override def retrieve: F[Chain[Executor.Step]] =
      Tracer[F]
        .span("memory", "retrieve")
        .logged: logger =>
          for
            _      <- logger.trace("Retrieving steps")
            result <- delegate.retrieve
            _      <- logger.trace(s"Retrieved ${result.size} steps")
          yield result

    override def last: F[Option[(I, Executor.Step)]] =
      Tracer[F]
        .span("memory", "last")
        .logged: logger =>
          for
            _      <- logger.trace("Retrieving last step")
            result <- delegate.last
            _      <- logger.trace(s"Last present: ${result.isDefined}")
          yield result

  def inMemory[F[_]: Async, I](using idGen: IDGen[F, I]): F[Memory[F, I]] =
    for storage <- Ref.of[F, Chain[(I, Executor.Step)]](Chain.empty)
    yield new:
      override def record(step: Executor.Step): F[Unit] =
        for
          id <- idGen.random
          _  <- storage.update(_ :+ (id -> step))
        yield ()
      override def retrieve: F[Chain[Executor.Step]]    = storage.get.map(_.map(_._2))
      override def last: F[Option[(I, Executor.Step)]]  = storage.get.map(_.lastOption)

  def mapK[F[_], G[_], Id](f: F ~> G)(memory: Memory[F, Id]): Memory[G, Id] =
    new Memory[G, Id]:
      override def record(step: Executor.Step): G[Unit] = f(memory.record(step))
      override def retrieve: G[Chain[Executor.Step]]    = f(memory.retrieve)
      override def last: G[Option[(Id, Executor.Step)]] = f(memory.last)

package io.github.serhiip.constellations.gcprag

import cats.effect.Async
import cats.syntax.all.*

import com.google.api.core.{ApiFuture, ApiFutureCallback, ApiFutures}
import com.google.common.util.concurrent.MoreExecutors

extension [A](future: ApiFuture[A])
  def liftTo[F[_]](using Async[F]): F[A] =
    Async[F].async: cb =>
      Async[F].delay:
        ApiFutures.addCallback(
          future,
          new ApiFutureCallback[A]:
            def onSuccess(result: A): Unit    = cb(Right(result))
            def onFailure(t: Throwable): Unit = cb(Left(t))
          ,
          MoreExecutors.directExecutor()
        )
        Some(Async[F].delay(future.cancel(false)).void)

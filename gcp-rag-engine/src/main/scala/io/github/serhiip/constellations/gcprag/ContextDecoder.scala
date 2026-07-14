package io.github.serhiip.constellations.gcprag

trait ContextDecoder[F[_], T]:
  def decode(context: RetrievedContext): F[T]

object ContextDecoder:

  def apply[F[_], T](f: RetrievedContext => F[T]): ContextDecoder[F, T] = new:
    def decode(context: RetrievedContext): F[T] = f(context)

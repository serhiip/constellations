package io.github.serhiip.constellations.common

import java.util.UUID

import cats.effect.std.UUIDGen

trait IDGen[F[_], T]:
  def random: F[T]

object IDGen:
  given IdGen[F[_]](using UUIDGen[F]): IDGen[F, UUID] = new:
    override def random: F[UUID] = UUIDGen[F].randomUUID

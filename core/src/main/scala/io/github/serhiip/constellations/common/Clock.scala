package io.github.serhiip.constellations.common

import java.time.{Instant, OffsetDateTime, ZoneId, ZoneOffset}

import cats.Functor
import cats.effect.Clock
import cats.syntax.functor.*

extension (millis: Long) def toOffsetDateTimeUtc: OffsetDateTime                     = OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.of("UTC"))
extension [F[_]: Functor](it: F[Instant]) def toOffsetDateTimeUtc: F[OffsetDateTime] = it.map(i => OffsetDateTime.ofInstant(i, ZoneOffset.UTC))
extension [F[_]: Functor](it: Clock[F]) def offsetDateTimeUtc: F[OffsetDateTime]     = it.realTime.map(_.toMillis.toOffsetDateTimeUtc)

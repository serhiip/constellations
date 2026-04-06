package io.github.serhiip.constellations.similarity

import cats.data.NonEmptyList as NEL

import io.github.serhiip.constellations.Similarity.Error

object CosineMath:
  def cosineDistance(a: NEL[Float], b: List[Float]): Either[Error, Double] =
    Option
      .when(a.size == b.size)({
        val ad  = a.toList.map(_.toDouble)
        val bd  = b.map(_.toDouble)
        val dot = ad.lazyZip(bd).map(_ * _).sum
        val na  = math.sqrt(ad.map(x => x * x).sum)
        val nb  = math.sqrt(bd.map(x => x * x).sum)
        if na == 0.0 || nb == 0.0 then 1.0
        else
          val sim = dot / (na * nb)
          1.0 - sim
      })
      .toRight(Error.DimensionMismatch(a.size, b.size))

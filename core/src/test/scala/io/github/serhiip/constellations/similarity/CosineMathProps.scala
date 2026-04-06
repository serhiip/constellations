package io.github.serhiip.constellations.similarity

import cats.data.NonEmptyList as NEL
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}

import io.github.serhiip.constellations.Similarity.Error
import io.github.serhiip.constellations.similarity.CosineMath

class CosineMathProps extends ScalaCheckSuite:

  private val nonZeroDouble = Gen.oneOf(
    Gen.choose(-10.0, -0.01),
    Gen.choose(0.01, 10.0)
  )

  private val positiveDouble = Gen.choose(0.01, 10.0)

  extension (a: Double)
    def closeTo(b: Double, tolerance: Double = 1e-15): Boolean =
      math.abs(a - b) <= tolerance

    def between(min: Double, max: Double, tolerance: Double = 1e-15): Boolean =
      (a >= min || a.closeTo(min, tolerance)) && (a <= max || a.closeTo(max, tolerance))

  property("cosineDistance of identical vectors is 0") {
    val gen = for
      dim   <- Gen.choose(1, 20)
      value <- nonZeroDouble
    yield NEL.fromListUnsafe(List.fill(dim)(value.toFloat))

    Prop.forAll(gen) { vec =>
      CosineMath.cosineDistance(vec, vec.toList) match
        case Right(d) => assert(d.closeTo(0.0), s"Distance between identical vectors should be 0.0, but got $d")
        case Left(e)  => fail(s"Expected successful distance calculation, but got error: $e")
    }
  }

  property("cosineDistance is symmetric") {
    val gen = for
      dim <- Gen.choose(1, 10)
      a   <- nonZeroDouble
      b   <- nonZeroDouble
    yield (
      NEL.fromListUnsafe(List.fill(dim)(a.toFloat)),
      NEL.fromListUnsafe(List.fill(dim)(b.toFloat))
    )

    Prop.forAll(gen) { case (vecA, vecB) =>
      val distAB = CosineMath.cosineDistance(vecA, vecB.toList)
      val distBA = CosineMath.cosineDistance(vecB, vecA.toList)
      (distAB, distBA) match
        case (Right(d1), Right(d2)) => assert(d1.closeTo(d2), s"Distance should be symmetric: dist(A,B)=$d1 != dist(B,A)=$d2")
        case _                      => fail("Expected successful distance calculation for both directions")
    }
  }

  property("cosineDistance returns DimensionMismatch for different dimensions") {
    val gen = for
      dimA  <- Gen.choose(1, 10)
      dimB  <- Gen.choose(11, 20)
      value <- nonZeroDouble
    yield (
      NEL.fromListUnsafe(List.fill(dimA)(value.toFloat)),
      List.fill(dimB)(value.toFloat)
    )

    Prop.forAll(gen) { case (vecA, vecB) =>
      CosineMath.cosineDistance(vecA, vecB) match
        case Left(Error.DimensionMismatch(e, a)) =>
          assert(
            e == vecA.size && a == vecB.size,
            s"Expected DimensionMismatch(${vecA.size}, ${vecB.size}), but got DimensionMismatch($e, $a)"
          )
        case other                               => fail(s"Expected DimensionMismatch error, but got $other")
    }
  }

  test("cosineDistance of orthogonal vectors is 1") {
    val vecA = NEL.of(1f, 0f)
    val vecB = List(0f, 1f)
    CosineMath.cosineDistance(vecA, vecB) match
      case Right(d) => assert(d.closeTo(1.0), s"Distance between orthogonal vectors should be 1.0, but got $d")
      case _        => fail("Expected successful distance calculation")
  }

  test("cosineDistance returns 1 when query vector has zero norm") {
    val vecA = NEL.of(0f, 0f)
    val vecB = List(1f, 0f)
    CosineMath.cosineDistance(vecA, vecB) match
      case Right(d) => assert(d.closeTo(1.0), s"Distance with zero-norm query vector should fallback to 1.0, but got $d")
      case _        => fail("Expected successful distance calculation")
  }

  test("cosineDistance returns 1 when stored vector has zero norm") {
    val vecA = NEL.of(1f, 0f)
    val vecB = List(0f, 0f)
    CosineMath.cosineDistance(vecA, vecB) match
      case Right(d) => assert(d.closeTo(1.0), s"Distance with zero-norm stored vector should fallback to 1.0, but got $d")
      case _        => fail("Expected successful distance calculation")
  }

  property("cosineDistance is always between 0 and 2") {
    val gen = for
      dim <- Gen.choose(1, 20)
      a   <- nonZeroDouble
      b   <- nonZeroDouble
    yield (
      NEL.fromListUnsafe(List.fill(dim)(a.toFloat)),
      NEL.fromListUnsafe(List.fill(dim)(b.toFloat))
    )

    Prop.forAll(gen) { case (vecA, vecB) =>
      CosineMath.cosineDistance(vecA, vecB.toList) match
        case Right(d) => assert(d.between(0.0, 2.0), s"Cosine distance $d is outside the valid range [0.0, 2.0]")
        case _        => fail("Expected successful distance calculation")
    }
  }

  property("cosineDistance of opposite vectors is 2 (antiparallel)") {
    val gen = for
      dim   <- Gen.choose(1, 10)
      value <- nonZeroDouble
    yield (
      NEL.fromListUnsafe(List.fill(dim)(value.toFloat)),
      List.fill(dim)(-value.toFloat)
    )

    Prop.forAll(gen) { case (vecA, vecB) =>
      CosineMath.cosineDistance(vecA, vecB) match
        case Right(d) => assert(d.closeTo(2.0), s"Distance between antiparallel vectors should be exactly 2.0, but got $d")
        case _        => fail("Expected successful distance calculation")
    }
  }

  property("cosineDistance scales invariantly (unit vectors vs scaled)") {
    val gen = for
      dim   <- Gen.choose(1, 10)
      base  <- nonZeroDouble
      scale <- positiveDouble
    yield (
      NEL.fromListUnsafe(List.fill(dim)(base.toFloat)),
      NEL.fromListUnsafe(List.fill(dim)((base * 2).toFloat)),
      scale.toFloat
    )

    Prop.forAll(gen) { case (vecA, vecB, scale) =>
      val vecBScaled = vecB.map(_ * scale)
      val distUnit   = CosineMath.cosineDistance(vecA, vecB.toList)
      val distScaled = CosineMath.cosineDistance(vecA, vecBScaled.toList)
      (distUnit, distScaled) match
        case (Right(d1), Right(d2)) =>
          assert(d1.closeTo(d2), s"Distance should be scale-invariant: dist(A,B)=$d1 != dist(A, B*scale)=$d2 (scale=$scale)")
        case _                      =>
          fail(s"Expected successful distance calculation for both unit and scaled vectors, got distUnit=$distUnit, distScaled=$distScaled")
    }
  }

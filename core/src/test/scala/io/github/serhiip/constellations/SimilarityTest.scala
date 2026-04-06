package io.github.serhiip.constellations

import cats.data.NonEmptyList as NEL
import cats.effect.IO
import munit.CatsEffectSuite

import io.github.serhiip.constellations.{Embeddings, Similarity}
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.trace.Tracer.Implicits.noop

class SimilarityTest extends CatsEffectSuite:

  private val emb2dNel     = NEL.of(1f, 0f)
  private val emb2dOrthNel = NEL.of(0f, 1f)
  private val emb2d        = emb2dNel.toList
  private val emb2dOrth    = emb2dOrthNel.toList

  private def stubEmbeddings: Embeddings[IO] = new Embeddings[IO]:
    def embed(text: String): IO[NEL[Float]] = IO.pure(emb2dNel)
    def embedBatch(texts: NEL[String]): IO[NEL[List[Float]]] =
      IO.pure(NEL.fromListUnsafe(texts.toList.map {
        case "text_a" => emb2d
        case "text_b" => emb2dOrth
        case "text_c" => List(1f, 1f)
        case "far"    => emb2dOrth
        case "close"  => emb2d
        case "mid"    => List(1f, 1f)
        case "item_a" => emb2d
        case "item_b" => emb2dOrth
        case "query"  => emb2d
        case _        => emb2d
      }))

  test("cosineInMemory returns closest by cosine distance") {
    val items      = NEL.of(
      ("a", "text_a"),
      ("b", "text_b"),
      ("c", "text_c")
    )
    val embeddings = stubEmbeddings
    for
      sim     <- Similarity.cosineInMemory[IO, String](items, embeddings)
      closest <- sim.findClosest(emb2dNel, k = 1)
    yield assertEquals(closest, NEL.one("a"))
  }

  test("cosineInMemory ranks by k") {
    val items      = NEL.of(
      ("far", "far"),
      ("close", "close"),
      ("mid", "mid")
    )
    val embeddings = stubEmbeddings
    for
      sim  <- Similarity.cosineInMemory[IO, String](items, embeddings)
      top2 <- sim.findClosest(emb2dNel, k = 2)
    yield assertEquals(top2, NEL.of("close", "mid"))
  }

  test("cosineInMemory returns all items when k >= items count") {
    val items      = NEL.of(("a", "text_a"), ("b", "text_b"))
    val embeddings = stubEmbeddings
    for
      sim <- Similarity.cosineInMemory[IO, String](items, embeddings)
      r   <- sim.findClosest(emb2dNel, k = 10)
    yield assertEquals(r, NEL.of("a", "b"))
  }

  test("cosineInMemory fails on query dimension mismatch") {
    val items      = NEL.one(("a", "text_a"))
    val embeddings = stubEmbeddings
    for
      sim <- Similarity.cosineInMemory[IO, String](items, embeddings)
      r   <- sim.findClosest(NEL.of(1f, 0f, 0f), k = 1).attempt
    yield assert(r.isLeft)
  }

  test("cosineInMemory returns at least one result when k <= 0") {
    val items      = NEL.of(("a", "text_a"), ("b", "text_b"))
    val embeddings = stubEmbeddings
    for
      sim <- Similarity.cosineInMemory[IO, String](items, embeddings)
      r   <- sim.findClosest(emb2dNel, k = 0)
    yield assertEquals(r.size, 2)
  }

  test("cosineInMemory finds closest by text query") {
    val items      = NEL.of(
      ("item_a", "item_a"),
      ("item_b", "item_b")
    )
    val embeddings = stubEmbeddings
    for
      sim     <- Similarity.cosineInMemory[IO, String](items, embeddings)
      closest <- sim.findClosest("query", k = 1)
    yield assertEquals(closest, NEL.one("item_a"))
  }

  test("observed delegates to inner similarity (embedding)") {
    given StructuredLogger[IO] = NoOpLogger[IO]
    val inner                  = new Similarity[IO, String]:
      def findClosest(queryEmbedding: NEL[Float], k: Int): IO[NEL[String]] =
        IO.pure(NEL.one("x"))
      def findClosest(query: String, k: Int): IO[NEL[String]]              =
        IO.pure(NEL.one("y"))

    val wrapped = Similarity.observed(inner)
    for r <- wrapped.findClosest(emb2dNel, k = 1)
    yield assertEquals(r, NEL.one("x"))
  }

  test("observed delegates to inner similarity (text)") {
    given StructuredLogger[IO] = NoOpLogger[IO]
    val inner                  = new Similarity[IO, String]:
      def findClosest(queryEmbedding: NEL[Float], k: Int): IO[NEL[String]] =
        IO.pure(NEL.one("x"))
      def findClosest(query: String, k: Int): IO[NEL[String]]              =
        IO.pure(NEL.one("y"))

    val wrapped = Similarity.observed(inner)
    for r <- wrapped.findClosest("test", k = 1)
    yield assertEquals(r, NEL.one("y"))
  }

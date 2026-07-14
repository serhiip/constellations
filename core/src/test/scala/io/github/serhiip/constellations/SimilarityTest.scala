package io.github.serhiip.constellations

import cats.Id
import cats.data.{EitherT, NonEmptyList as NEL}
import cats.syntax.all.*
import munit.FunSuite

class SimilarityTest extends FunSuite:

  private type F[A] = EitherT[Id, Throwable, A]

  private val emb2dNel  = NEL.of(1f, 0f)
  private val emb2d     = emb2dNel.toList
  private val emb2dOrth = NEL.of(0f, 1f).toList

  private def stubEmbeddings: Embeddings[F] = new Embeddings[F]:
    def embed(text: String): F[NEL[Float]] = emb2dNel.pure[F]
    def embedBatch(texts: NEL[String]): F[NEL[List[Float]]] =
      NEL
        .fromListUnsafe(texts.toList.map {
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
        })
        .pure[F]

  private def assertRight[A](fa: F[A], expected: A): Unit =
    assertEquals(fa.value, Right(expected): Either[Throwable, A])

  test("cosineInMemory returns closest by cosine distance") {
    val items = NEL.of(("a", "text_a"), ("b", "text_b"), ("c", "text_c"))
    val result = for
      sim     <- Similarity.cosineInMemory[F, String](items, stubEmbeddings)
      closest <- sim.findClosest(emb2dNel, k = 1)
    yield closest
    assertRight(result, NEL.one("a"))
  }

  test("cosineInMemory ranks by k") {
    val items = NEL.of(("far", "far"), ("close", "close"), ("mid", "mid"))
    val result = for
      sim  <- Similarity.cosineInMemory[F, String](items, stubEmbeddings)
      top2 <- sim.findClosest(emb2dNel, k = 2)
    yield top2
    assertRight(result, NEL.of("close", "mid"))
  }

  test("cosineInMemory returns all items when k >= items count") {
    val items = NEL.of(("a", "text_a"), ("b", "text_b"))
    val result = for
      sim <- Similarity.cosineInMemory[F, String](items, stubEmbeddings)
      r   <- sim.findClosest(emb2dNel, k = 10)
    yield r
    assertRight(result, NEL.of("a", "b"))
  }

  test("cosineInMemory fails on query dimension mismatch") {
    val items = NEL.one(("a", "text_a"))
    val result = for
      sim <- Similarity.cosineInMemory[F, String](items, stubEmbeddings)
      r   <- sim.findClosest(NEL.of(1f, 0f, 0f), k = 1)
    yield r
    assert(result.value.isLeft)
  }

  test("cosineInMemory returns at least one result when k <= 0") {
    val items = NEL.of(("a", "text_a"), ("b", "text_b"))
    val result = for
      sim <- Similarity.cosineInMemory[F, String](items, stubEmbeddings)
      r   <- sim.findClosest(emb2dNel, k = 0)
    yield r.size
    assertRight(result, 2)
  }

  test("textViaEmbeddings finds closest by text query") {
    val items = NEL.of(("item_a", "item_a"), ("item_b", "item_b"))
    val result = for
      embSim  <- Similarity.cosineInMemory[F, String](items, stubEmbeddings)
      textSim  = Similarity.textViaEmbeddings(stubEmbeddings, embSim)
      closest <- textSim.findClosest("query", k = 1)
    yield closest
    assertRight(result, NEL.one("item_a"))
  }

  test("cosineInMemory fails when batch embeddings have mismatched dimensions") {
    val mismatched = new Embeddings[F]:
      def embed(text: String): F[NEL[Float]]                      = emb2dNel.pure[F]
      def embedBatch(texts: NEL[String]): F[NEL[List[Float]]] =
        NEL.of(emb2d, List(1f, 0f, 0f)).pure[F]

    val items  = NEL.of(("a", "text_a"), ("b", "text_b"))
    val result = Similarity.cosineInMemory[F, String](items, mismatched)
    assert(result.value.isLeft)
  }

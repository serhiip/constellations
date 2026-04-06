package io.github.serhiip.constellations

import cats.{Monad, MonadThrow, ~>}
import cats.data.NonEmptyList as NEL
import cats.syntax.all.*
import org.typelevel.log4cats.{LoggerFactory, StructuredLogger}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.Tracer

import io.github.serhiip.constellations.common.Observability.*
import io.github.serhiip.constellations.similarity.CosineMath

trait Similarity[F[_], T]:
  def findClosest(queryEmbedding: NEL[Float], k: Int): F[NEL[T]]
  def findClosest(query: String, k: Int): F[NEL[T]]

object Similarity:

  enum Error extends RuntimeException:
    case DimensionMismatch(expected: Int, actual: Int)

    override def getMessage(): String = this match
      case DimensionMismatch(expected, actual) => s"Embedding dimension mismatch: expected $expected, got $actual"

  def apply[F[_]: Monad: Tracer: LoggerFactory, T](delegate: Similarity[F, T]): F[Similarity[F, T]] =
    LoggerFactory[F].create.map: logger =>
      given StructuredLogger[F] = logger
      observed(delegate)

  def observed[F[_]: Monad: Tracer: StructuredLogger, T](delegate: Similarity[F, T]): Similarity[F, T] =
    new Observed(delegate)

  def cosineInMemory[F[_]: MonadThrow, T](items: NEL[(T, String)], embeddings: Embeddings[F]): F[Similarity[F, T]] =
    val texts = NEL.fromListUnsafe(items.toList.map(_._2))
    for
      embeddingsNel <- embeddings.embedBatch(texts)
      zippedNel      = NEL.fromListUnsafe(items.toList.zip(embeddingsNel.toList).map { case ((t, _), emb) => (t, emb) })
      _             <- validateStoredEmbeddings(zippedNel)
      dim            = zippedNel.head._2.size
    yield new CosineInMemory[F, T](zippedNel, dim, embeddings)

  def mapK[F[_], G[_], T](similarity: Similarity[F, T])(f: F ~> G): Similarity[G, T] = new:
    override def findClosest(queryEmbedding: NEL[Float], k: Int): G[NEL[T]] = f(similarity.findClosest(queryEmbedding, k))
    override def findClosest(query: String, k: Int): G[NEL[T]]              = f(similarity.findClosest(query, k))

  private def validateStoredEmbeddings[F[_]: MonadThrow, T](items: NEL[(T, List[Float])]): F[Unit] =
    val dim = items.head._2.size
    items.tail.traverse_ { case (_, v) => Error.DimensionMismatch(dim, v.size).raiseError.unlessA(v.size == dim) }

  private final class CosineInMemory[F[_]: MonadThrow, T](items: NEL[(T, List[Float])], dimension: Int, embeddings: Embeddings[F])
      extends Similarity[F, T]:

    override def findClosest(queryEmbedding: NEL[Float], k: Int): F[NEL[T]] =
      Error.DimensionMismatch(dimension, queryEmbedding.size).raiseError.unlessA(queryEmbedding.size == dimension) *>
        items
          .traverse { case (t, emb) => CosineMath.cosineDistance(queryEmbedding, emb).liftTo[F].tupleLeft(t) }
          .map: scored =>
            val sorted    = scored.sortBy(_._2).map(_._1)
            val takeCount = if k <= 0 then sorted.size else math.min(k, sorted.size)
            NEL(sorted.head, sorted.tail.take(takeCount - 1))

    override def findClosest(query: String, k: Int): F[NEL[T]] =
      for
        queryEmbedding <- embeddings.embed(query)
        result         <- findClosest(queryEmbedding, k)
      yield result

  private final class Observed[F[_]: Monad: Tracer: StructuredLogger, T](delegate: Similarity[F, T]) extends Similarity[F, T]:
    private val delegateName = Option(delegate.getClass.getCanonicalName()).getOrElse(delegate.getClass.getName)

    override def findClosest(queryEmbedding: NEL[Float], k: Int): F[NEL[T]] =
      Tracer[F]
        .span("similarity", "find-closest")
        .logged: logger =>
          for
            _      <- logger.trace(s"Finding up to $k closest matches (embedding dim=${queryEmbedding.size}) using $delegateName")
            span   <- Tracer[F].currentSpanOrNoop
            _      <- span.addAttributes(
                        Attribute("delegate_name", delegateName),
                        Attribute("k", k.toLong),
                        Attribute("query_dim", queryEmbedding.size.toLong)
                      )
            result <- delegate.findClosest(queryEmbedding, k)
            _      <- logger.trace(s"Found ${result.size} matches")
          yield result

    override def findClosest(query: String, k: Int): F[NEL[T]] =
      Tracer[F]
        .span("similarity", "find-closest-text")
        .logged: logger =>
          for
            _      <- logger.trace(s"Finding up to $k closest matches for text query (${query.length} chars) using $delegateName")
            span   <- Tracer[F].currentSpanOrNoop
            _      <- span.addAttributes(
                        Attribute("delegate_name", delegateName),
                        Attribute("k", k.toLong),
                        Attribute("query_length", query.length.toLong)
                      )
            result <- delegate.findClosest(query, k)
            _      <- logger.trace(s"Found ${result.size} matches")
          yield result

package io.github.serhiip.constellations

import cats.{Applicative, Monad, MonadThrow, ~>}
import cats.data.NonEmptyList as NEL
import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all.*
import org.typelevel.log4cats.{LoggerFactory, StructuredLogger}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{BucketBoundaries, Counter, Histogram, Meter}
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.SECONDS

import io.github.serhiip.constellations.common.Observability.*
import io.github.serhiip.constellations.similarity.CosineMath

trait Similarity[F[_], T, Q]:
  def findClosest(query: Q, k: Int): F[NEL[T]]

trait TextSimilarity[F[_], T]      extends Similarity[F, T, String]
trait EmbeddingSimilarity[F[_], T] extends Similarity[F, T, NEL[Float]]

object Similarity:

  enum Error extends RuntimeException:
    case DimensionMismatch(expected: Int, actual: Int)

    override def getMessage(): String = this match
      case DimensionMismatch(expected, actual) => s"Embedding dimension mismatch: expected $expected, got $actual"

  final case class Meters[F[_]](
      findClosestSuccess: Counter[F, Long],
      findClosestError: Counter[F, Long],
      findClosestLatency: Histogram[F, Double]
  )

  object Meters:
    def create[F[_]: Meter: Applicative]: F[Meters[F]] =
      val metric = Metrics.component("similarity")("find_closest")
      (
        Meter[F].counter[Long](metric("success_count")).create,
        Meter[F].counter[Long](metric("error_count")).create,
        Meter[F]
          .histogram[Double](metric("duration"))
          .withUnit("s")
          .withDescription("Latency of Similarity.findClosest")
          .withExplicitBucketBoundaries(BucketBoundaries(0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 15.0))
          .create
      ).mapN(Meters.apply)

  def metered[F[_]: MonadCancelThrow, T, Q](delegate: Similarity[F, T, Q], meters: Meters[F]): Similarity[F, T, Q] = new:
    override def findClosest(query: Q, k: Int): F[NEL[T]] =
      val kAttr = Attribute("k", k.toLong)
      meters.findClosestLatency
        .recordDuration(SECONDS, kAttr)
        .surround(
          delegate.findClosest(query, k).withOperationCounters(meters.findClosestSuccess, meters.findClosestError, kAttr)
        )

  def observed[F[_]: MonadCancelThrow: Tracer: LoggerFactory: Meter: Applicative, T, Q](
      delegate: Similarity[F, T, Q]
  ): F[Similarity[F, T, Q]] =
    (Meters.create[F], LoggerFactory[F].create).mapN: (meters, logger) =>
      given StructuredLogger[F] = logger
      traced(metered(delegate, meters))

  def traced[F[_]: Monad: Tracer: StructuredLogger, T, Q](delegate: Similarity[F, T, Q]): Similarity[F, T, Q] = Traced(delegate)

  def mapK[F[_], G[_], T, Q](similarity: Similarity[F, T, Q])(f: F ~> G): Similarity[G, T, Q] = new:
    override def findClosest(query: Q, k: Int): G[NEL[T]] = f(similarity.findClosest(query, k))

  def textViaEmbeddings[F[_]: Monad, T](embeddings: Embeddings[F], similarity: EmbeddingSimilarity[F, T]): TextSimilarity[F, T] = new:
    override def findClosest(query: String, k: Int): F[NEL[T]] =
      for
        queryEmbedding <- embeddings.embed(query)
        result         <- similarity.findClosest(queryEmbedding, k)
      yield result

  def cosineInMemory[F[_]: MonadThrow, T](items: NEL[(T, String)], embeddings: Embeddings[F]): F[EmbeddingSimilarity[F, T]] =
    val texts = NEL.fromListUnsafe(items.toList.map(_._2))
    for
      embeddingsNel <- embeddings.embedBatch(texts)
      zippedNel      = NEL.fromListUnsafe(items.toList.zip(embeddingsNel.toList).map { case ((t, _), emb) => (t, emb) })
      _             <- validateStoredEmbeddings(zippedNel)
      dim            = zippedNel.head._2.size
    yield CosineInMemory[F, T](zippedNel, dim)

  private def validateStoredEmbeddings[F[_]: MonadThrow, T](items: NEL[(T, List[Float])]): F[Unit] =
    val dim = items.head._2.size
    items.tail.traverse_ { case (_, v) => Error.DimensionMismatch(dim, v.size).raiseError.unlessA(v.size == dim) }

  private final class Traced[F[_]: Monad: Tracer, T, Q](delegate: Similarity[F, T, Q])(using StructuredLogger[F])
      extends Similarity[F, T, Q]:
    private val delegateName = Option(delegate.getClass.getCanonicalName()).getOrElse(delegate.getClass.getName)

    override def findClosest(query: Q, k: Int): F[NEL[T]] =
      Tracer[F]
        .span("similarity", "find-closest")(Attribute("similarity_type", delegateName), Attribute("k", k.toLong))
        .logged: spanLogger =>
          for
            _      <- spanLogger.trace(s"Finding up to $k closest matches using $delegateName")
            result <- delegate.findClosest(query, k)
            _      <- spanLogger.trace(s"Found ${result.size} matches")
          yield result

  private final class CosineInMemory[F[_]: MonadThrow, T](items: NEL[(T, List[Float])], dimension: Int) extends EmbeddingSimilarity[F, T]:

    override def findClosest(query: NEL[Float], k: Int): F[NEL[T]] =
      Error.DimensionMismatch(dimension, query.size).raiseError.unlessA(query.size == dimension) *>
        items
          .traverse { case (t, emb) => CosineMath.cosineDistance(query, emb).liftTo[F].tupleLeft(t) }
          .map: scored =>
            val sorted    = scored.sortBy(_._2).map(_._1)
            val takeCount = if k <= 0 then sorted.size else math.min(k, sorted.size)
            NEL(sorted.head, sorted.tail.take(takeCount - 1))

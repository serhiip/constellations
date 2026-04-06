package io.github.serhiip.constellations

import cats.{Monad, ~>}
import cats.syntax.all.*
import org.typelevel.log4cats.{LoggerFactory, StructuredLogger}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.Tracer
import cats.data.NonEmptyList as NEL

import io.github.serhiip.constellations.common.Observability.*

trait Embeddings[F[_]]:
  def embed(text: String): F[NEL[Float]]
  def embedBatch(texts: NEL[String]): F[NEL[List[Float]]]

object Embeddings:

  def apply[F[_]: Monad: Tracer: LoggerFactory](delegate: Embeddings[F]): F[Embeddings[F]] =
    LoggerFactory[F].create.map: logger =>
      given StructuredLogger[F] = logger
      observed(delegate)

  def observed[F[_]: Monad: Tracer: StructuredLogger](delegate: Embeddings[F]): Embeddings[F] =
    new Observed(delegate)

  def mapK[F[_], G[_]](embeddings: Embeddings[F])(f: F ~> G): Embeddings[G] = new:
    override def embed(text: String): G[NEL[Float]]                  = f(embeddings.embed(text))
    override def embedBatch(texts: NEL[String]): G[NEL[List[Float]]] = f(embeddings.embedBatch(texts))

  private final class Observed[F[_]: Monad: Tracer: StructuredLogger](delegate: Embeddings[F]) extends Embeddings[F]:
    private val delegateName = Option(delegate.getClass.getCanonicalName()).getOrElse(delegate.getClass.getName)

    override def embed(text: String): F[NEL[Float]] =
      Tracer[F]
        .span("embeddings", "embed")
        .logged: logger =>
          for
            _      <- logger.trace(s"Embedding text (${text.length} chars) using $delegateName")
            span   <- Tracer[F].currentSpanOrNoop
            _      <- span.addAttributes(
                        Attribute("delegate_name", delegateName),
                        Attribute("text_length", text.length.toLong)
                      )
            result <- delegate.embed(text)
            _      <- logger.trace(s"Embedding generated with ${result.size} dimensions")
          yield result

    override def embedBatch(texts: NEL[String]): F[NEL[List[Float]]] =
      Tracer[F]
        .span("embeddings", "embed-batch")
        .logged: logger =>
          for
            _      <- logger.trace(s"Embedding batch of ${texts.size} texts using $delegateName")
            span   <- Tracer[F].currentSpanOrNoop
            _      <- span.addAttributes(
                        Attribute("delegate_name", delegateName),
                        Attribute("batch_size", texts.size.toLong)
                      )
            result <- delegate.embedBatch(texts)
            _      <- logger.trace(s"Batch embeddings generated with ${result.size} vectors")
          yield result

package io.github.serhiip.constellations.gcprag

import cats.MonadThrow
import cats.data.NonEmptyList as NEL
import cats.syntax.all.*

import io.github.serhiip.constellations.TextSimilarity

object RagEngine:

  enum Error extends RuntimeException:
    case NoContextsFound(corpusName: String)
    case DecodeFailed(message: String)

    override def getMessage(): String = this match
      case NoContextsFound(corpusName) => s"No contexts found in RAG corpus: $corpusName"
      case DecodeFailed(message)       => message

  def similarity[F[_]: MonadThrow, T](client: RagClient[F], corpusName: String, retrieval: RetrievalConfig)(using
      decoder: ContextDecoder[F, T]
  ): TextSimilarity[F, T] = new:
    def findClosest(query: String, k: Int): F[NEL[T]] =
      val effective = retrieval.copy(topK = Option.when(k > 0)(k).orElse(retrieval.topK))
      for
        contexts <- client.retrieveContexts(corpusName, query, effective)
        nel      <- NEL.fromList(contexts).liftTo[F](Error.NoContextsFound(corpusName))
        decoded  <- nel.traverse(decoder.decode)
      yield decoded

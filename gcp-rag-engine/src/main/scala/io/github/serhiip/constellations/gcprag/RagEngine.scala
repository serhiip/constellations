package io.github.serhiip.constellations.gcprag

import cats.MonadThrow
import cats.data.NonEmptyList as NEL
import cats.syntax.all.*

import io.github.serhiip.constellations.TextSimilarity
import cats.mtl.Ask

object RagEngine:

  enum Error extends RuntimeException:
    case NoContextsFound(corpusName: String)
    case DecodeFailed(message: String)

    override def getMessage(): String = this match
      case NoContextsFound(corpusName) => s"No contexts found in RAG corpus: $corpusName"
      case DecodeFailed(message)       => message

  object Similarity:
    def simple[F[_]: MonadThrow, T](client: RagClient[F], corpusName: String, retrieval: RetrievalConfig)(using
        decoder: ContextDecoder[F, T]
    ): TextSimilarity[F, T] = fromAsk(client)(using MonadThrow[F], decoder, Ask.const(retrieval -> corpusName))

    def fromAsk[F[_]: MonadThrow, T](
        client: RagClient[F]
    )(using decoder: ContextDecoder[F, T], context: Ask[F, (RetrievalConfig, String)]): TextSimilarity[F, T] = new:
      def findClosest(query: String, k: Int): F[NEL[T]] =
        for
          (retrieval, corpusName) <- context.ask
          effective                = retrieval.copy(topK = Option.when(k > 0)(k).orElse(retrieval.topK))
          contexts                <- client.retrieveContexts(corpusName, query, effective)
          nel                     <- NEL.fromList(contexts).liftTo[F](Error.NoContextsFound(corpusName))
          decoded                 <- nel.traverse(decoder.decode)
        yield decoded

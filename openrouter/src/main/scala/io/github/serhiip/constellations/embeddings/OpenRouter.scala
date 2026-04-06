package io.github.serhiip.constellations.embeddings

import cats.MonadThrow
import cats.data.NonEmptyList as NEL
import cats.syntax.all.*
import io.circe.Json

import io.github.serhiip.constellations.Embeddings
import io.github.serhiip.constellations.openrouter.{Client, EmbeddingsRequest}

object OpenRouter:

  case class Config(model: String)

  enum Error extends RuntimeException:
    case EmptyResponse
    case DimensionMismatch(expected: Int, actual: Int)

    override def getMessage(): String = this match
      case EmptyResponse               => "Embedding API returned empty response"
      case DimensionMismatch(exp, act) => s"Dimension mismatch in batch: expected $exp, got $act"

  def apply[F[_]: MonadThrow](client: Client[F], config: Config): Embeddings[F] = new:

    override def embed(text: String): F[NEL[Float]] =
      val request = EmbeddingsRequest(
        model = config.model,
        input = Json.fromString(text)
      )
      client.createEmbeddings(request).flatMap: response =>
        response.data.sortBy(_.index).headOption match
          case None => Error.EmptyResponse.raiseError[F, NEL[Float]]
          case Some(data) =>
            NEL.fromList(data.embedding) match
              case None       => Error.EmptyResponse.raiseError[F, NEL[Float]]
              case Some(nel)  => nel.pure[F]

    override def embedBatch(texts: NEL[String]): F[NEL[List[Float]]] =
      val request = EmbeddingsRequest(
        model = config.model,
        input = Json.arr(texts.toList.map(Json.fromString)*)
      )
      client.createEmbeddings(request).flatMap: response =>
        val embeddings = response.data.sortBy(_.index).map(_.embedding)
        NEL.fromList(embeddings) match
          case None => Error.EmptyResponse.raiseError[F, NEL[List[Float]]]
          case Some(nel) =>
            val dim = nel.head.size
            nel.toList.find(_.size != dim) match
              case Some(different) =>
                Error.DimensionMismatch(dim, different.size).raiseError[F, NEL[List[Float]]]
              case None => nel.pure[F]

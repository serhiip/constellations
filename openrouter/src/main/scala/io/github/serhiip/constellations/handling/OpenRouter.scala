package io.github.serhiip.constellations.handling

import cats.effect.Sync
import cats.syntax.all.*

import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.openrouter.*
import io.circe.parser.*
import io.github.serhiip.constellations.common.Codecs.given
import java.util.Base64

object OpenRouter:

  enum Error extends RuntimeException:
    case MalformedImageDataUrl(url: String)
    case DecodeError(message: String)

    override def getMessage(): String = this match
      case MalformedImageDataUrl(url) => s"Malformed image data URL: $url"
      case DecodeError(message)       => s"Failed to decode image: $message"

  def apply[F[_]: Sync](): Handling[F, ChatCompletionResponse] = new:

    private val DataUrl = raw"^data:(image/[-+.\w]+);base64,(.*)$$".r

    private def extFromMediaType(mediaType: String): String =
      mediaType match
        case "image/png"  => "png"
        case "image/jpeg" => "jpg"
        case "image/webp" => "webp"
        case "image/gif"  => "gif"
        case _            => "img"

    private def decodeImage(raw: String): Either[Error, GeneratedImage[F]] =
      raw match
        case DataUrl(mime, data) =>
          Either
            .catchNonFatal(Base64.getMimeDecoder.decode(data))
            .map { bytes =>
              GeneratedImage(mime, extFromMediaType(mime), fs2.Stream.chunk(fs2.Chunk.array(bytes)).covary[F])
            }
            .leftMap(e => Error.DecodeError(e.getMessage))
        case _                   => Error.MalformedImageDataUrl(raw).asLeft

    override def structuredOutput(response: ChatCompletionResponse): F[Struct] =
      response.choices.headOption match
        case Some(ChatCompletionChoice(_, ChatMessage(_, Some(content), _, _, _, _), _)) =>
          content.as[Struct] match
            case Right(s) => s.pure[F]
            case Left(e)  =>
              RuntimeException(s"Failed to parse structured output: ${e.getMessage}").raiseError[F, Struct]
        case Some(ChatCompletionChoice(_, ChatMessage(_, None, _, _, _, _), _))          => Struct.empty.pure[F]
        case None                                                                        => Struct.empty.pure[F]

    override def getTextFromResponse(response: ChatCompletionResponse): F[Option[String]] =
      response.choices.headOption match
        case Some(ChatCompletionChoice(_, ChatMessage(_, Some(content), _, _, _, _), _)) => content.asString.filterNot(_.isBlank).pure[F]
        case _                                                                           => none[String].pure[F]

    override def getFunctinoCalls(response: ChatCompletionResponse): F[List[FunctionCall]] =
      response.choices.headOption match
        case Some(ChatCompletionChoice(_, ChatMessage(_, _, Some(toolCalls), _, _, _), _)) =>
          toolCalls.traverse { toolCall =>
            parse(toolCall.function.arguments) match
              case Right(json) =>
                val fields = json.asObject
                  .map(_.toMap.view.mapValues(_.toString).mapValues(Value.string).toMap)
                  .getOrElse(Map.empty)
                FunctionCall(toolCall.function.name, Struct(fields), toolCall.id.some).pure[F]
              case Left(error) =>
                RuntimeException(s"Failed to parse tool call arguments: ${error.getMessage}")
                  .raiseError[F, FunctionCall]
          }
        case Some(ChatCompletionChoice(_, ChatMessage(_, _, None, _, _, _), _)) | None     => List.empty[FunctionCall].pure[F]

    override def finishReason(response: ChatCompletionResponse): F[FinishReason] =
      response.choices.headOption match
        case Some(ChatCompletionChoice(_, _, Some(finishReason))) =>
          finishReason match
            case "tool_calls"     => FinishReason.ToolCalls.pure[F]
            case "stop"           => FinishReason.Stop.pure[F]
            case "length"         => FinishReason.Length.pure[F]
            case "content_filter" => FinishReason.ContentFilter.pure[F]
            case _                => FinishReason.Error.pure[F]
        case Some(ChatCompletionChoice(_, _, None)) | None        => FinishReason.Error.pure[F]

    override def getImages(response: ChatCompletionResponse): F[List[GeneratedImage[F]]] =
      response.choices.headOption
        .flatMap(_.message.images)
        .getOrElse(List.empty)
        .traverse { imageUrlContent =>
          decodeImage(imageUrlContent.imageUrl.url).liftTo[F]
        }

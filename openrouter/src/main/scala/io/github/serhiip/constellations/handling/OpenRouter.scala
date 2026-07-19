package io.github.serhiip.constellations.handling

import java.util.Base64

import cats.MonadThrow
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import io.circe.parser.*

import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Codecs.given
import io.github.serhiip.constellations.openrouter.*

object OpenRouter:

  enum Error extends RuntimeException:
    case MalformedImageDataUrl(url: String)
    case DecodeError(message: String)

    override def getMessage(): String = this match
      case MalformedImageDataUrl(url) => s"Malformed image data URL: $url"
      case DecodeError(message)       => s"Failed to decode image: $message"

  def apply(): Handling[ChatCompletionResponse] = summon

  given Handling[ChatCompletionResponse] with
    override def structuredOutput(response: ChatCompletionResponse): Either[Throwable, Struct] =
      response.choices.headOption match
        case Some(ChatCompletionChoice(_, ChatMessage(_, Some(content), _, _, _, _), _)) =>
          content.as[Struct].leftMap(e => RuntimeException(s"Failed to parse structured output: ${e.getMessage}"))
        case Some(ChatCompletionChoice(_, ChatMessage(_, None, _, _, _, _), _))          => Struct.empty.asRight
        case None                                                                        => Struct.empty.asRight

    override def getTextFromResponse(response: ChatCompletionResponse): Option[String] =
      response.choices.headOption match
        case Some(ChatCompletionChoice(_, ChatMessage(_, Some(content), _, _, _, _), _)) => content.asString.filterNot(_.isBlank)
        case _                                                                           => none

    override def getFunctionCalls(response: ChatCompletionResponse): Either[Throwable, List[FunctionCall]] =
      response.choices.headOption match
        case Some(ChatCompletionChoice(_, ChatMessage(_, _, Some(toolCalls), _, _, _), _)) =>
          toolCalls.traverse { toolCall =>
            val argsJson = Option(toolCall.function.arguments).filterNot(_.isBlank).getOrElse("{}")
            decode[Struct](argsJson).bimap(
              error => RuntimeException(s"Failed to parse tool call arguments: ${error.getMessage}"),
              struct => FunctionCall(toolCall.function.name, struct, toolCall.id.some)
            )
          }
        case Some(ChatCompletionChoice(_, ChatMessage(_, _, None, _, _, _), _)) | None     => List.empty[FunctionCall].asRight

    override def finishReason(response: ChatCompletionResponse): FinishReason =
      response.choices.headOption match
        case Some(ChatCompletionChoice(_, _, Some(finishReason))) =>
          finishReason match
            case "tool_calls"     => FinishReason.ToolCalls
            case "stop"           => FinishReason.Stop
            case "length"         => FinishReason.Length
            case "content_filter" => FinishReason.ContentFilter
            case _                => FinishReason.Error
        case Some(ChatCompletionChoice(_, _, None)) | None        => FinishReason.Error

  given [F[_]: MonadThrow]: AssetsHandling[F, ChatCompletionResponse] with
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
            .map(bytes => GeneratedImage(mime, extFromMediaType(mime), Stream.chunk(Chunk.array(bytes)).covary[F]))
            .leftMap(e => Error.DecodeError(e.getMessage))
        case _                   => Error.MalformedImageDataUrl(raw).asLeft

    override def getImages(response: ChatCompletionResponse): F[List[GeneratedImage[F]]] =
      response.choices.headOption
        .flatMap(_.message.images)
        .getOrElse(List.empty)
        .traverse(imageUrlContent => decodeImage(imageUrlContent.imageUrl.url).liftTo[F])

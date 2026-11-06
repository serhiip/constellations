package io.github.serhiip.constellations.handling

import java.util.Base64

import cats.MonadThrow
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import io.circe.parser.*

import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Codecs.given
import io.github.serhiip.constellations.openrouter.schema.chat.content.items.ChatContentText
import io.github.serhiip.constellations.openrouter.schema.chat.finish.reason.enum_.ChatFinishReasonEnum
import io.github.serhiip.constellations.openrouter.schema.chat.messages.ChatAssistantMessage
import io.github.serhiip.constellations.openrouter.schema.chat.result.ChatResult

object OpenRouter:

  enum Error extends RuntimeException:
    case MalformedImageDataUrl(url: String)
    case DecodeError(message: String)

    override def getMessage(): String = this match
      case MalformedImageDataUrl(url) => s"Malformed image data URL: $url"
      case DecodeError(message)       => s"Failed to decode image: $message"

  def apply(): Handling[ChatResult] = summon

  given Handling[ChatResult] with
    override def structuredOutput(response: ChatResult): Either[Throwable, Struct] =
      response.choices.headOption.map(_.message).flatMap(textFrom) match
        case Some(content) =>
          decode[Struct](content).leftMap(e => RuntimeException(s"Failed to parse structured output: ${e.getMessage}"))
        case None          => Struct.empty.asRight

    override def getTextFromResponse(response: ChatResult): Option[String] =
      response.choices.headOption.flatMap(choice => textFrom(choice.message).orElse(choice.message.reasoning.filterNot(_.isBlank)))

    override def getFunctionCalls(response: ChatResult): Either[Throwable, List[FunctionCall]] =
      response.choices.headOption.flatMap(_.message.tool_calls) match
        case Some(toolCalls) =>
          toolCalls.traverse { toolCall =>
            val argsJson = Option(toolCall.function.arguments).filterNot(_.isBlank).getOrElse("{}")
            decode[Struct](argsJson).bimap(
              error => RuntimeException(s"Failed to parse tool call arguments: ${error.getMessage}"),
              struct => FunctionCall(toolCall.function.name, struct, toolCall.id.some)
            )
          }
        case None            => List.empty[FunctionCall].asRight

    override def finishReason(response: ChatResult): FinishReason =
      response.choices.headOption.flatMap(_.finish_reason) match
        case Some(ChatFinishReasonEnum.ToolCalls)                                      => FinishReason.ToolCalls
        case Some(ChatFinishReasonEnum.Stop)                                           => FinishReason.Stop
        case Some(ChatFinishReasonEnum.Length)                                         => FinishReason.Length
        case Some(ChatFinishReasonEnum.ContentFilter)                                  => FinishReason.ContentFilter
        case Some(ChatFinishReasonEnum.Error | ChatFinishReasonEnum.Unknown(_)) | None => FinishReason.Error

    private def textFrom(message: ChatAssistantMessage): Option[String] =
      message.content.flatMap {
        case text: String   => Option.when(!text.isBlank)(text)
        case parts: List[?] =>
          val text = parts.collect { case part: ChatContentText => part.text }.filterNot(_.isBlank).mkString
          Option.when(text.nonEmpty)(text)
      }

  given [F[_]: MonadThrow]: AssetsHandling[F, ChatResult] with
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

    override def getImages(response: ChatResult): F[List[GeneratedImage[F]]] =
      response.choices.headOption
        .flatMap(_.message.images)
        .getOrElse(List.empty)
        .traverse(image => decodeImage(image.image_url.url).liftTo[F])

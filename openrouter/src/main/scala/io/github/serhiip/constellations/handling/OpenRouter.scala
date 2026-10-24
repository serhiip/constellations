package io.github.serhiip.constellations.handling

import java.util.Base64

import cats.MonadThrow
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import io.circe.Json
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
      response.choices.headOption.flatMap(_.message.content) match
        case Some(content) =>
          content.as[Struct].leftMap(e => RuntimeException(s"Failed to parse structured output: ${e.getMessage}"))
        case None          => Struct.empty.asRight

    override def getTextFromResponse(response: ChatCompletionResponse): Option[String] =
      response.choices.headOption.flatMap(choice => textFrom(choice.message))

    override def getFunctionCalls(response: ChatCompletionResponse): Either[Throwable, List[FunctionCall]] =
      response.choices.headOption.flatMap(_.message.toolCalls) match
        case Some(toolCalls) =>
          toolCalls.traverse { toolCall =>
            val argsJson = Option(toolCall.function.arguments).filterNot(_.isBlank).getOrElse("{}")
            decode[Struct](argsJson).bimap(
              error => RuntimeException(s"Failed to parse tool call arguments: ${error.getMessage}"),
              struct => FunctionCall(toolCall.function.name, struct, toolCall.id.some)
            )
          }
        case None            => List.empty[FunctionCall].asRight

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

    private def textFrom(message: ChatMessage): Option[String] =
      message.content.flatMap(textFromContent).orElse(message.reasoning.filterNot(_.isBlank))

    private def textFromContent(content: Json): Option[String] =
      content.asString.filterNot(_.isBlank).orElse(textFromParts(content))

    private def textFromParts(content: Json): Option[String] =
      content.asArray.flatMap { parts =>
        val text = parts.flatMap(textFromPart).mkString
        Option.when(text.nonEmpty)(text)
      }

    private def textFromPart(part: Json): Option[String] =
      val partType = part.hcursor.get[String]("type").toOption
      Option
        .when(!partType.exists(_.contains("image")))(part.hcursor.get[String]("text").toOption.filterNot(_.isBlank))
        .flatten

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

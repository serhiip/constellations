package io.github.serhiip.constellations.handling

import scala.jdk.CollectionConverters.*

import cats.MonadThrow
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import io.circe.parser.parse
import software.amazon.awssdk.services.bedrockruntime.model.{
  ContentBlock,
  ConverseResponse,
  ImageBlock,
  ImageFormat,
  StopReason
}

import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.bedrock.Codecs
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Codecs.given

object Bedrock:

  enum Error extends RuntimeException:
    case MissingImageFormat
    case MissingImageSource
    case UnsupportedImageSource
    case ImageBlockError(message: String)
    case UnknownImageFormat(format: ImageFormat)

    override def getMessage(): String = this match
      case MissingImageFormat         => "Missing image format"
      case MissingImageSource         => "Missing image source"
      case UnsupportedImageSource     => "Image source bytes are missing (S3 locations are not supported)"
      case ImageBlockError(message)   => s"Image block error: $message"
      case UnknownImageFormat(format) => s"Unknown image format: $format"

  def apply(): Handling[ConverseResponse] = summon

  given Handling[ConverseResponse] with
    override def getTextFromResponse(response: ConverseResponse): Option[String] =
      Option(contentBlocks(response).flatMap(b => Option(b.text())).mkString).filterNot(_.isBlank)

    override def getFunctionCalls(response: ConverseResponse): Either[Throwable, List[FunctionCall]] =
      contentBlocks(response)
        .flatMap(b => Option(b.toolUse()))
        .map: toolUse =>
          FunctionCall(
            toolUse.name(),
            Codecs.documentToStruct(toolUse.input()),
            Option(toolUse.toolUseId()).filter(_.nonEmpty)
          )
        .asRight

    override def finishReason(response: ConverseResponse): FinishReason =
      Option(response.stopReason()).map(mapStopReason).getOrElse(FinishReason.Error)

    override def structuredOutput(response: ConverseResponse): Either[Throwable, Struct] =
      for
        text   <- getTextFromResponse(response).toRight(RuntimeException("Missing structured output text"))
        result <- parse(text) match
                    case Left(err)   =>
                      RuntimeException(s"Failed to parse structured output JSON: ${err.getMessage}", err).asLeft
                    case Right(json) =>
                      json
                        .as[Struct]
                        .leftMap(err => RuntimeException(s"Failed to parse structured output JSON: ${err.getMessage}", err))
      yield result

    private def mapStopReason(reason: StopReason): FinishReason = reason match
      case StopReason.END_TURN | StopReason.STOP_SEQUENCE                => FinishReason.Stop
      case StopReason.TOOL_USE                                           => FinishReason.ToolCalls
      case StopReason.MAX_TOKENS                                         => FinishReason.Length
      case StopReason.CONTENT_FILTERED | StopReason.GUARDRAIL_INTERVENED => FinishReason.ContentFilter
      case _                                                             => FinishReason.Error

  given [F[_]: MonadThrow]: AssetsHandling[F, ConverseResponse] with
    override def getImages(response: ConverseResponse): F[List[GeneratedImage[F]]] =
      contentBlocks(response).flatMap(b => Option(b.image())).traverse(toGeneratedImage).liftTo[F]

    private def toGeneratedImage(image: ImageBlock): Either[Error, GeneratedImage[F]] =
      for
        _      <- Option(image.error()).map(err => Error.ImageBlockError(Option(err.message()).getOrElse("unknown"))).toLeft(())
        format <- Option(image.format()).toRight(Error.MissingImageFormat)
        source <- Option(image.source()).toRight(Error.MissingImageSource)
        bytes  <- Option(source.bytes()).toRight(Error.UnsupportedImageSource)
        meta   <- mimeAndExt(format)
      yield GeneratedImage(meta._1, meta._2, Stream.chunk(Chunk.array(bytes.asByteArray())).covary[F])

    private def mimeAndExt(format: ImageFormat): Either[Error, (String, String)] = format match
      case ImageFormat.PNG  => ("image/png", "png").asRight
      case ImageFormat.JPEG => ("image/jpeg", "jpg").asRight
      case ImageFormat.GIF  => ("image/gif", "gif").asRight
      case ImageFormat.WEBP => ("image/webp", "webp").asRight
      case other            => Error.UnknownImageFormat(other).asLeft

  private def contentBlocks(response: ConverseResponse): List[ContentBlock] =
    Option(response.output())
      .flatMap(o => Option(o.message()))
      .flatMap(m => Option(m.content()))
      .map(_.asScala.toList)
      .getOrElse(Nil)

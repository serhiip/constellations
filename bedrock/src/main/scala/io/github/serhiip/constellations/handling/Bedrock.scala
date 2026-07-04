package io.github.serhiip.constellations.handling

import cats.MonadThrow
import cats.syntax.all.*
import io.circe.parser.parse
import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.bedrock.Codecs
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Codecs.given
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.services.bedrockruntime.model.{ConverseResponse, StopReason}

object Bedrock:

  def apply[F[_]: MonadThrow]: Handling[F, ConverseResponse] = new:

    override def getTextFromResponse(response: ConverseResponse): F[Option[String]] =
      Option(response.output())
        .flatMap(o => Option(o.message()))
        .flatMap(m => Option(m.content()))
        .map(_.asScala.toList.flatMap(b => Option(b.text())).mkString)
        .filterNot(_.isBlank)
        .pure[F]

    override def getFunctinoCalls(response: ConverseResponse): F[List[FunctionCall]] =
      val blocks   = Option(response.output())
        .flatMap(o => Option(o.message()))
        .flatMap(m => Option(m.content()))
        .map(_.asScala.toList)
        .getOrElse(Nil)
      val toolUses = blocks.flatMap(b => Option(b.toolUse()))
      toolUses.traverse: toolUse =>
        val name = toolUse.name()
        val args = Codecs.documentToStruct(toolUse.input())
        FunctionCall(name, args, Option(toolUse.toolUseId()).filter(_.nonEmpty)).pure[F]

    override def finishReason(response: ConverseResponse): F[FinishReason] =
      Option(response.stopReason()).map(mapStopReason).getOrElse(FinishReason.Error).pure[F]

    override def structuredOutput(response: ConverseResponse): F[Struct] =
      for
        text   <- getTextFromResponse(response).flatMap(_.liftTo[F](RuntimeException("Missing structured output text")))
        result <- parse(text) match
                    case Left(err)   =>
                      RuntimeException(s"Failed to parse structured output JSON: ${err.getMessage}", err).raiseError[F, Struct]
                    case Right(json) =>
                      json
                        .as[Struct]
                        .leftMap(err => RuntimeException(s"Failed to parse structured output JSON: ${err.getMessage}", err))
                        .liftTo
      yield result

    override def getImages(response: ConverseResponse): F[List[GeneratedImage[F]]] =
      List.empty[GeneratedImage[F]].pure[F]

    private def mapStopReason(reason: StopReason): FinishReason = reason match
      case StopReason.END_TURN | StopReason.STOP_SEQUENCE                => FinishReason.Stop
      case StopReason.TOOL_USE                                           => FinishReason.ToolCalls
      case StopReason.MAX_TOKENS                                         => FinishReason.Length
      case StopReason.CONTENT_FILTERED | StopReason.GUARDRAIL_INTERVENED => FinishReason.ContentFilter
      case _                                                             => FinishReason.Error

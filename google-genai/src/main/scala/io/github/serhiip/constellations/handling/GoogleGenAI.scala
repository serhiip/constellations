package io.github.serhiip.constellations.handling

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import cats.Applicative
import cats.syntax.all.*
import com.google.genai.types.{FinishReason$Known as GKnown, GenerateContentResponse}
import io.circe.parser.parse

import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Codecs.given

object GoogleGenAI:

  def apply(): Handling[GenerateContentResponse] = summon

  given Handling[GenerateContentResponse] with
    override def getTextFromResponse(response: GenerateContentResponse): Option[String] =
      Option(response.text())

    override def getFunctionCalls(response: GenerateContentResponse): Either[Throwable, List[FunctionCall]] =
      response.functionCalls().asScala.toList.traverse { call =>
        for
          name <- call.name().toScala.toRight(RuntimeException("Missing function call name"))
          raw  <- call.args().toScala.toRight(RuntimeException(s"Missing function call args for: $name"))
        yield FunctionCall(name, Struct.fromMap(raw.asScala.toMap), call.id().toScala)
      }

    override def finishReason(response: GenerateContentResponse): FinishReason =
      response.finishReason().knownEnum() match
        case GKnown.STOP       => FinishReason.Stop
        case GKnown.MAX_TOKENS => FinishReason.Length
        case GKnown.SAFETY     => FinishReason.ContentFilter
        case _                 => FinishReason.Error

    override def structuredOutput(response: GenerateContentResponse): Either[Throwable, Struct] =
      val txt = response.text()
      parse(txt) match
        case Left(err)   => RuntimeException(s"Failed to parse structured output JSON: ${err.getMessage}", err).asLeft
        case Right(json) =>
          json.as[Struct].leftMap(err => RuntimeException(s"Failed to parse structured output JSON: ${err.getMessage}", err))

  given [F[_]: Applicative]: AssetsHandling[F, GenerateContentResponse] with
    override def getImages(response: GenerateContentResponse): F[List[GeneratedImage[F]]] =
      List.empty[GeneratedImage[F]].pure[F]

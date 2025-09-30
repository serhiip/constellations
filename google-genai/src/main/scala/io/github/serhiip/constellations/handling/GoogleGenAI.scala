package io.github.serhiip.constellations.handling

import cats.syntax.all.*

import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.common.*
import com.google.genai.types.{FinishReason$Known as GKnown, GenerateContentResponse}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import cats.MonadThrow
import io.circe.parser.parse
import io.github.serhiip.constellations.common.Codecs.given

object GoogleGenAI:

  def apply[F[_]: MonadThrow]: Handling[F, GenerateContentResponse] = new:
    override def getTextFromResponse(response: GenerateContentResponse): F[String] =
      response.text().pure[F]

    override def getFunctinoCalls(response: GenerateContentResponse): F[List[FunctionCall]] =
      response.functionCalls().asScala.toList.traverse { call =>
        val name = call.name().toScala.liftTo[F](RuntimeException("Missing function call name"))
        val raw  = call.args().toScala.liftTo[F](RuntimeException(s"Missing function call args for: $name"))
        (name, raw).mapN({ case (name, raw) =>
          FunctionCall(name, Struct.fromMap(raw.asScala.toMap), call.id().toScala)
        })
      }

    override def finishReason(response: GenerateContentResponse): F[FinishReason] =
      response.finishReason().knownEnum() match
        case GKnown.STOP       => FinishReason.Stop.pure[F]
        case GKnown.MAX_TOKENS => FinishReason.Length.pure[F]
        case GKnown.SAFETY     => FinishReason.ContentFilter.pure[F]
        case other             => RuntimeException(s"Unknown finish reason: $other").raiseError[F, FinishReason]

    override def structuredOutput(response: GenerateContentResponse): F[Struct] =
      val txt = response.text()
      parse(txt) match
        case Left(err)   =>
          RuntimeException(s"Failed to parse structured output JSON: ${err.getMessage}").raiseError[F, Struct]
        case Right(json) =>
          json.as[Struct] match
            case Left(decErr) =>
              RuntimeException(s"Failed to decode structured output: ${decErr.getMessage}").raiseError[F, Struct]
            case Right(s)     => s.pure[F]

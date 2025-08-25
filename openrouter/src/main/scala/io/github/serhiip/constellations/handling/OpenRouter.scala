package io.github.serhiip.constellations.handling

import cats.effect.Sync
import cats.syntax.all.*

import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.openrouter.*
import io.circe.parser.*

object OpenRouter:

  def apply[F[_]: Sync](): Handling[F, ChatCompletionResponse] = new:
    override def getTextFromResponse(response: ChatCompletionResponse): F[String] =
      response.choices.headOption match
        case Some(ChatCompletionChoice(_, ChatMessage(_, Some(content), _, _, _), _)) => (content.asString.getOrElse(content.toString)).pure[F]
        case Some(ChatCompletionChoice(_, ChatMessage(_, None, _, _, _), _))          => "No content in response".pure[F]
        case None                                                                     => "No choices in response".pure[F]

    override def getFunctinoCalls(response: ChatCompletionResponse): F[List[FunctionCall]] =
      response.choices.headOption match
        case Some(ChatCompletionChoice(_, ChatMessage(_, _, Some(toolCalls), _, _), _)) =>
          toolCalls.traverse { toolCall =>
            parse(toolCall.function.arguments) match
              case Right(json) =>
                val fields = json.asObject.map(_.toMap.view.mapValues(_.toString).mapValues(Value.string).toMap).getOrElse(Map.empty)
                FunctionCall(toolCall.function.name, Struct(fields), toolCall.id.some).pure[F]
              case Left(error) => RuntimeException(s"Failed to parse tool call arguments: ${error.getMessage}").raiseError[F, FunctionCall]
          }
        case Some(ChatCompletionChoice(_, ChatMessage(_, _, None, _, _), _)) | None     => List.empty[FunctionCall].pure[F]

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

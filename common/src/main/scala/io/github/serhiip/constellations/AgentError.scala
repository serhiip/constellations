package io.github.serhiip.constellations

import cats.Show
import cats.data.NonEmptyChain
import cats.syntax.all.*

import io.github.serhiip.constellations.common.FunctionCall
import io.github.serhiip.constellations.dispatcher.Decoder

enum AgentError extends RuntimeException:
  case ArgumentDecodingFailed(call: FunctionCall, errors: NonEmptyChain[Decoder.Error])
  case UnknownFunction(call: FunctionCall)

  def functionCall: FunctionCall = this match
    case ArgumentDecodingFailed(call, _) => call
    case UnknownFunction(call)           => call

  override def getMessage: String = this match
    case ArgumentDecodingFailed(call, errors) => s"Failed to decode arguments for method '${call.name}': ${errors.mkString_(delim = ", ")}"
    case UnknownFunction(call)                => s"No handler for ${call.name}"

object AgentError:
  given Show[AgentError] = Show.show:
    case ArgumentDecodingFailed(call, errors) =>
      s"The arguments provided for tool '${call.name}' are invalid: ${errors.mkString_(delim = "; ")}. " +
        s"Fix the argument names, types, and required fields so they match the '${call.name}' tool schema, then call it again."
    case UnknownFunction(call)                =>
      s"There is no tool named '${call.name}'. " +
        "Call one of the available tools using its exact name as defined in the provided tool schema."

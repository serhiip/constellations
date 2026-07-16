package io.github.serhiip.constellations

import io.github.serhiip.constellations.common.*

trait Handling[T]:
  def getTextFromResponse(response: T): Option[String]
  def getFunctionCalls(response: T): Either[Throwable, List[FunctionCall]]
  def finishReason(response: T): FinishReason
  def structuredOutput(response: T): Either[Throwable, Struct]

object Handling:
  def apply[T](using h: Handling[T]): Handling[T] = h

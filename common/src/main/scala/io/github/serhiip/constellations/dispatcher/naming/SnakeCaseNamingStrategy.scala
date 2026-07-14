package io.github.serhiip.constellations.dispatcher.naming

import scala.annotation.tailrec

import cats.data.Chain

object SnakeCaseNamingStrategy extends NamingStrategy:
  def componentName(name: String): String = name
  def methodName(name: String): String    = toSnakeCase(name)
  def parameterName(name: String): String = toSnakeCase(name)

  private def toSnakeCase(name: String): String =
    @tailrec
    def loop(i: Int, prevWasUpper: Boolean, prevWasLower: Boolean, acc: Chain[Char]): Chain[Char] =
      if i >= name.length then acc
      else if name.charAt(i).isUpper then
        loop(
          i + 1,
          prevWasUpper = true,
          prevWasLower = false,
          (
            if prevWasLower || (prevWasUpper && i + 1 < name.length && name.charAt(i + 1).isLower) then
              acc.append('_').append(name.charAt(i).toLower)
            else acc.append(name.charAt(i).toLower)
          )
        )
      else
        loop(
          i + 1,
          prevWasUpper = false,
          prevWasLower = name.charAt(i).isLower,
          acc.append(name.charAt(i))
        )

    if name.isEmpty then name
    else loop(0, prevWasUpper = false, prevWasLower = false, acc = Chain.empty).iterator.mkString

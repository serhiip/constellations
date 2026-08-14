package io.github.serhiip.constellations.naming

import scala.annotation.tailrec

object Renaming:
  def snakeCase(name: String): String =
    if name.isEmpty then name
    else
      val acc = new StringBuilder(name.length + 4)

      @tailrec
      def loop(i: Int, prevWasUpper: Boolean, prevWasLower: Boolean): String =
        if i >= name.length then acc.result()
        else if name.charAt(i).isUpper then
          if prevWasLower || (prevWasUpper && i + 1 < name.length && name.charAt(i + 1).isLower) then acc.append('_')
          acc.append(name.charAt(i).toLower)
          loop(i + 1, prevWasUpper = true, prevWasLower = false)
        else
          acc.append(name.charAt(i))
          loop(i + 1, prevWasUpper = false, prevWasLower = name.charAt(i).isLower)
      loop(0, prevWasUpper = false, prevWasLower = false)

  def kebabCase(name: String): String =
    snakeCase(name).replace('_', '-')

  def screamingSnakeCase(name: String): String =
    snakeCase(name).toUpperCase

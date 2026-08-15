package io.github.serhiip.constellations.common

object Docstrings:
  def sanitize(raw: Option[String]): Option[String] =
    raw.map(sanitize).filter(_.nonEmpty)

  def sanitize(raw: String): String =
    val trimmed = raw.trim
    val body    =
      if trimmed.startsWith("/**") && trimmed.endsWith("*/") then trimmed.drop(3).dropRight(2)
      else if trimmed.startsWith("/*") && trimmed.endsWith("*/") then trimmed.drop(2).dropRight(2)
      else trimmed
    body.linesIterator.map(stripLeadingStar).mkString("\n").trim

  private def stripLeadingStar(line: String): String =
    val indentStripped = line.stripLeading
    if indentStripped.startsWith("*") then indentStripped.drop(1).stripLeading
    else line.stripTrailing

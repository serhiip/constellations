package io.github.serhiip.constellations.dispatcher.naming

object SnakeCaseNamingStrategy extends NamingStrategy:
  def componentName(name: String): String = toSnakeCase(name)
  def methodName(name: String): String = toSnakeCase(name)
  def parameterName(name: String): String = toSnakeCase(name)

  private def toSnakeCase(name: String): String =
    if name.isEmpty then name
    else
      val result = new StringBuilder
      var prevWasUpper = false
      var prevWasLower = false

      for i <- name.indices do
        val c = name(i)
        val isUpper = c.isUpper
        val isLower = c.isLower

        if isUpper then
          // Insert underscore before this uppercase letter if:
          // 1. Previous char was lowercase (camelCase boundary)
          // 2. Previous char was uppercase AND next char (if exists) is lowercase (end of acronym)
          if prevWasLower then
            result += '_'
          else if prevWasUpper && i + 1 < name.length && name(i + 1).isLower then
            result += '_'
          result += c.toLower
          prevWasUpper = true
          prevWasLower = false
        else
          result += c
          prevWasUpper = false
          prevWasLower = isLower

      result.toString

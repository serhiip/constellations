package io.github.serhiip.sbt.openapi

object Names {
  def scalaName(name: String): String = {
    val parts = name.split("[^A-Za-z0-9]+").filter(_.nonEmpty)
    val pascal = parts.map(part => part.head.toUpper.toString + part.tail).mkString
    if pascal.isEmpty || !pascal.head.isLetter then
      sys.error(s"OpenAPI spec name '$name' cannot be used as a Scala identifier")
    else pascal
  }

  def kebab(name: String): String = {
    val spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
    val result = spaced.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase.stripPrefix("-").stripSuffix("-")
    if result.isEmpty then sys.error(s"OpenAPI spec name '$name' is empty")
    else result
  }

  def examplesResource(name: String): String = s"${kebab(name)}-schema-examples.json"
}

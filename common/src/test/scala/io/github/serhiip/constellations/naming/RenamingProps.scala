package io.github.serhiip.constellations.naming

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.Gen

class RenamingProps extends ScalaCheckSuite:
  override def scalaCheckTestParameters = super.scalaCheckTestParameters
    .withMinSuccessfulTests(1000)
    .withWorkers(Runtime.getRuntime.availableProcessors())

  val stringGen: Gen[String] = Gen.alphaNumStr

  property("snakeCase output contains only lowercase letters, underscores, and digits") {
    forAll(stringGen) { (name: String) =>
      val result = Renaming.snakeCase(name)
      result.forall(c => c.isLower || c.isDigit || c == '_')
    }
  }

  property("snakeCase has no leading underscores") {
    forAll(stringGen) { (name: String) =>
      val result = Renaming.snakeCase(name)
      result.isEmpty || !result.startsWith("_")
    }
  }

  property("snakeCase has no trailing underscores") {
    forAll(stringGen) { (name: String) =>
      val result = Renaming.snakeCase(name)
      result.isEmpty || !result.endsWith("_")
    }
  }

  property("snakeCase has no consecutive underscores") {
    forAll(stringGen) { (name: String) =>
      val result = Renaming.snakeCase(name)
      !result.contains("__")
    }
  }

  property("snakeCase is idempotent") {
    forAll(stringGen) { (name: String) =>
      val firstPass  = Renaming.snakeCase(name)
      val secondPass = Renaming.snakeCase(firstPass)
      firstPass == secondPass
    }
  }

  property("snakeCase handles acronyms correctly") {
    val acronymExamples = List(
      ("getAPIKey", "get_api_key"),
      ("HTTPResponse", "http_response"),
      ("XMLParser", "xml_parser"),
      ("getAPI", "get_api"),
      ("APIKey", "api_key")
    )

    acronymExamples.forall { case (input, expected) =>
      Renaming.snakeCase(input) == expected
    }
  }

  property("snakeCase single letter handling") {
    forAll(Gen.alphaChar.map(_.toString)) { (name: String) =>
      val result = Renaming.snakeCase(name)
      result == name.toLowerCase
    }
  }

  property("snakeCase empty string remains empty") {
    Renaming.snakeCase("") == ""
  }

  property("already snake_case remains unchanged") {
    forAll(Gen.alphaLowerStr) { (name: String) =>
      val result = Renaming.snakeCase(name)
      result == name.toLowerCase
    }
  }

  property("all original lowercase characters are preserved") {
    forAll(stringGen) { (name: String) =>
      val result            = Renaming.snakeCase(name)
      val originalLowercase = name.filter(_.isLower)
      originalLowercase.forall(c => result.contains(c))
    }
  }

  property("underscore count is less than or equal to uppercase count") {
    forAll(stringGen) { (name: String) =>
      val result          = Renaming.snakeCase(name)
      val uppercaseCount  = name.count(_.isUpper)
      val underscoreCount = result.count(_ == '_')
      underscoreCount <= uppercaseCount
    }
  }

  property("proper camelCase without abbreviations has underscores equal to uppercase count") {
    val properCamelCaseGen: Gen[String] = for {
      firstLower <- Gen.alphaLowerChar
      rest       <- Gen.listOf(
                      Gen.oneOf(
                        Gen.alphaLowerChar.map(_.toString),
                        Gen.alphaUpperChar.map(_.toString)
                      )
                    )
    } yield {
      val restStr = rest.mkString
      if restStr.sliding(2).exists(s => s.length == 2 && s.forall(_.isUpper)) then ""
      else firstLower.toString + restStr
    }

    forAll(properCamelCaseGen) { (name: String) =>
      name.isEmpty || {
        val result          = Renaming.snakeCase(name)
        val uppercaseCount  = name.count(_.isUpper)
        val underscoreCount = result.count(_ == '_')
        underscoreCount == uppercaseCount
      }
    }
  }

  property("handles mixed case with digits") {
    forAll(Gen.alphaNumStr) { (name: String) =>
      val result = Renaming.snakeCase(name)
      result.forall(c => c.isLower || c.isDigit || c == '_')
    }
  }

  property("kebabCase replaces underscores with hyphens") {
    forAll(stringGen) { (name: String) =>
      Renaming.kebabCase(name) == Renaming.snakeCase(name).replace('_', '-')
    }
  }

  property("screamingSnakeCase is uppercase snake_case") {
    forAll(stringGen) { (name: String) =>
      Renaming.screamingSnakeCase(name) == Renaming.snakeCase(name).toUpperCase
    }
  }

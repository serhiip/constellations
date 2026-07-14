package io.github.serhiip.constellations.dispatcher.naming

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.Gen

class SnakeCaseNamingStrategyProps extends ScalaCheckSuite:
  override def scalaCheckTestParameters = super.scalaCheckTestParameters
    .withMinSuccessfulTests(1000)
    .withWorkers(Runtime.getRuntime.availableProcessors())

  val stringGen: Gen[String] = Gen.alphaNumStr

  property("output contains only lowercase letters, underscores, and digits") {
    forAll(stringGen) { (name: String) =>
      val result = SnakeCaseNamingStrategy.methodName(name)
      result.forall(c => c.isLower || c.isDigit || c == '_')
    }
  }

  property("no leading underscores") {
    forAll(stringGen) { (name: String) =>
      val result = SnakeCaseNamingStrategy.methodName(name)
      result.isEmpty || !result.startsWith("_")
    }
  }

  property("no trailing underscores") {
    forAll(stringGen) { (name: String) =>
      val result = SnakeCaseNamingStrategy.methodName(name)
      result.isEmpty || !result.endsWith("_")
    }
  }

  property("no consecutive underscores") {
    forAll(stringGen) { (name: String) =>
      val result = SnakeCaseNamingStrategy.methodName(name)
      !result.contains("__")
    }
  }

  property("idempotent conversion") {
    forAll(stringGen) { (name: String) =>
      val firstPass = SnakeCaseNamingStrategy.methodName(name)
      val secondPass = SnakeCaseNamingStrategy.methodName(firstPass)
      firstPass == secondPass
    }
  }

  property("handles acronyms correctly") {
    val acronymExamples = List(
      ("getAPIKey", "get_api_key"),
      ("HTTPResponse", "http_response"),
      ("XMLParser", "xml_parser"),
      ("getAPI", "get_api"),
      ("APIKey", "api_key")
    )
    
    acronymExamples.forall { case (input, expected) =>
      SnakeCaseNamingStrategy.methodName(input) == expected
    }
  }

  property("single letter handling") {
    forAll(Gen.alphaChar.map(_.toString)) { (name: String) =>
      val result = SnakeCaseNamingStrategy.methodName(name)
      result == name.toLowerCase
    }
  }

  property("empty string remains empty") {
    SnakeCaseNamingStrategy.methodName("") == ""
  }

  property("already snake_case remains unchanged") {
    forAll(Gen.alphaLowerStr) { (name: String) =>
      val result = SnakeCaseNamingStrategy.methodName(name)
      result == name.toLowerCase
    }
  }

  property("all original lowercase characters are preserved") {
    forAll(stringGen) { (name: String) =>
      val result = SnakeCaseNamingStrategy.methodName(name)
      val originalLowercase = name.filter(_.isLower)
      originalLowercase.forall(c => result.contains(c))
    }
  }

  property("underscore count is less than or equal to uppercase count") {
    forAll(stringGen) { (name: String) =>
      val result = SnakeCaseNamingStrategy.methodName(name)
      val uppercaseCount = name.count(_.isUpper)
      val underscoreCount = result.count(_ == '_')

      // For proper camelCase without abbreviations: underscores == uppercase letters
      // With abbreviations (like API, HTTP), underscores < uppercase letters
      // So: underscoreCount <= uppercaseCount is the invariant
      underscoreCount <= uppercaseCount
    }
  }

  property("proper camelCase without abbreviations has underscores equal to uppercase count") {
    val properCamelCaseGen: Gen[String] = for {
      firstLower <- Gen.alphaLowerChar
      rest <- Gen.listOf(Gen.oneOf(
        Gen.alphaLowerChar.map(_.toString),
        Gen.alphaUpperChar.map(_.toString)
      ))
    } yield {
      val restStr = rest.mkString
      if (restStr.sliding(2).exists(s => s.length == 2 && s.forall(_.isUpper))) {
        ""
      } else {
        firstLower.toString + restStr
      }
    }

    forAll(properCamelCaseGen) { (name: String) =>
      name.isEmpty || {
        val result = SnakeCaseNamingStrategy.methodName(name)
        val uppercaseCount = name.count(_.isUpper)
        val underscoreCount = result.count(_ == '_')

        underscoreCount == uppercaseCount
      }
    }
  }
  
  property("handles mixed case with digits") {
    forAll(Gen.alphaNumStr) { (name: String) =>
      val result = SnakeCaseNamingStrategy.methodName(name)
      result.forall(c => c.isLower || c.isDigit || c == '_')
    }
  }

  property("componentName preserves name (CamelCase), methodName and parameterName produce same snake_case") {
    forAll(stringGen) { (name: String) =>
      SnakeCaseNamingStrategy.componentName(name) == name &&
      SnakeCaseNamingStrategy.methodName(name) == SnakeCaseNamingStrategy.parameterName(name)
    }
  }

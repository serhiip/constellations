package io.github.serhiip.constellations.typesafe

import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.parser.*
import io.circe.{Encoder, Json}
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client as HTTPClient
import org.http4s.headers.`Retry-After`
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.noop.NoOpLogger

final class ClientTest extends CatsEffectSuite:

  private val testApiKey = "test-api-key"
  private val testConfig = Client.Config()

  private def createStubClient[F[_]: cats.effect.kernel.MonadCancelThrow](response: Response[F]): HTTPClient[F] =
    HTTPClient[F] { _ => Resource.pure(response) }

  private def createTestClient(stubClient: HTTPClient[IO]): Client[IO] =
    given StructuredLogger[IO] = NoOpLogger[IO]
    Client.apply[IO](stubClient, testApiKey, testConfig)

  private def json(body: String): Json =
    parse(body).fold(error => fail(error.message), identity)

  test("Question encoder writes noul, choice, and score discriminators") {
    val noul = Question.noul("Does this convey urgency?", (yes = "Explicitly time-sensitive", no = "No urgency"))
    val choice = Question.choice(
      "Which team should handle this?",
      (billing = "Payments, invoicing, refunds", technical = "Bugs, outages, integrations")
    )
    val score = Question.score("How frustrated is the customer?", ("Calm", "Frustrated", "Very angry"))

    assertEquals(
      Encoder[Question].apply(noul),
      json("""
        |{
        |  "type": "noul",
        |  "instructions": { "type": "text", "value": "Does this convey urgency?" },
        |  "criteria": { "true": "Explicitly time-sensitive", "false": "No urgency" }
        |}
      """.stripMargin)
    )
    assertEquals(
      Encoder[Question].apply(choice),
      json("""
        |{
        |  "type": "choice",
        |  "instructions": { "type": "text", "value": "Which team should handle this?" },
        |  "criteria": {
        |    "billing": "Payments, invoicing, refunds",
        |    "technical": "Bugs, outages, integrations"
        |  }
        |}
      """.stripMargin)
    )
    assertEquals(
      Encoder[Question].apply(score),
      json("""
        |{
        |  "type": "score",
        |  "instructions": { "type": "text", "value": "How frustrated is the customer?" },
        |  "criteria": ["Calm", "Frustrated", "Very angry"]
        |}
      """.stripMargin)
    )
  }

  test("Question encoder supports structured instructions and null choice option descriptions") {
    val instructions = Json.obj("question" -> Json.fromString("Does the claimed sender conflict with the domain?"))
    val question     = Question.choice(
      instructions,
      (
        billing = Json.Null,
        technical = Json.obj("includes" -> Json.arr(Json.fromString("outages")))
      )
    )
    assertEquals(
      Encoder[Question].apply(question),
      json("""
        |{
        |  "type": "choice",
        |  "instructions": {
        |    "type": "structured",
        |    "value": { "question": "Does the claimed sender conflict with the domain?" }
        |  },
        |  "criteria": {
        |    "billing": null,
        |    "technical": { "includes": ["outages"] }
        |  }
        |}
      """.stripMargin)
    )
  }

  test("SystemOneRequest apply accepts String state via into[State]") {
    val request = SystemOneRequest(
      "Help! My payouts have been failing for 3 days.",
      (isUrgent = Question.noul("Does this convey urgency?"))
    )
    assertEquals(
      Encoder[SystemOneRequest[?]].apply(request),
      json(s"""
        |{
        |  "state": { "type": "text", "value": "Help! My payouts have been failing for 3 days." },
        |  "questions": {
        |    "isUrgent": {
        |      "type": "noul",
        |      "instructions": { "type": "text", "value": "Does this convey urgency?" }
        |    }
        |  },
        |  "model": "${Model.jevLatest}"
        |}
      """.stripMargin)
    )
  }

  test("SystemOneRequest apply accepts chat state") {
    val request = SystemOneRequest(
      State.chat(
        ChatMessage("customer", "I was charged twice."),
        ChatMessage("support", "Checking the charges.")
      ),
      (refundRequested = Question.noul("Does the customer request a refund?"))
    )
    assertEquals(
      Encoder[SystemOneRequest[?]].apply(request),
      json(s"""
        |{
        |  "state": {
        |    "type": "chat",
        |    "messages": [
        |      { "from": "customer", "text": "I was charged twice." },
        |      { "from": "support", "text": "Checking the charges." }
        |    ]
        |  },
        |  "questions": {
        |    "refundRequested": {
        |      "type": "noul",
        |      "instructions": { "type": "text", "value": "Does the customer request a refund?" }
        |    }
        |  },
        |  "model": "${Model.jevLatest}"
        |}
      """.stripMargin)
    )
  }

  test("Question.noul accepts a case class encoded via into[Instructions]") {
    case class Prompt(question: String) derives io.circe.Codec.AsObject
    assertEquals(
      Encoder[Question].apply(Question.noul(Prompt("Does this convey urgency?"))),
      json("""
        |{
        |  "type": "noul",
        |  "instructions": {
        |    "type": "structured",
        |    "value": { "question": "Does this convey urgency?" }
        |  }
        |}
      """.stripMargin)
    )
  }

  test("systemOne returns typed answers selectable by question id") {
    val responseJson =
      """
      |{
      |  "model": "jev-1.13.0",
      |  "answers": {
      |    "isUrgent": {
      |      "type": "noul",
      |      "noul": 0.92
      |    },
      |    "department": {
      |      "type": "choice",
      |      "choice": "technical",
      |      "probabilities": { "billing": 0.08, "technical": 0.85, "sales": 0.07 },
      |      "confidence": 0.82
      |    },
      |    "frustration": {
      |      "type": "score",
      |      "score": 1.6,
      |      "legend": { "0": "Calm", "1": "Frustrated", "2": "Very angry" },
      |      "probabilities": { "0": 0.05, "1": 0.3, "2": 0.65 },
      |      "confidence": 0.78
      |    }
      |  },
      |  "usage": { "input_tokens": 312, "output_tokens": 48 }
      |}
      |""".stripMargin

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(parse(responseJson).toOption.get))
    val client     = createTestClient(stubClient)
    val request    = SystemOneRequest(
      "Help! My payouts have been failing for 3 days.",
      (
        isUrgent = Question.noul("Does this convey urgency?"),
        department = Question.choice(
          "Which team?",
          (billing = "Payments", technical = "Bugs", sales = "Sales")
        ),
        frustration = Question.score("How frustrated?", ("Calm", "Frustrated", "Very angry"))
      )
    )

    client.systemOne(request).map { response =>
      assertEquals(response.model, "jev-1.13.0")
      assertEquals(response.usage, Usage(Some(312), Some(48)))

      val noul = response.answers.isUrgent.toOption.get
      assertEquals(noul.noul, 0.92)

      val department = response.answers.department.toOption.get
      assertEquals(department.selected, "technical")
      assert(department.technical)
      assert(!department.billing)
      assert(!department.sales)
      assertEquals(department.probability("technical"), Some(0.85))
      val selected   = department.choice.getOrElse(fail("expected a known choice option"))
      assertEquals(
        selected match
          case "billing" | "technical" | "sales" => selected
        ,
        "technical"
      )

      val frustration = response.answers.frustration.toOption.get
      assertEquals(frustration.score, 1.6)
      assertEquals(frustration.probability(2), Some(0.65))
      val level       = frustration.level.getOrElse(fail("expected a known score level"))
      assertEquals(
        level match
          case 0 | 1 | 2 => level
        ,
        2
      )
    }
  }

  test("Answers selectDynamic returns AnswerOptionUnknown for unexpected choice") {
    val responseJson =
      """
      |{
      |  "model": "jev-latest",
      |  "answers": {
      |    "department": {
      |      "type": "choice",
      |      "choice": "other",
      |      "probabilities": { "billing": 0.1, "technical": 0.1, "other": 0.8 },
      |      "confidence": 0.5
      |    }
      |  },
      |  "usage": {}
      |}
      |""".stripMargin

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(parse(responseJson).toOption.get))
    val client     = createTestClient(stubClient)
    val request    = SystemOneRequest(
      "state",
      (department = Question.choice("Which?", (billing = "Payments", technical = "Bugs")))
    )

    client.systemOne(request).map { response =>
      assertEquals(
        response.answers.department,
        Left(Client.Error.AnswerOptionUnknown("department", "other", List("billing", "technical")))
      )
    }
  }

  test("SystemOneResponse raw decoder accepts usage with missing fields") {
    val json =
      """
      |{
      |  "model": "jev-latest",
      |  "answers": {
      |    "isUrgent": { "type": "noul", "noul": 0.5 }
      |  },
      |  "usage": {}
      |}
      |""".stripMargin

    assertEquals(
      parse(json).flatMap(_.as(using SystemOneResponse.decodeRaw)),
      Right(("jev-latest", Map("isUrgent" -> Answer.Noul(0.5)), Usage()))
    )
  }

  test("accessors return AnswerMissing and AnswerTypeMismatch") {
    val response = SystemOneResponse(
      model = "jev-latest",
      answers = Map("isUrgent" -> Answer.Noul(0.9)),
      usage = Usage(),
      questions = Map("isUrgent" -> Question.noul("yes?"))
    )

    assertEquals(response.noul("isUrgent"), Right[Client.Error, Answer.Noul](Answer.Noul(0.9)))
    assertEquals(response.noul("missing"), Left(Client.Error.AnswerMissing("missing")))
    assertEquals(response.choice("isUrgent"), Left(Client.Error.AnswerTypeMismatch("isUrgent", "choice", "noul")))
    assertEquals(response.score("isUrgent"), Left(Client.Error.AnswerTypeMismatch("isUrgent", "score", "noul")))
  }

  test("score level is the most likely level, not the rounded score") {
    val frustration = Answer.Score[3](0.85, Map.empty, Map(0 -> 0.4, 1 -> 0.35, 2 -> 0.25), 0.6)

    assert(frustration.level.contains(0))
  }

  test("compileErrors reject option flags that are not declared by the question") {
    val department = Answer.Choice[("billing", "technical")]("billing", Map("billing" -> 1.0), 1.0)

    assert(department.billing)
    assert(compileErrors("""department.marketing""").nonEmpty)
    assert(
      compileErrors("""Question.choice("q", (selected = "x", other = "y"))""")
        .contains("choice option labels are exposed as fields on the answer and must not shadow its members")
    )
  }

  test("compileErrors reject invalid noul labels, empty score, empty choice, and non-Question fields") {
    assert(
      compileErrors("""Question.noul("q", (maybe = "x"))""").nonEmpty
    )
    assert(
      compileErrors("""Question.score("q", ("only"))""").nonEmpty
    )
    assert(
      compileErrors("""Question.choice("q", NamedTuple.Empty)""").nonEmpty
    )
    assert(
      compileErrors("""SystemOneRequest("state", (isUrgent = "not a question"))""")
        .contains("All named-tuple fields of SystemOneRequest must be Question instances")
    )
  }

  test("listModels decodes models with release_date") {
    val responseJson =
      """
      |{
      |  "models": [
      |    {
      |      "name": "jev-latest",
      |      "description": "The most recent stable release",
      |      "release_date": "2026-09-15"
      |    }
      |  ]
      |}
      |""".stripMargin

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(parse(responseJson).toOption.get))
    val client     = createTestClient(stubClient)

    client.listModels().map { response =>
      assertEquals(
        response,
        ListModelsResponse(List(ModelMetadata("jev-latest", "The most recent stable release", "2026-09-15")))
      )
    }
  }

  test("should raise BadRequest for 400 status") {
    expectError(Status.BadRequest, "Invalid request", Client.Error.BadRequest("Invalid request", _, _))
  }

  test("should raise Unauthorized for 401 status") {
    expectError(Status.Unauthorized, "Invalid API key", Client.Error.Unauthorized("Invalid API key", _, _))
  }

  test("should raise PermissionDenied for 403 status") {
    expectError(Status.Forbidden, "Forbidden", Client.Error.PermissionDenied("Forbidden", _, _))
  }

  test("should raise UnprocessableEntity for 422 status") {
    expectError(Status.UnprocessableEntity, "Malformed question", Client.Error.UnprocessableEntity("Malformed question", _, _))
  }

  test("should raise RateLimited for 429 status and parse Retry-After") {
    val body       = Json.obj("message" -> Json.fromString("Rate limit exceeded"))
    val stubClient = createStubClient(
      Response[IO](Status.TooManyRequests)
        .withHeaders(`Retry-After`.unsafeFromLong(60))
        .withEntity(body)
    )
    val client  = createTestClient(stubClient)
    val request = SystemOneRequest.dynamic("state", "q" -> Question.noul("yes?"))

    client.systemOne(request).attempt.map { result =>
      assert(result.isLeft)
      result.left.map {
        case err: Client.Error.RateLimited =>
          assertEquals(err.message, "Rate limit exceeded")
          assertEquals(err.retryAfter, Some(60.seconds))
          assert(err.getMessage.contains("Rate Limited: Rate limit exceeded"))
        case other =>
          fail(s"Expected RateLimited, got $other")
      }
    }
  }

  test("should raise Overloaded for 529 status") {
    val overloaded = Status.fromInt(529).toOption.get
    expectError(overloaded, "Temporarily overloaded", Client.Error.Overloaded("Temporarily overloaded", _, _))
  }

  test("should raise InternalServer for 500 status") {
    expectError(Status.InternalServerError, "Internal server error", Client.Error.InternalServer("Internal server error", _, _))
  }

  test("should raise Unexpected for unmapped status") {
    val body       = Json.obj("message" -> Json.fromString("I'm a teapot"))
    val stubClient = createStubClient(Response[IO](Status.ImATeapot).withEntity(body))
    val client     = createTestClient(stubClient)
    val request    = SystemOneRequest.dynamic("state", "q" -> Question.noul("yes?"))

    client.systemOne(request).attempt.map { result =>
      assert(result.isLeft)
      result.left.map {
        case err: Client.Error.Unexpected =>
          assertEquals(err.status, 418)
          assertEquals(err.message, "I'm a teapot")
          assert(err.getMessage.contains("Unexpected Error (status 418)"))
        case other =>
          fail(s"Expected Unexpected, got $other")
      }
    }
  }

  test("Error Show includes request id when present") {
    val err = Client.Error.Unauthorized("Invalid API key", none, "req-123".some)
    assertEquals(err.getMessage, "Unauthorized: Invalid API key (request-id: req-123)")
  }

  private def expectError(
      status: Status,
      message: String,
      expected: (Option[Json], Option[String]) => Client.Error
  ): IO[Unit] =
    val body       = Json.obj("message" -> Json.fromString(message))
    val stubClient = createStubClient(Response[IO](status).withEntity(body))
    val client     = createTestClient(stubClient)
    val request    = SystemOneRequest.dynamic("state", "q" -> Question.noul("yes?"))

    client.systemOne(request).attempt.map { result =>
      assert(result.isLeft)
      result.left.foreach { error =>
        assertEquals(error, expected(body.some, none))
        assert(error.getMessage.nonEmpty)
      }
    }

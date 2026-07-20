package io.github.serhiip.constellations

import java.time.OffsetDateTime
import java.util.UUID

import java.util.concurrent.atomic.AtomicInteger

import cats.MonadThrow
import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNec
import cats.effect.IO
import cats.syntax.all.*
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.dispatcher.{Decoder, ValueEncoder}
import io.github.serhiip.constellations.schema.ToSchema
import munit.CatsEffectSuite

final case class Ping(value: String) derives ValueEncoder

trait PingApi[F[_]]:
  def ping(): F[Ping]

trait ScheduleApi[F[_]]:
  def at(when: OffsetDateTime): F[String]

/** Documented payload for tools. */
final case class DocumentedPayload(
    /** Field from docstring. */
    label: String,
    @llmHint(description = Some("Hinted count"), minimum = Some(0.0))
    count: Int
)

trait DocumentedApi[F[_]]:
  def submit(payload: DocumentedPayload): F[String]

final case class CustomId(value: String)
object CustomId:
  given ToSchema[CustomId] = ToSchema.instance(Schema.string(format = Some("uuid"), description = Some("custom id")))
  given Decoder[Value, CustomId] with
    def decode(value: Value, path: String): ValidatedNec[Decoder.Error, CustomId] =
      value match
        case Value.StringValue(s) => CustomId(s).validNec
        case other                =>
          Decoder.Error.WrongType(path, "String", other.getClass.getSimpleName).invalidNec

trait CustomIdApi[F[_]]:
  def lookup(id: CustomId): F[String]

trait TestApi[F[_]]:
  def mixedTypes(intVal: Int, strVal: String, boolVal: Boolean): F[String]
  def noParams(): F[String]
  def optionalParam(a: Int, b: Option[String]): F[String]
  def nestedStruct(person: Person): F[Struct]
  def listParam(items: List[String]): F[List[String]]
  def uuidValue(): F[UUID]
  def offsetDateTimeValue(): F[OffsetDateTime]

case class Person(name: String, age: Int, active: Boolean)

class TestApiImpl extends TestApi[IO]:
  val uuid: UUID                     = UUID.fromString("d684b6c2-6c1e-4f84-98f1-5f3ef06435c5")
  val offsetDateTime: OffsetDateTime =
    OffsetDateTime.parse("2025-01-20T12:34:56.123+02:00")

  private def createResponse(value: String): IO[String] = IO.pure(value)

  def mixedTypes(intVal: Int, strVal: String, boolVal: Boolean): IO[String] =
    createResponse(s"int=$intVal, str=$strVal, bool=$boolVal")

  def noParams(): IO[String] =
    createResponse("no params")

  def optionalParam(a: Int, b: Option[String]): IO[String] =
    createResponse(s"a=$a, b=${b.getOrElse("none")}")

  def nestedStruct(person: Person): IO[Struct] =
    IO.pure(
      Struct(
        "name"   -> Value.string(person.name),
        "age"    -> Value.number(person.age),
        "active" -> Value.bool(person.active)
      )
    )

  def listParam(items: List[String]): IO[List[String]] =
    IO.pure(items)

  def uuidValue(): IO[UUID] =
    IO.pure(uuid)

  def offsetDateTimeValue(): IO[OffsetDateTime] =
    IO.pure(offsetDateTime)

trait GreetingApi[F[_]]:
  def greet(name: String): F[String]

class GreetingApiStub extends GreetingApi[IO]:
  def greet(name: String): IO[String] =
    IO.pure(s"Hello, $name")

class ToolDispatcherTest extends CatsEffectSuite:

  private def convertValue(value: Any): Value = value match
    case s: String    => Value.string(s)
    case i: Int       => Value.number(i)
    case d: Double    => Value.number(d)
    case b: Boolean   => Value.bool(b)
    case p: Person    =>
      Value.struct(
        Struct(
          Map(
            "name"   -> Value.string(p.name),
            "age"    -> Value.number(p.age),
            "active" -> Value.bool(p.active)
          )
        )
      )
    case l: List[?]   =>
      // Safe to cast since we know it's List[String] from our test context
      Value.list(l.asInstanceOf[List[String]].map(Value.string))
    case m: Map[?, ?] =>
      // Convert Map to Struct by converting all values
      val structFields = m.asInstanceOf[Map[String, Any]].map { case (k, v) => k -> convertValue(v) }
      Value.struct(Struct(structFields))
    case other        => Value.string(other.toString)

  private def createFunctionCall(name: String, args: Map[String, Any] = Map.empty): FunctionCall =
    val structFields = args.map { case (key, value) => key -> convertValue(value) }
    FunctionCall(name, Struct(structFields))

  private def extractResponseStruct(response: ToolDispatcher.Result): Struct =
    response match
      case ToolDispatcher.Result.Response(result) => result.response
      case ToolDispatcher.Result.HumanInTheLoop   => throw new RuntimeException("Unexpected HumanInTheLoop result")

  private def extractString(response: ToolDispatcher.Result): String =
    extractResponseStruct(response).fields("value") match
      case Value.StringValue(s) => s
      case other                => throw new RuntimeException(s"Unexpected response format: $other")

  val impl = new TestApiImpl

  val dispatcher      = ToolDispatcher.generate[IO](impl)
  val typedDispatcher = ToolDispatcher.to[IO, TestApi](impl)
  val greeting        = new GreetingApiStub
  val multiDispatcher = ToolDispatcher.generate[IO](impl, greeting)

  test("dispatcher should successfully call a method with various parameter types") {
    val call = createFunctionCall("TestApi_mixed_types", Map("int_val" -> 42, "str_val" -> "test", "bool_val" -> true))
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "int=42, str=test, bool=true"))
  }

  test("dispatcher should successfully call a method with no parameters") {
    val call = createFunctionCall("TestApi_no_params")
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "no params"))
  }

  test("dispatcher should keep FunctionCall on successful FunctionResponse") {
    val call = FunctionCall("TestApi_no_params", Struct.empty, "call-42".some)
    dispatcher.dispatch(call).map {
      case ToolDispatcher.Result.Response(result) =>
        assertEquals(result.call, call)
        assertEquals(result.response.fields("value"), Value.string("no params"))
      case ToolDispatcher.Result.HumanInTheLoop   =>
        fail("Unexpected HumanInTheLoop")
    }
  }

  test("dispatcher should attach callId to ArgumentDecodingFailed") {
    val call = FunctionCall(
      "TestApi_mixed_types",
      Struct("str_val" -> Value.string("test"), "bool_val" -> Value.bool(true)),
      "call-err".some
    )
    dispatcher.dispatch(call).attempt.map {
      case Left(err: AgentError.ArgumentDecodingFailed) =>
        assertEquals(err.call, call)
      case other                                        => fail(s"Expected ArgumentDecodingFailed, got $other")
    }
  }

  test("dispatcher should handle an optional parameter when it's present") {
    val call = createFunctionCall("TestApi_optional_param", Map("a" -> 1, "b" -> "hello"))
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "a=1, b=hello"))
  }

  test("dispatcher should handle an optional parameter when it's missing") {
    val call = createFunctionCall("TestApi_optional_param", Map("a" -> 1))
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "a=1, b=none"))
  }

  test("dispatcher should handle nested case class parameters") {
    val person = Person("John", 30, true)
    val call   = createFunctionCall("TestApi_nested_struct", Map("person" -> person))
    dispatcher.dispatch(call).map { response =>
      val fields = extractResponseStruct(response).fields
      assertEquals(fields("name"), Value.string(person.name))
      assertEquals(fields("age"), Value.number(person.age))
      assertEquals(fields("active"), Value.bool(person.active))
    }
  }

  test("dispatcher should handle list parameters") {
    val items = List("apple", "banana", "cherry")
    val call  = createFunctionCall("TestApi_list_param", Map("items" -> items))
    dispatcher.dispatch(call).map { response =>
      extractResponseStruct(response).fields("value") match
        case Value.ListValue(values) => assertEquals(values, items.map(Value.string))
        case other                   => throw new RuntimeException(s"Unexpected response format: $other")
    }
  }

  test("dispatcher should encode UUID results as strings") {
    val call = createFunctionCall("TestApi_uuid_value")
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), impl.uuid.toString))
  }

  test("dispatcher should encode OffsetDateTime results as strings") {
    val call = createFunctionCall("TestApi_offset_date_time_value")
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), impl.offsetDateTime.toString))
  }

  test("dispatcher should raise UnknownFunction for an unknown method") {
    val call = createFunctionCall("TestApi_unknown_method")
    dispatcher.dispatch(call).attempt.map {
      case Left(err: AgentError.UnknownFunction) =>
        assertEquals(err.call, call)
        assertEquals(err.getMessage, "No handler for TestApi_unknown_method")
      case other                                 => fail(s"Expected AgentError.UnknownFunction, got $other")
    }
  }

  test("dispatchAll should return Valid results in call order") {
    val calls = List(
      createFunctionCall("TestApi_no_params"),
      createFunctionCall("TestApi_mixed_types", Map("int_val" -> 1, "str_val" -> "a", "bool_val" -> false))
    )
    dispatcher.dispatchAll(calls).map {
      case Valid(results) =>
        assertEquals(results.map(extractString), List("no params", "int=1, str=a, bool=false"))
      case Invalid(errs)  => fail(s"Expected Valid, got Invalid($errs)")
    }
  }

  test("dispatchAll should not execute any call when one call is invalid") {
    val executions        = AtomicInteger(0)
    val recordingGreeting = new GreetingApi[IO]:
      def greet(name: String): IO[String] =
        IO.delay(executions.incrementAndGet()) *> IO.pure(s"Hello, $name")
    val batchDispatcher   = ToolDispatcher.generate[IO](impl, recordingGreeting)
    val calls             = List(
      createFunctionCall("GreetingApi_greet", Map("name" -> "Ada")),
      createFunctionCall("TestApi_mixed_types", Map("str_val" -> "test"))
    )
    batchDispatcher.dispatchAll(calls).map {
      case Invalid(errs)  =>
        assertEquals(executions.get(), 0)
        assert(errs.exists {
          case AgentError.ArgumentDecodingFailed(call, _) => call.name == "TestApi_mixed_types"
          case _                                          => false
        })
      case Valid(results) => fail(s"Expected Invalid, got Valid($results)")
    }
  }

  test("dispatchAll should aggregate multiple AgentErrors") {
    val calls = List(
      createFunctionCall("TestApi_unknown_method"),
      createFunctionCall("TestApi_mixed_types", Map("str_val" -> "test"))
    )
    dispatcher.dispatchAll(calls).map {
      case Invalid(errs)  =>
        assertEquals(errs.length, 2L)
        assert(errs.exists {
          case AgentError.UnknownFunction(call) => call.name == "TestApi_unknown_method"
          case _                                => false
        })
        assert(errs.exists {
          case AgentError.ArgumentDecodingFailed(call, _) => call.name == "TestApi_mixed_types"
          case _                                          => false
        })
      case Valid(results) => fail(s"Expected Invalid, got Valid($results)")
    }
  }

  test("dispatcher should report a single missing required parameter") {
    val call = createFunctionCall("TestApi_mixed_types", Map("str_val" -> "test", "bool_val" -> true))
    dispatcher.dispatch(call).attempt.map {
      case Left(err: AgentError.ArgumentDecodingFailed) =>
        assertEquals(
          err.getMessage,
          "Failed to decode arguments for method 'TestApi_mixed_types': Error at path 'int_val': Field is missing."
        )
      case other                                        => fail(s"Expected AgentError.ArgumentDecodingFailed, got $other")
    }
  }

  test("dispatcher should accumulate and report multiple decoding errors") {
    val call = createFunctionCall("TestApi_mixed_types", Map("bool_val" -> "not-a-bool"))
    dispatcher.dispatch(call).attempt.map {
      case Left(err: AgentError.ArgumentDecodingFailed) =>
        assertEquals(
          err.getMessage,
          "Failed to decode arguments for method 'TestApi_mixed_types': Error at path 'int_val': Field is missing., Error at path 'str_val': Field is missing., Error at path 'bool_val': Expected type Boolean, but got StringValue."
        )
      case other                                        => fail(s"Expected AgentError.ArgumentDecodingFailed, got $other")
    }
  }

  test("dispatcher should ignore extra parameters in the function call") {
    val call = createFunctionCall(
      "TestApi_mixed_types",
      Map(
        "int_val"  -> 42,
        "str_val"  -> "test",
        "bool_val" -> true,
        "extra"    -> "ignored"
      )
    )
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "int=42, str=test, bool=true"))
  }

  test("dispatcher should handle wrong type for nested struct") {
    val call = createFunctionCall("TestApi_nested_struct", Map("person" -> "not a person"))
    dispatcher.dispatch(call).attempt.map {
      case Left(err: AgentError.ArgumentDecodingFailed) =>
        assert(err.getMessage.contains("Expected type Struct, but got StringValue"))
      case other                                        => fail(s"Expected AgentError.ArgumentDecodingFailed, got $other")
    }
  }

  test("dispatcher should handle missing field in nested struct") {
    val personStruct = Map(
      "name"   -> "John",
      "active" -> true
    )
    val call         = createFunctionCall("TestApi_nested_struct", Map("person" -> personStruct))
    dispatcher.dispatch(call).attempt.map {
      case Left(err: AgentError.ArgumentDecodingFailed) =>
        assert(err.getMessage.contains("Error at path 'person.age': Field is missing"))
      case other                                        => fail(s"Expected AgentError.ArgumentDecodingFailed, got $other")
    }
  }

  test("dispatcher should support multiple components") {
    val testCall     = createFunctionCall("TestApi_no_params")
    val greetingCall = createFunctionCall("GreetingApi_greet", Map("name" -> "Ada"))
    for
      testResponse     <- multiDispatcher.dispatch(testCall)
      greetingResponse <- multiDispatcher.dispatch(greetingCall)
    yield
      assertEquals(extractString(testResponse), "no params")
      assertEquals(extractString(greetingResponse), "Hello, Ada")
  }

  test("combine should route calls across dispatchers") {
    val testOnly     = ToolDispatcher.generate[IO](impl)
    val greetingOnly = ToolDispatcher.generate[IO](greeting)
    val testCall     = createFunctionCall("TestApi_no_params")
    val greetingCall = createFunctionCall("GreetingApi_greet", Map("name" -> "Ada"))
    for
      merged           <- ToolDispatcher.combine(testOnly, greetingOnly)
      testResponse     <- merged.dispatch(testCall)
      greetingResponse <- merged.dispatch(greetingCall)
      names            <- merged.getFunctionDeclarations.map(_.map(_.name).toSet)
    yield
      assertEquals(extractString(testResponse), "no params")
      assertEquals(extractString(greetingResponse), "Hello, Ada")
      assert(names.contains("TestApi_no_params"))
      assert(names.contains("GreetingApi_greet"))
  }

  test("combine should prefer the leftmost dispatcher on name clashes") {
    val leftGreeting  = new GreetingApi[IO]:
      def greet(name: String): IO[String] = IO.pure(s"left:$name")
    val rightGreeting = new GreetingApi[IO]:
      def greet(name: String): IO[String] = IO.pure(s"right:$name")
    val left          = ToolDispatcher.generate[IO](leftGreeting)
    val right         = ToolDispatcher.generate[IO](rightGreeting)
    val call          = createFunctionCall("GreetingApi_greet", Map("name" -> "Ada"))
    for
      merged <- ToolDispatcher.combine(left, right)
      result <- merged.dispatch(call)
      decls  <- merged.getFunctionDeclarations
    yield
      assertEquals(extractString(result), "left:Ada")
      assertEquals(decls.count(_.name == "GreetingApi_greet"), 1)
  }

  test("combine should fail for an unknown method") {
    val testOnly     = ToolDispatcher.generate[IO](impl)
    val greetingOnly = ToolDispatcher.generate[IO](greeting)
    val call         = createFunctionCall("Unknown_tool")
    for
      merged  <- ToolDispatcher.combine(testOnly, greetingOnly)
      attempt <- merged.dispatch(call).attempt
    yield attempt match
      case Left(err: AgentError.UnknownFunction) =>
        assertEquals(err.call, call)
        assertEquals(err.getMessage, "No handler for Unknown_tool")
      case other                                 => fail(s"Expected AgentError.UnknownFunction, got $other")
  }

  test("dispatcher should accept OffsetDateTime parameters") {
    val when               = OffsetDateTime.parse("2025-01-20T12:34:56.123+02:00")
    val api                = new ScheduleApi[IO]:
      def at(when: OffsetDateTime): IO[String] = IO.pure(when.toString)
    val scheduleDispatcher = ToolDispatcher.generate[IO](api)
    val call               = createFunctionCall("ScheduleApi_at", Map("when" -> when.toString))
    for
      result <- scheduleDispatcher.dispatch(call)
      decls  <- scheduleDispatcher.getFunctionDeclarations
    yield
      assertEquals(extractString(result), when.toString)
      assertEquals(
        decls.find(_.name == "ScheduleApi_at").flatMap(_.parameters),
        Some(
          Schema.obj(
            properties = Map("when" -> Schema.string(format = Some("date-time"))),
            required = List("when")
          )
        )
      )
  }

  test("dispatcher should raise ArgumentDecodingFailed for invalid OffsetDateTime strings") {
    val api                = new ScheduleApi[IO]:
      def at(when: OffsetDateTime): IO[String] = IO.pure(when.toString)
    val scheduleDispatcher = ToolDispatcher.generate[IO](api)
    val call               = createFunctionCall("ScheduleApi_at", Map("when" -> "not-a-timestamp"))
    scheduleDispatcher.dispatch(call).attempt.map {
      case Left(err: AgentError.ArgumentDecodingFailed) =>
        assertEquals(err.call, call)
        assert(
          err.errors.exists {
            case Decoder.Error.InvalidStringValue("when", "not-a-timestamp", "OffsetDateTime", Some(_)) => true
            case _                                                                                      => false
          }
        )
        assert(err.getMessage.contains("Cannot parse 'not-a-timestamp' into OffsetDateTime"))
      case other                                        => fail(s"Expected AgentError.ArgumentDecodingFailed, got $other")
    }
  }

  test("generate should expand when F is an abstract type parameter") {
    def make[F[_]: MonadThrow](api: PingApi[F]): ToolDispatcher[F] =
      ToolDispatcher.generate[F](api)

    val api = new PingApi[IO]:
      def ping(): IO[Ping] = Ping("pong").pure[IO]

    val polymorphic = make(api)
    val call        = createFunctionCall("PingApi_ping")
    for
      result <- polymorphic.dispatch(call)
      decls  <- polymorphic.getFunctionDeclarations
    yield
      assertEquals(extractString(result), "pong")
      assert(decls.exists(_.name == "PingApi_ping"))
  }

  test("dispatcher to should dispatch for single trait") {
    val call = createFunctionCall("TestApi_no_params")
    typedDispatcher.dispatch(call).map(response => assertEquals(extractString(response), "no params"))
  }

  test("dispatcher to should match generate declarations") {
    for
      typedDeclsRaw <- typedDispatcher.getFunctionDeclarations
      typedDecls     = typedDeclsRaw.sortBy(_.name)
      generatedRaw  <- dispatcher.getFunctionDeclarations
      generated      = generatedRaw.sortBy(_.name)
    yield assertEquals(typedDecls, generated)
  }

  test("generate should expand when spread is used on a list returned by apply") {
    def make[F[_]: MonadThrow]: PingApi[F] =
      new PingApi[F]:
        def ping(): F[Ping] = Ping("pong").pure[F]

    object A:
      def apply(t: Int) = List.fill(t)(make[IO])

    val polymorphic = ToolDispatcher.generate[IO](make[IO], A.apply(1)*)
    val call        = createFunctionCall("PingApi_ping")
    for
      result <- polymorphic.dispatch(call)
      decls  <- polymorphic.getFunctionDeclarations
    yield
      assertEquals(extractString(result), "pong")
      assert(decls.exists(_.name == "PingApi_ping"))
  }

  test("dispatcher to should dispatch for single trait") {
    val call = createFunctionCall("TestApi_no_params")
    typedDispatcher.dispatch(call).map(response => assertEquals(extractString(response), "no params"))
  }

  test("dispatcher to should match generate declarations") {
    for
      typedDeclsRaw <- typedDispatcher.getFunctionDeclarations
      typedDecls     = typedDeclsRaw.sortBy(_.name)
      generatedRaw  <- dispatcher.getFunctionDeclarations
      generated      = generatedRaw.sortBy(_.name)
    yield assertEquals(typedDecls, generated)
  }

  test("getFunctionDeclarations should include multiple components") {
    multiDispatcher.getFunctionDeclarations.map { declarations =>
      val names = declarations.map(_.name).toSet
      assert(names.contains("TestApi_no_params"))
      assert(names.contains("GreetingApi_greet"))
    }
  }

  test("getFunctionDeclarations should return correct function declarations") {
    val declarations = dispatcher.getFunctionDeclarations.map(_.sortBy(_.name))

    val int32Schema  = ToSchema[Int].schema
    val personSchema = Schema.obj(
      properties = Map(
        "name"   -> Schema.string(),
        "age"    -> int32Schema,
        "active" -> Schema.boolean()
      ),
      required = List("name", "age", "active")
    )

    val expectedDeclarations = List(
      FunctionDeclaration(
        "TestApi_list_param",
        None,
        Some(
          Schema.obj(
            properties = Map("items" -> Schema.array(items = Schema.string())),
            required = List("items")
          )
        )
      ),
      FunctionDeclaration(
        "TestApi_mixed_types",
        None,
        Some(
          Schema.obj(
            properties = Map(
              "int_val"  -> int32Schema,
              "str_val"  -> Schema.string(),
              "bool_val" -> Schema.boolean()
            ),
            required = List("int_val", "str_val", "bool_val")
          )
        )
      ),
      FunctionDeclaration(
        "TestApi_nested_struct",
        None,
        Some(
          Schema.obj(
            properties = Map("person" -> personSchema),
            required = List("person")
          )
        )
      ),
      FunctionDeclaration("TestApi_no_params", None, None),
      FunctionDeclaration(
        "TestApi_optional_param",
        None,
        Some(
          Schema.obj(
            properties = Map(
              "a" -> int32Schema,
              "b" -> Schema.string().copy(nullable = Some(true))
            ),
            required = List("a")
          )
        )
      ),
      FunctionDeclaration("TestApi_offset_date_time_value", None, None),
      FunctionDeclaration("TestApi_uuid_value", None, None)
    ).sortBy(_.name)

    declarations.map(decls => assertEquals(decls, expectedDeclarations))
  }

  private def assertMergedTestAndGreeting(dispatcher: ToolDispatcher[IO]): IO[Unit] =
    val testCall     = createFunctionCall("TestApi_no_params")
    val greetingCall = createFunctionCall("GreetingApi_greet", Map("name" -> "Ada"))
    for
      testResponse     <- dispatcher.dispatch(testCall)
      greetingResponse <- dispatcher.dispatch(greetingCall)
      names            <- dispatcher.getFunctionDeclarations.map(_.map(_.name).toSet)
    yield
      assertEquals(extractString(testResponse), "no params")
      assertEquals(extractString(greetingResponse), "Hello, Ada")
      assert(names.contains("TestApi_no_params"))
      assert(names.contains("GreetingApi_greet"))

  test("generate Varargs: explicit trailing components are merged into one dispatcher") {
    // `generate(a, b)` — optionalOtherComponents matches Varargs(Seq(b))
    assertMergedTestAndGreeting(ToolDispatcher.generate[IO](impl, greeting))
  }

  test("generate Varargs: empty optional list still builds a single-component dispatcher") {
    // `generate(a)` — optionalOtherComponents matches Varargs(Nil)
    val dispatcher = ToolDispatcher.generate[IO](impl)
    val call       = createFunctionCall("TestApi_no_params")
    for
      response <- dispatcher.dispatch(call)
      names    <- dispatcher.getFunctionDeclarations.map(_.map(_.name).toSet)
    yield
      assertEquals(extractString(response), "no params")
      assert(names.contains("TestApi_no_params"))
      assert(!names.contains("GreetingApi_greet"))
  }

  test("generate collection spread: List(component)* literal is accepted") {
    // Not Varargs; the spread element type (GreetingApiStub) resolves the trait, elements combined at runtime
    assertMergedTestAndGreeting(ToolDispatcher.generate[IO](impl, List(greeting)*))
  }

  test("generate collection spread: Seq(component)* literal is accepted") {
    assertMergedTestAndGreeting(ToolDispatcher.generate[IO](impl, Seq(greeting)*))
  }

  test("generate collection spread: a typed Seq variable is spread at runtime") {
    val extras = List(greeting)
    assertMergedTestAndGreeting(ToolDispatcher.generate[IO](impl, extras*))
  }

  test("generate collection spread: a Seq[Any] with no trait element type is rejected") {
    // Element type is Any, which is not a trait with the effect type so trait resolution aborts
    val errors = compiletime.testing.typeCheckErrors(
      """
      import cats.effect.IO
      import cats.syntax.all.*
      import io.github.serhiip.constellations.*
      import io.github.serhiip.constellations.dispatcher.ValueEncoder

      final case class ProbePing(value: String) derives ValueEncoder
      trait ProbePingApi[F[_]]:
        def ping(): F[ProbePing]
      trait ProbeGreetApi[F[_]]:
        def greet(name: String): F[String]

      val ping = new ProbePingApi[IO]:
        def ping(): IO[ProbePing] = ProbePing("pong").pure[IO]
      val greet = new ProbeGreetApi[IO]:
        def greet(name: String): IO[String] = s"Hi $name".pure[IO]
      val extras = Seq[Any](greet)
      val _ = ToolDispatcher.generate[IO](ping, extras*)
      """
    )
    assert(
      errors.exists(_.message.contains("must be typed as a trait")),
      clues(errors.map(_.message))
    )
  }

  test("getFunctionDeclarations should flow docstrings and llmHint through nested param schemas") {
    val api                  = new DocumentedApi[IO]:
      def submit(payload: DocumentedPayload): IO[String] = IO.pure(payload.label)
    val documentedDispatcher = ToolDispatcher.generate[IO](api)
    documentedDispatcher.getFunctionDeclarations.map { decls =>
      assertEquals(
        decls.find(_.name == "DocumentedApi_submit").flatMap(_.parameters),
        Some(
          Schema.obj(
            properties = Map(
              "payload" -> Schema.obj(
                description = Some("Documented payload for tools."),
                properties = Map(
                  "label" -> Schema.string(),
                  "count" -> ToSchema[Int].schema.copy(
                    description = Some("Hinted count"),
                    minimum = Some(0.0)
                  )
                ),
                required = List("label", "count")
              )
            ),
            required = List("payload")
          )
        )
      )
    }
  }

  test("getFunctionDeclarations should use a custom ToSchema instance for param types") {
    val api              = new CustomIdApi[IO]:
      def lookup(id: CustomId): IO[String] = IO.pure(id.value)
    val customDispatcher = ToolDispatcher.generate[IO](api)
    customDispatcher.getFunctionDeclarations.map { decls =>
      assertEquals(
        decls.find(_.name == "CustomIdApi_lookup").flatMap(_.parameters),
        Some(
          Schema.obj(
            properties = Map("id" -> Schema.string(format = Some("uuid"), description = Some("custom id"))),
            required = List("id")
          )
        )
      )
    }
  }

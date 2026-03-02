package io.github.serhiip.constellations

import java.time.OffsetDateTime
import java.util.UUID

import scala.annotation.experimental

import cats.effect.IO
import io.github.serhiip.constellations.common.*
import munit.CatsEffectSuite

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

@experimental
class DispatcherTest extends CatsEffectSuite:

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

  private def extractResponseStruct(response: Dispatcher.Result): Struct =
    response match
      case Dispatcher.Result.Response(result) => result.response
      case Dispatcher.Result.HumanInTheLoop   => throw new RuntimeException("Unexpected HumanInTheLoop result")

  private def extractString(response: Dispatcher.Result): String =
    extractResponseStruct(response).fields("value") match
      case Value.StringValue(s) => s
      case other                => throw new RuntimeException(s"Unexpected response format: $other")

  val impl       = new TestApiImpl
  val factory    = Dispatcher.generate[IO, TestApi]
  val dispatcher = factory(impl)

  test("dispatcher should successfully call a method with various parameter types") {
    val call = createFunctionCall("test_api_mixed_types", Map("int_val" -> 42, "str_val" -> "test", "bool_val" -> true))
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "int=42, str=test, bool=true"))
  }

  test("dispatcher should successfully call a method with no parameters") {
    val call = createFunctionCall("test_api_no_params")
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "no params"))
  }

  test("dispatcher should handle an optional parameter when it's present") {
    val call = createFunctionCall("test_api_optional_param", Map("a" -> 1, "b" -> "hello"))
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "a=1, b=hello"))
  }

  test("dispatcher should handle an optional parameter when it's missing") {
    val call = createFunctionCall("test_api_optional_param", Map("a" -> 1))
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "a=1, b=none"))
  }

  test("dispatcher should handle nested case class parameters") {
    val person = Person("John", 30, true)
    val call   = createFunctionCall("test_api_nested_struct", Map("person" -> person))
    dispatcher.dispatch(call).map { response =>
      val fields = extractResponseStruct(response).fields
      assertEquals(fields("name"), Value.string(person.name))
      assertEquals(fields("age"), Value.number(person.age))
      assertEquals(fields("active"), Value.bool(person.active))
    }
  }

  test("dispatcher should handle list parameters") {
    val items = List("apple", "banana", "cherry")
    val call  = createFunctionCall("test_api_list_param", Map("items" -> items))
    dispatcher.dispatch(call).map { response =>
      extractResponseStruct(response).fields("value") match
        case Value.ListValue(values) => assertEquals(values, items.map(Value.string))
        case other                   => throw new RuntimeException(s"Unexpected response format: $other")
    }
  }

  test("dispatcher should encode UUID results as strings") {
    val call = createFunctionCall("test_api_uuid_value")
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), impl.uuid.toString))
  }

  test("dispatcher should encode OffsetDateTime results as strings") {
    val call = createFunctionCall("test_api_offset_date_time_value")
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), impl.offsetDateTime.toString))
  }

  test("dispatcher should throw RuntimeException for an unknown method") {
    val call = createFunctionCall("test_api_unknown_method")
    val ex   = intercept[RuntimeException](dispatcher.dispatch(call)) // TODO: should raise inside IO
    assertEquals(ex.getMessage, "No handler for test_api_unknown_method")
  }

  test("dispatcher should report a single missing required parameter") {
    val call = createFunctionCall("test_api_mixed_types", Map("str_val" -> "test", "bool_val" -> true))
    val ex   = intercept[IllegalArgumentException](dispatcher.dispatch(call)) // TODO: should raise inside IO
    assertEquals(ex.getMessage, "Failed to decode arguments for method 'test_api_mixed_types': Error at path 'int_val': Field is missing.")
  }

  test("dispatcher should accumulate and report multiple decoding errors") {
    val call = createFunctionCall("test_api_mixed_types", Map("bool_val" -> "not-a-bool"))
    val ex   = intercept[IllegalArgumentException](dispatcher.dispatch(call)) // TODO: should raise inside IO
    assertEquals(
      ex.getMessage,
      "Failed to decode arguments for method 'test_api_mixed_types': Error at path 'int_val': Field is missing., Error at path 'str_val': Field is missing., Error at path 'bool_val': Expected type Boolean, but got StringValue."
    )
  }

  test("dispatcher should ignore extra parameters in the function call") {
    val call = createFunctionCall(
      "test_api_mixed_types",
      Map(
        "int_val"  -> 42,
        "str_val"  -> "test",
        "bool_val" -> true,
        "extra"   -> "ignored"
      )
    )
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "int=42, str=test, bool=true"))
  }

  test("dispatcher should handle wrong type for nested struct") {
    val call = createFunctionCall("test_api_nested_struct", Map("person" -> "not a person"))
    val ex   = intercept[IllegalArgumentException](dispatcher.dispatch(call)) // TODO: should raise inside IO
    assert(ex.getMessage.contains("Expected type Struct, but got StringValue"))
  }

  test("dispatcher should handle missing field in nested struct") {
    val personStruct = Map(
      "name"   -> "John",
      "active" -> true
    )
    val call         = createFunctionCall("test_api_nested_struct", Map("person" -> personStruct))
    val ex           = intercept[IllegalArgumentException](dispatcher.dispatch(call)) // TODO: should raise inside IO
    assert(ex.getMessage.contains("Error at path 'person.age': Field is missing"))
  }

  test("getFunctionDeclarations should return correct function declarations") {
    val declarations = dispatcher.getFunctionDeclarations.map(_.sortBy(_.name))

    val personSchema = Schema.obj(
      properties = Map(
        "name"   -> Schema.string(),
        "age"    -> Schema.integer(),
        "active" -> Schema.boolean()
      ),
      required = List("name", "age", "active")
    )

    val expectedDeclarations = List(
      FunctionDeclaration(
        "test_api_list_param",
        None,
        Some(
          Schema.obj(
            properties = Map("items" -> Schema.array(items = Schema.string())),
            required = List("items")
          )
        )
      ),
      FunctionDeclaration(
        "test_api_mixed_types",
        None,
        Some(
          Schema.obj(
            properties = Map(
              "int_val"  -> Schema.integer(),
              "str_val"  -> Schema.string(),
              "bool_val" -> Schema.boolean()
            ),
            required = List("int_val", "str_val", "bool_val")
          )
        )
      ),
      FunctionDeclaration(
        "test_api_nested_struct",
        None,
        Some(
          Schema.obj(
            properties = Map("person" -> personSchema),
            required = List("person")
          )
        )
      ),
      FunctionDeclaration("test_api_no_params", None, None),
      FunctionDeclaration(
        "test_api_optional_param",
        None,
        Some(
          Schema.obj(
            properties = Map(
              "a" -> Schema.integer(),
              "b" -> Schema.string().copy(nullable = Some(true))
            ),
            required = List("a")
          )
        )
      ),
      FunctionDeclaration("test_api_offset_date_time_value", None, None),
      FunctionDeclaration("test_api_uuid_value", None, None)
    ).sortBy(_.name)

    declarations.map(decls => assertEquals(decls, expectedDeclarations))
  }

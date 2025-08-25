package io.github.serhiip.constellations

import scala.annotation.experimental

import cats.effect.IO
import cats.syntax.all.*

import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.dispatcher.Decoder
import munit.CatsEffectSuite

trait TestApi[F[_]]:
  def mixedTypes(intVal: Int, strVal: String, boolVal: Boolean): F[Dispatcher.Result]
  def noParams(): F[Dispatcher.Result]
  def optionalParam(a: Int, b: Option[String]): F[Dispatcher.Result]
  def nestedStruct(person: Person): F[Dispatcher.Result]
  def listParam(items: List[String]): F[Dispatcher.Result]

case class Person(name: String, age: Int, active: Boolean)

class TestApiImpl extends TestApi[IO]:
  private def createResponse(value: Any): IO[Dispatcher.Result] = IO {
    val struct = Struct(Map("value" -> Value.string(value.toString)))
    Dispatcher.Result.Response(FunctionResponse("response", struct))
  }

  def mixedTypes(intVal: Int, strVal: String, boolVal: Boolean): IO[Dispatcher.Result] =
    createResponse(s"int=$intVal, str=$strVal, bool=$boolVal")

  def noParams(): IO[Dispatcher.Result] =
    createResponse("no params")

  def optionalParam(a: Int, b: Option[String]): IO[Dispatcher.Result] =
    createResponse(s"a=$a, b=${b.getOrElse("none")}")

  def nestedStruct(person: Person): IO[Dispatcher.Result] =
    createResponse(s"person=${person.name}, age=${person.age}, active=${person.active}")

  def listParam(items: List[String]): IO[Dispatcher.Result] =
    createResponse(s"items=${items.mkString(",")}")

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
    case _            => Value.string(value.toString)

  private def createFunctionCall(name: String, args: Map[String, Any] = Map.empty): FunctionCall =
    val structFields = args.map { case (key, value) => key -> convertValue(value) }
    FunctionCall(name, Struct(structFields))

  private def extractString(response: Dispatcher.Result): String =
    response match
      case Dispatcher.Result.Response(result) =>
        result.response.fields("value") match
          case Value.StringValue(s) => s
          case _                    => throw new RuntimeException("Unexpected response format")
      case Dispatcher.Result.HumanInTheLoop   =>
        throw new RuntimeException("Unexpected HumanInTheLoop result")

  val impl       = new TestApiImpl
  val factory    = Dispatcher.generate[IO, TestApi]
  val dispatcher = factory(impl)

  test("dispatcher should successfully call a method with various parameter types") {
    val call = createFunctionCall("TestApi_mixedTypes", Map("intVal" -> 42, "strVal" -> "test", "boolVal" -> true))
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "int=42, str=test, bool=true"))
  }

  test("dispatcher should successfully call a method with no parameters") {
    val call = createFunctionCall("TestApi_noParams")
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "no params"))
  }

  test("dispatcher should handle an optional parameter when it's present") {
    val call = createFunctionCall("TestApi_optionalParam", Map("a" -> 1, "b" -> "hello"))
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "a=1, b=hello"))
  }

  test("dispatcher should handle an optional parameter when it's missing") {
    val call = createFunctionCall("TestApi_optionalParam", Map("a" -> 1))
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "a=1, b=none"))
  }

  test("dispatcher should handle nested case class parameters") {
    val person = Person("John", 30, true)
    val call   = createFunctionCall("TestApi_nestedStruct", Map("person" -> person))
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "person=John, age=30, active=true"))
  }

  test("dispatcher should handle list parameters") {
    val items = List("apple", "banana", "cherry")
    val call  = createFunctionCall("TestApi_listParam", Map("items" -> items))
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "items=apple,banana,cherry"))
  }

  test("dispatcher should throw RuntimeException for an unknown method") {
    val call = createFunctionCall("TestApi_unknownMethod")
    val ex   = intercept[RuntimeException](dispatcher.dispatch(call)) // TODO: should raise inside IO
    assertEquals(ex.getMessage, "No handler for TestApi_unknownMethod")
  }

  test("dispatcher should report a single missing required parameter") {
    val call = createFunctionCall("TestApi_mixedTypes", Map("strVal" -> "test", "boolVal" -> true))
    val ex   = intercept[IllegalArgumentException](dispatcher.dispatch(call)) // TODO: should raise inside IO
    assertEquals(ex.getMessage, "Failed to decode arguments for method 'TestApi_mixedTypes': Error at path 'intVal': Field is missing.")
  }

  test("dispatcher should accumulate and report multiple decoding errors") {
    val call = createFunctionCall("TestApi_mixedTypes", Map("boolVal" -> "not-a-bool"))
    val ex   = intercept[IllegalArgumentException](dispatcher.dispatch(call)) // TODO: should raise inside IO
    assertEquals(
      ex.getMessage,
      "Failed to decode arguments for method 'TestApi_mixedTypes': Error at path 'intVal': Field is missing., Error at path 'strVal': Field is missing., Error at path 'boolVal': Expected type Boolean, but got StringValue."
    )
  }

  test("dispatcher should ignore extra parameters in the function call") {
    val call = createFunctionCall(
      "TestApi_mixedTypes",
      Map(
        "intVal"  -> 42,
        "strVal"  -> "test",
        "boolVal" -> true,
        "extra"   -> "ignored"
      )
    )
    dispatcher.dispatch(call).map(response => assertEquals(extractString(response), "int=42, str=test, bool=true"))
  }

  test("dispatcher should handle wrong type for nested struct") {
    val call = createFunctionCall("TestApi_nestedStruct", Map("person" -> "not a person"))
    val ex   = intercept[IllegalArgumentException](dispatcher.dispatch(call)) // TODO: should raise inside IO
    assert(ex.getMessage.contains("Expected type Struct, but got StringValue"))
  }

  test("dispatcher should handle missing field in nested struct") {
    val personStruct = Map(
      "name"   -> "John",
      "active" -> true
    )
    val call         = createFunctionCall("TestApi_nestedStruct", Map("person" -> personStruct))
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
        "TestApi_listParam",
        None,
        Some(
          Schema.obj(
            properties = Map("items" -> Schema.array(items = Schema.string())),
            required = List("items")
          )
        )
      ),
      FunctionDeclaration(
        "TestApi_mixedTypes",
        None,
        Some(
          Schema.obj(
            properties = Map(
              "intVal"  -> Schema.integer(),
              "strVal"  -> Schema.string(),
              "boolVal" -> Schema.boolean()
            ),
            required = List("intVal", "strVal", "boolVal")
          )
        )
      ),
      FunctionDeclaration(
        "TestApi_nestedStruct",
        None,
        Some(
          Schema.obj(
            properties = Map("person" -> personSchema),
            required = List("person")
          )
        )
      ),
      FunctionDeclaration("TestApi_noParams", None, None),
      FunctionDeclaration(
        "TestApi_optionalParam",
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
      )
    ).sortBy(_.name)

    declarations.map(decls => assertEquals(decls, expectedDeclarations))
  }

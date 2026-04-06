package io.github.serhiip.constellations.dispatcher

import java.time.OffsetDateTime
import java.util.UUID

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNec
import io.github.serhiip.constellations.common.{Value, Struct}
import munit.FunSuite

class DecoderTest extends FunSuite:
  import io.github.serhiip.constellations.dispatcher.Decoder.given

  case class Person(name: String, age: Int, active: Boolean)
  case class Address(street: String, city: String, zipCode: String)
  case class User(id: String, person: Person, address: Option[Address], tags: List[String])

  enum Shape:
    case Circle(radius: Double)
    case Rectangle(width: Double, height: Double)
    case Square(side: Double)

  test("Decoder[Value, String] should decode StringValue correctly") {
    val value  = Value.StringValue("hello world")
    val result = Decoder[Value, String].decode(value, "test")
    assertEquals(result, Valid("hello world"))
  }

  test("Decoder[Value, String] should fail for NumberValue") {
    val value  = Value.NumberValue(42.5)
    val result = Decoder[Value, String].decode(value, "test")
    assert(result.isInvalid)
    result match
      case Invalid(errors) =>
        assertEquals(errors.length, 1L)
        assert(errors.head.isInstanceOf[Decoder.Error.WrongType])
      case _               => fail("Expected Invalid result")
  }

  test("Decoder[Value, String] should fail for BoolValue") {
    val value  = Value.BoolValue(true)
    val result = Decoder[Value, String].decode(value, "test")
    assert(result.isInvalid)
  }

  test("Decoder[Value, String] should fail for NullValue") {
    val value  = Value.NullValue
    val result = Decoder[Value, String].decode(value, "test")
    assert(result.isInvalid)
  }

  test("Decoder[Value, Int] should decode NumberValue correctly") {
    val value  = Value.NumberValue(42.0)
    val result = Decoder[Value, Int].decode(value, "test")
    assertEquals(result, Valid(42))
  }

  test("Decoder[Value, Int] should fail for StringValue") {
    val value  = Value.StringValue("123")
    val result = Decoder[Value, Int].decode(value, "test")
    assert(result.isInvalid)
  }

  test("Decoder[Value, Int] should fail for non-integer NumberValue") {
    val value  = Value.NumberValue(42.5)
    val result = Decoder[Value, Int].decode(value, "test")
    assert(result.isInvalid)
  }

  test("Decoder[Value, Int] should fail for out-of-range NumberValue") {
    val value  = Value.NumberValue(Double.MaxValue)
    val result = Decoder[Value, Int].decode(value, "test")
    assert(result.isInvalid)
  }

  test("Decoder[Value, Long] should decode NumberValue correctly") {
    val value  = Value.NumberValue(123456789L.toDouble)
    val result = Decoder[Value, Long].decode(value, "test")
    assertEquals(result, Valid(123456789L))
  }

  test("Decoder[Value, Float] should decode NumberValue correctly") {
    val value  = Value.NumberValue(3.14)
    val result = Decoder[Value, Float].decode(value, "test")
    assertEquals(result, Valid(3.14f))
  }

  test("Decoder[Value, Double] should decode NumberValue correctly") {
    val value  = Value.NumberValue(3.14159)
    val result = Decoder[Value, Double].decode(value, "test")
    assertEquals(result, Valid(3.14159))
  }

  test("Decoder[Value, Boolean] should decode BoolValue correctly") {
    val value  = Value.BoolValue(true)
    val result = Decoder[Value, Boolean].decode(value, "test")
    assertEquals(result, Valid(true))
  }

  test("Decoder[Value, Boolean] should fail for StringValue") {
    val value  = Value.StringValue("true")
    val result = Decoder[Value, Boolean].decode(value, "test")
    assert(result.isInvalid)
  }

  test("Decoder[Value, Boolean] should fail for NumberValue") {
    val value  = Value.NumberValue(1.0)
    val result = Decoder[Value, Boolean].decode(value, "test")
    assert(result.isInvalid)
  }

  test("Decoder[Value, OffsetDateTime] should decode StringValue correctly") {
    val time   = OffsetDateTime.parse("2025-01-20T12:34:56.123+02:00")
    val value  = Value.StringValue(time.toString)
    val result = Decoder[Value, OffsetDateTime].decode(value, "test")
    assertEquals(result, Valid(time))
  }

  test("Decoder[Value, OffsetDateTime] should fail for invalid StringValue") {
    val value  = Value.StringValue("not-a-date")
    val result = Decoder[Value, OffsetDateTime].decode(value, "test")
    assert(result.isInvalid)
  }

  test("Decoder[Value, UUID] should decode StringValue correctly") {
    val id     = UUID.fromString("d684b6c2-6c1e-4f84-98f1-5f3ef06435c5")
    val value  = Value.StringValue(id.toString)
    val result = Decoder[Value, UUID].decode(value, "test")
    assertEquals(result, Valid(id))
  }

  test("Decoder[Value, UUID] should fail for invalid StringValue") {
    val value  = Value.StringValue("not-a-uuid")
    val result = Decoder[Value, UUID].decode(value, "test")
    assert(result.isInvalid)
  }

  test("Decoder[Value, Struct] should decode StructValue correctly") {
    val struct = Struct(Map("name" -> Value.StringValue("John")))
    val value  = Value.StructValue(struct)
    val result = Decoder[Value, Struct].decode(value, "test")
    assertEquals(result, Valid(struct))
  }

  test("Decoder[Value, List[Value]] should decode ListValue correctly") {
    val list   = List(Value.StringValue("a"), Value.NumberValue(1), Value.BoolValue(true))
    val value  = Value.ListValue(list)
    val result = Decoder[Value, List[Value]].decode(value, "test")
    assertEquals(result, Valid(list))
  }

  test("Decoder[Value, Option[String]] should decode NullValue as None") {
    val value  = Value.NullValue
    val result = Decoder[Value, Option[String]].decode(value, "test")
    assertEquals(result, Valid(None))
  }

  test("Decoder[Value, Option[String]] should decode StringValue as Some") {
    val value  = Value.StringValue("hello")
    val result = Decoder[Value, Option[String]].decode(value, "test")
    assertEquals(result, Valid(Some("hello")))
  }

  test("Decoder[Value, List[String]] should decode ListValue correctly") {
    val value  = Value.ListValue(List(Value.StringValue("a"), Value.StringValue("b")))
    val result = Decoder[Value, List[String]].decode(value, "test")
    assertEquals(result, Valid(List("a", "b")))
  }

  test("Decoder[Value, List[String]] should fail for mixed types") {
    val value  = Value.ListValue(List(Value.StringValue("a"), Value.NumberValue(1)))
    val result = Decoder[Value, List[String]].decode(value, "test")
    assert(result.isInvalid)
  }

  test("Decoder[Value, Map[String, String]] should decode StructValue correctly") {
    val struct = Struct(Map("name" -> Value.StringValue("John"), "age" -> Value.StringValue("30")))
    val value  = Value.StructValue(struct)
    val result = Decoder[Value, Map[String, String]].decode(value, "test")
    assertEquals(result, Valid(Map("name" -> "John", "age" -> "30")))
  }

  test("Decoder[Value, Map[String, Int]] should decode StructValue correctly") {
    val struct = Struct(Map("count" -> Value.NumberValue(42), "index" -> Value.NumberValue(1)))
    val value  = Value.StructValue(struct)
    val result = Decoder[Value, Map[String, Int]].decode(value, "test")
    assertEquals(result, Valid(Map("count" -> 42, "index" -> 1)))
  }

  test("should derive decoder for simple case class") {
    val struct = Struct(
      Map(
        "name"   -> Value.StringValue("John"),
        "age"    -> Value.NumberValue(30),
        "active" -> Value.BoolValue(true)
      )
    )

    val result = Decoder[Struct, Person].decode(struct, "person")
    assertEquals(result, Valid(Person("John", 30, true)))
  }

  test("should fail when required field is missing") {
    val struct = Struct(
      Map(
        "name"   -> Value.StringValue("John"),
        "active" -> Value.BoolValue(true)
      )
    )

    val result = Decoder[Struct, Person].decode(struct, "person")
    assert(result.isInvalid)
    result match
      case Invalid(errors) =>
        assertEquals(errors.length, 1L)
        assert(errors.head.isInstanceOf[Decoder.Error.MissingField])
        assertEquals(errors.head.path, "person.age")
      case _               => fail("Expected Invalid result")
  }

  test("should fail when field has wrong type") {
    val struct = Struct(
      Map(
        "name"   -> Value.StringValue("John"),
        "age"    -> Value.StringValue("thirty"),
        "active" -> Value.BoolValue(true)
      )
    )

    val result = Decoder[Struct, Person].decode(struct, "person")
    assert(result.isInvalid)
    result match
      case Invalid(errors) =>
        assertEquals(errors.length, 1L)
        assert(errors.head.isInstanceOf[Decoder.Error.WrongType])
      case _               => fail("Expected Invalid result")
  }

  test("should derive decoder for nested case class") {
    val personStruct = Struct(
      Map(
        "name"   -> Value.StringValue("John"),
        "age"    -> Value.NumberValue(30),
        "active" -> Value.BoolValue(true)
      )
    )

    val addressStruct = Struct(
      Map(
        "street"  -> Value.StringValue("123 Main St"),
        "city"    -> Value.StringValue("Anytown"),
        "zipCode" -> Value.StringValue("12345")
      )
    )

    val userStruct = Struct(
      Map(
        "id"      -> Value.StringValue("user123"),
        "person"  -> Value.StructValue(personStruct),
        "address" -> Value.StructValue(addressStruct),
        "tags"    -> Value.ListValue(
          List(
            Value.StringValue("admin"),
            Value.StringValue("premium")
          )
        )
      )
    )

    val result = Decoder[Struct, User].decode(userStruct, "user")
    assertEquals(
      result,
      Valid(
        User(
          "user123",
          Person("John", 30, true),
          Some(Address("123 Main St", "Anytown", "12345")),
          List("admin", "premium")
        )
      )
    )
  }

  test("should handle optional fields with None") {
    val personStruct = Struct(
      Map(
        "name"   -> Value.StringValue("John"),
        "age"    -> Value.NumberValue(30),
        "active" -> Value.BoolValue(true)
      )
    )

    val userStruct = Struct(
      Map(
        "id"      -> Value.StringValue("user123"),
        "person"  -> Value.StructValue(personStruct),
        "address" -> Value.NullValue,
        "tags"    -> Value.ListValue(List.empty)
      )
    )

    val result = Decoder[Struct, User].decode(userStruct, "user")
    assertEquals(
      result,
      Valid(
        User(
          "user123",
          Person("John", 30, true),
          None,
          List.empty
        )
      )
    )
  }

  test("should derive decoder for sum types") {
    val circleStruct = Struct(
      Map(
        "_type"  -> Value.StringValue("Circle"),
        "radius" -> Value.NumberValue(5.0)
      )
    )

    val result = Decoder[Struct, Shape].decode(circleStruct, "shape")
    assertEquals(result, Valid(Shape.Circle(5.0)))
  }

  test("should fail for sum type with missing discriminator") {
    val circleStruct = Struct(
      Map(
        "radius" -> Value.NumberValue(5.0)
      )
    )

    val result = Decoder[Struct, Shape].decode(circleStruct, "shape")
    assert(result.isInvalid)
    result match
      case Invalid(errors) =>
        assertEquals(errors.length, 1L)
        assert(errors.head.isInstanceOf[Decoder.Error.MissingDiscriminator])
      case _               => fail("Expected Invalid result")
  }

  test("should fail for sum type with unknown discriminator") {
    val unknownStruct = Struct(
      Map(
        "_type" -> Value.StringValue("Triangle"),
        "sides" -> Value.NumberValue(3)
      )
    )

    val result = Decoder[Struct, Shape].decode(unknownStruct, "shape")
    assert(result.isInvalid)
    result match
      case Invalid(errors) =>
        assertEquals(errors.length, 1L)
        assert(errors.head.isInstanceOf[Decoder.Error.UnknownDiscriminator])
      case _               => fail("Expected Invalid result")
  }

  test("should decode Value to case class") {
    val personStruct = Struct(
      Map(
        "name"   -> Value.StringValue("John"),
        "age"    -> Value.NumberValue(30),
        "active" -> Value.BoolValue(true)
      )
    )

    val value  = Value.StructValue(personStruct)
    val result = Decoder[Value, Person].decode(value, "person")
    assertEquals(result, Valid(Person("John", 30, true)))
  }

  test("should fail when Value is not a Struct") {
    val value  = Value.StringValue("not a struct")
    val result = Decoder[Value, Person].decode(value, "person")
    assert(result.isInvalid)
    result match
      case Invalid(errors) =>
        assertEquals(errors.length, 1L)
        assert(errors.head.isInstanceOf[Decoder.Error.WrongType])
      case _               => fail("Expected Invalid result")
  }

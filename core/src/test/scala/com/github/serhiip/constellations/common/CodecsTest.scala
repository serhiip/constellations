package io.github.serhiip.constellations.common

import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

class CodecsSuite extends FunSuite:
  import Codecs.given

  test("Value.NullValue should encode/decode correctly") {
    val value   = Value.NullValue
    val json    = value.asJson
    val decoded = decode[Value](json.noSpaces)

    assertEquals(json, io.circe.Json.Null)
    assertEquals(decoded, Right(value))
  }

  test("Value.NumberValue should encode/decode correctly") {
    val value   = Value.NumberValue(42.5)
    val json    = value.asJson
    val decoded = decode[Value](json.noSpaces)

    assertEquals(json, io.circe.Json.fromDoubleOrNull(42.5))
    assertEquals(decoded, Right(value))
  }

  test("Value.StringValue should encode/decode correctly") {
    val value   = Value.StringValue("hello world")
    val json    = value.asJson
    val decoded = decode[Value](json.noSpaces)

    assertEquals(json, io.circe.Json.fromString("hello world"))
    assertEquals(decoded, Right(value))
  }

  test("Value.BoolValue should encode/decode correctly") {
    val value   = Value.BoolValue(true)
    val json    = value.asJson
    val decoded = decode[Value](json.noSpaces)

    assertEquals(json, io.circe.Json.fromBoolean(true))
    assertEquals(decoded, Right(value))
  }

  test("Value.ListValue should encode/decode correctly") {
    val value   = Value.ListValue(
      List(
        Value.StringValue("a"),
        Value.NumberValue(1),
        Value.BoolValue(true)
      )
    )
    val json    = value.asJson
    val decoded = decode[Value](json.noSpaces)

    assertEquals(decoded, Right(value))
  }

  test("Struct should encode/decode correctly") {
    val struct  = Struct(
      Map(
        "name"   -> Value.StringValue("John"),
        "age"    -> Value.NumberValue(30),
        "active" -> Value.BoolValue(true)
      )
    )
    val json    = struct.asJson
    val decoded = decode[Struct](json.noSpaces)

    assertEquals(decoded, Right(struct))
  }

  test("Struct with nested Struct should encode/decode correctly") {
    val nestedStruct = Struct(
      Map(
        "street" -> Value.StringValue("123 Main St"),
        "city"   -> Value.StringValue("Anytown")
      )
    )
    val struct       = Struct(
      Map(
        "name"    -> Value.StringValue("John"),
        "address" -> Value.StructValue(nestedStruct)
      )
    )
    val json         = struct.asJson
    val decoded      = decode[Struct](json.noSpaces)

    assertEquals(decoded, Right(struct))
  }

  test("SchemaType should encode/decode correctly") {
    val types = List(
      SchemaType.TypeUnspecified,
      SchemaType.String,
      SchemaType.Number,
      SchemaType.Integer,
      SchemaType.Boolean,
      SchemaType.Array,
      SchemaType.Object
    )

    types.foreach { schemaType =>
      val json    = schemaType.asJson
      val decoded = decode[SchemaType](json.noSpaces)
      assertEquals(decoded, Right(schemaType))
    }
  }

  test("Schema.string with constraints should encode/decode correctly") {
    val schema  = Schema.string(
      format = Some("email"),
      description = Some("User email address"),
      nullable = Some(false),
      minLength = Some(5),
      maxLength = Some(100),
      pattern = Some("^[^@]+@[^@]+\\.[^@]+$"),
      enm = List("admin@example.com", "user@example.com")
    )
    val json    = schema.asJson
    val decoded = decode[Schema](json.noSpaces)

    assertEquals(decoded, Right(schema))
  }

  test("Schema.number should encode/decode correctly") {
    val schema  = Schema.number(
      description = Some("User score"),
      nullable = Some(true),
      minimum = Some(0.0),
      maximum = Some(100.0)
    )
    val json    = schema.asJson
    val decoded = decode[Schema](json.noSpaces)

    assertEquals(decoded, Right(schema))
  }

  test("Schema.integer should encode/decode correctly") {
    val schema  = Schema.integer(
      description = Some("User age"),
      nullable = Some(false),
      minimum = Some(0.0),
      maximum = Some(150.0)
    )
    val json    = schema.asJson
    val decoded = decode[Schema](json.noSpaces)

    assertEquals(decoded, Right(schema))
  }

  test("Schema.boolean should encode/decode correctly") {
    val schema  = Schema.boolean(
      description = Some("User active status"),
      nullable = Some(true)
    )
    val json    = schema.asJson
    val decoded = decode[Schema](json.noSpaces)

    assertEquals(decoded, Right(schema))
  }

  test("Schema.array should encode/decode correctly") {
    val itemSchema = Schema.string()
    val schema     = Schema.array(
      items = itemSchema,
      description = Some("List of tags"),
      nullable = Some(false),
      minItems = Some(0),
      maxItems = Some(10)
    )
    val json       = schema.asJson
    val decoded    = decode[Schema](json.noSpaces)

    assertEquals(decoded, Right(schema))
  }

  test("Schema.obj should encode/decode correctly") {
    val properties = Map(
      "id"     -> Schema.string(),
      "name"   -> Schema.string(),
      "age"    -> Schema.integer(),
      "active" -> Schema.boolean()
    )
    val schema     = Schema.obj(
      properties = properties,
      required = List("id", "name"),
      description = Some("User object"),
      nullable = Some(false),
      minProperties = Some(1),
      maxProperties = Some(10)
    )
    val json       = schema.asJson
    val decoded    = decode[Schema](json.noSpaces)

    assertEquals(decoded, Right(schema))
  }

  test("Schema with nested object should encode/decode correctly") {
    val addressSchema = Schema.obj(
      properties = Map(
        "street"  -> Schema.string(),
        "city"    -> Schema.string(),
        "zipCode" -> Schema.string()
      ),
      required = List("street", "city")
    )

    val userSchema = Schema.obj(
      properties = Map(
        "id"      -> Schema.string(),
        "name"    -> Schema.string(),
        "address" -> addressSchema,
        "tags"    -> Schema.array(Schema.string())
      ),
      required = List("id", "name")
    )

    val json    = userSchema.asJson
    val decoded = decode[Schema](json.noSpaces)

    assertEquals(decoded, Right(userSchema))
  }

  test("FunctionDeclaration.simple should encode/decode correctly") {
    val func    = FunctionDeclaration("getUser", "Get user information")
    val json    = func.asJson
    val decoded = decode[FunctionDeclaration](json.noSpaces)

    assertEquals(decoded, Right(func))
  }

  test("FunctionDeclaration.withParameters should encode/decode correctly") {
    val params  = Schema.obj(
      properties = Map(
        "userId"         -> Schema.string(),
        "includeDetails" -> Schema.boolean()
      ),
      required = List("userId")
    )
    val func    = FunctionDeclaration("getUser", "Get user information", params)
    val json    = func.asJson
    val decoded = decode[FunctionDeclaration](json.noSpaces)

    assertEquals(decoded, Right(func))
  }

  test("FunctionDeclaration.fromMethod should encode/decode correctly") {
    val func    = FunctionDeclaration.fromMethod("UserService", "getUser", "Get user information")
    val json    = func.asJson
    val decoded = decode[FunctionDeclaration](json.noSpaces)

    assertEquals(decoded, Right(func))
  }

  test("FunctionResponse.simple should encode/decode correctly") {
    val response = FunctionResponse(
      "getUser",
      Struct(
        Map(
          "id"   -> Value.StringValue("123"),
          "name" -> Value.StringValue("John Doe")
        )
      )
    )
    val json     = response.asJson
    val decoded  = decode[FunctionResponse](json.noSpaces)

    assertEquals(decoded, Right(response))
  }

  test("FunctionResponse.withStringResponse should encode/decode correctly") {
    val response = FunctionResponse(
      "getUser",
      Struct(Map("result" -> Value.string("User data retrieved successfully")))
    )
    val json     = response.asJson
    val decoded  = decode[FunctionResponse](json.noSpaces)

    assertEquals(decoded, Right(response))
  }

  test("FunctionResponse.withErrorResponse should encode/decode correctly") {
    val response = FunctionResponse(
      "getUser",
      Struct(Map("error" -> Value.string("User not found")))
    )
    val json     = response.asJson
    val decoded  = decode[FunctionResponse](json.noSpaces)

    assertEquals(decoded, Right(response))
  }

  test("FunctionResponse.withSuccessResponse should encode/decode correctly") {
    val data     = Map(
      "id"     -> "123",
      "name"   -> "John Doe",
      "age"    -> 30,
      "active" -> true,
      "score"  -> 95.5
    )
    val response = FunctionResponse("getUser", Struct.fromMap(data))
    val json     = response.asJson
    val decoded  = decode[FunctionResponse](json.noSpaces)

    assertEquals(decoded, Right(response))
  }

  test("FunctionResponse.fromMethod should encode/decode correctly") {
    val response = FunctionResponse.fromMethod(
      "UserService",
      "getUser",
      Struct(
        Map(
          "result" -> Value.StringValue("success")
        )
      )
    )
    val json     = response.asJson
    val decoded  = decode[FunctionResponse](json.noSpaces)

    assertEquals(decoded, Right(response))
  }

  test("Complex nested structures should encode/decode correctly") {
    val userStruct = Struct(
      Map(
        "id"      -> Value.NumberValue(123),
        "name"    -> Value.StringValue("John Doe"),
        "address" -> Value.StructValue(
          Struct(
            Map(
              "street" -> Value.StringValue("123 Main St"),
              "city"   -> Value.StringValue("Anytown")
            )
          )
        ),
        "tags"    -> Value.ListValue(
          List(
            Value.StringValue("admin"),
            Value.StringValue("user")
          )
        )
      )
    )

    val response = FunctionResponse("getUser", userStruct)
    val json     = response.asJson
    val decoded  = decode[FunctionResponse](json.noSpaces)

    assertEquals(decoded, Right(response))
  }

  test("FunctionResponse.withCallId should encode/decode correctly") {
    val response = FunctionResponse(
      "getUser",
      Struct(Map("result" -> Value.string("User data retrieved successfully"))),
      "call-123".some
    )
    val json     = response.asJson
    val decoded  = decode[FunctionResponse](json.noSpaces)

    assertEquals(decoded, Right(response))
    assertEquals(response.functionCallId, "call-123".some)
  }

  test("FunctionResponse with functionCallId should encode/decode correctly") {
    val response = FunctionResponse(
      "getUser",
      Struct(Map("id" -> Value.StringValue("123"))),
      "call-456".some
    )
    val json     = response.asJson
    val decoded  = decode[FunctionResponse](json.noSpaces)

    assertEquals(decoded, Right(response))
    assertEquals(response.functionCallId, "call-456".some)
  }

  test("FunctionCall with callId should encode/decode correctly") {
    val call    = FunctionCall(
      "getUser",
      Struct(Map("id" -> Value.StringValue("123"))),
      "call-789".some
    )
    val json    = call.asJson
    val decoded = decode[FunctionCall](json.noSpaces)

    assertEquals(decoded, Right(call))
    assertEquals(call.callId, "call-789".some)
  }

  test("Value.fromAny should work correctly") {
    val data = Map(
      "string"  -> "hello",
      "number"  -> 42,
      "double"  -> 3.14,
      "boolean" -> true,
      "null"    -> null,
      "list"    -> List("a", "b", "c"),
      "nested"  -> Map("key" -> "value")
    )

    val struct  = Struct.fromMap(data)
    val json    = struct.asJson
    val decoded = decode[Struct](json.noSpaces)

    assertEquals(decoded, Right(struct))
  }

  test("Round-trip encoding/decoding should preserve all data") {
    val originalFunc = FunctionDeclaration(
      "updateUser",
      "Update user information",
      Schema.obj(
        properties = Map(
          "userId"  -> Schema.string(description = Some("User ID")),
          "name"    -> Schema.string(minLength = Some(1), maxLength = Some(100)),
          "age"     -> Schema.integer(minimum = Some(0), maximum = Some(150)),
          "address" -> Schema.obj(
            properties = Map(
              "street" -> Schema.string(),
              "city"   -> Schema.string()
            ),
            required = List("street", "city")
          )
        ),
        required = List("userId")
      )
    )

    val json    = originalFunc.asJson
    val decoded = decode[FunctionDeclaration](json.noSpaces)

    assertEquals(decoded, Right(originalFunc))
  }

  test("FunctionCall.simple should encode/decode correctly") {
    val call    = FunctionCall(
      "getUser",
      Struct(
        Map(
          "userId" -> Value.StringValue("123")
        )
      )
    )
    val json    = call.asJson
    val decoded = decode[FunctionCall](json.noSpaces)

    assertEquals(decoded, Right(call))
  }

  test("FunctionCall.fromMethod should encode/decode correctly") {
    val call    = FunctionCall.fromMethod(
      "UserService",
      "getUser",
      Struct(
        Map(
          "userId" -> Value.StringValue("123")
        )
      )
    )
    val json    = call.asJson
    val decoded = decode[FunctionCall](json.noSpaces)

    assertEquals(decoded, Right(call))
  }

  test("FunctionCall.withStringArgs should encode/decode correctly") {
    val call    = FunctionCall(
      "updateUser",
      Struct(
        Map(
          "userId" -> Value.string("123"),
          "name"   -> Value.string("John Doe")
        )
      )
    )
    val json    = call.asJson
    val decoded = decode[FunctionCall](json.noSpaces)

    assertEquals(decoded, Right(call))
  }

  test("FunctionCall.withJsonArgs should encode/decode correctly") {
    val call    = FunctionCall(
      "createUser",
      Struct(
        Map(
          "name"   -> Value.StringValue("John Doe"),
          "age"    -> Value.NumberValue(30),
          "active" -> Value.BoolValue(true)
        )
      )
    )
    val json    = call.asJson
    val decoded = decode[FunctionCall](json.noSpaces)

    assertEquals(decoded, Right(call))
  }

  test("FunctionCall.withMapArgs should encode/decode correctly") {
    val call    = FunctionCall(
      "processData",
      Struct.fromMap(
        Map(
          "id"     -> "123",
          "count"  -> 42,
          "active" -> true,
          "score"  -> 95.5
        )
      )
    )
    val json    = call.asJson
    val decoded = decode[FunctionCall](json.noSpaces)

    assertEquals(decoded, Right(call))
  }

  test("FunctionCall.empty should encode/decode correctly") {
    val call    = FunctionCall.empty("ping")
    val json    = call.asJson
    val decoded = decode[FunctionCall](json.noSpaces)

    assertEquals(decoded, Right(call))
  }

  test("FunctionCall with complex nested arguments should encode/decode correctly") {
    val call    = FunctionCall(
      "processUser",
      Struct(
        Map(
          "user"    -> Value.StructValue(
            Struct(
              Map(
                "id"      -> Value.StringValue("123"),
                "name"    -> Value.StringValue("John Doe"),
                "address" -> Value.StructValue(
                  Struct(
                    Map(
                      "street" -> Value.StringValue("123 Main St"),
                      "city"   -> Value.StringValue("Anytown")
                    )
                  )
                ),
                "tags"    -> Value.ListValue(
                  List(
                    Value.StringValue("admin"),
                    Value.StringValue("user")
                  )
                )
              )
            )
          ),
          "options" -> Value.StructValue(
            Struct(
              Map(
                "includeDetails" -> Value.BoolValue(true),
                "maxResults"     -> Value.NumberValue(10)
              )
            )
          )
        )
      )
    )
    val json    = call.asJson
    val decoded = decode[FunctionCall](json.noSpaces)

    assertEquals(decoded, Right(call))
  }

  test("FunctionCall round-trip encoding/decoding should preserve all data") {
    val originalCall = FunctionCall(
      "complexOperation",
      Struct.fromMap(
        Map(
          "userId"  -> "123",
          "data"    -> Map(
            "name"     -> "John Doe",
            "age"      -> 30,
            "scores"   -> List(85, 92, 78),
            "metadata" -> Map(
              "created" -> "2024-01-01",
              "active"  -> true
            )
          ),
          "options" -> Map(
            "includeHistory" -> true,
            "maxResults"     -> 50
          )
        )
      )
    )

    val json    = originalCall.asJson
    val decoded = decode[FunctionCall](json.noSpaces)

    assertEquals(decoded, Right(originalCall))
  }

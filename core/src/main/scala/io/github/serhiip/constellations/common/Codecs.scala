package io.github.serhiip.constellations.common

import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}

object Codecs:

  given Encoder[Value] with
    def apply(value: Value): Json = value match
      case Value.NullValue      => Json.Null
      case Value.NumberValue(v) => Json.fromDoubleOrNull(v)
      case Value.StringValue(v) => Json.fromString(v)
      case Value.BoolValue(v)   => Json.fromBoolean(v)
      case Value.StructValue(v) => v.asJson
      case Value.ListValue(v)   => Json.fromValues(v.map(_.asJson))

  given Decoder[Value] with
    def apply(c: io.circe.HCursor): Decoder.Result[Value] = c.value match
      case Json.Null        => Right(Value.NullValue)
      case j if j.isNumber  => j.as[Double].map(Value.NumberValue.apply)
      case j if j.isString  => j.as[String].map(Value.StringValue.apply)
      case j if j.isBoolean => j.as[Boolean].map(Value.BoolValue.apply)
      case j if j.isObject  => j.as[Struct].map(Value.StructValue.apply)
      case j if j.isArray   => j.as[List[Value]].map(Value.ListValue.apply)
      case _                => Left(io.circe.DecodingFailure("Invalid Value", c.history))

  given Encoder[Struct] with
    def apply(struct: Struct): Json =
      val fields = struct.fields.map { case (key, value) =>
        key -> value.asJson
      }.toSeq
      Json.obj(fields*)

  given Decoder[Struct] with
    def apply(c: io.circe.HCursor): Decoder.Result[Struct] =
      c.as[Map[String, Value]].map(Struct(_))

  given Encoder[SchemaType] with
    def apply(schemaType: SchemaType): Json = schemaType match
      case SchemaType.TypeUnspecified => Json.fromString("TYPE_UNSPECIFIED")
      case SchemaType.String          => Json.fromString("string")
      case SchemaType.Number          => Json.fromString("number")
      case SchemaType.Integer         => Json.fromString("integer")
      case SchemaType.Boolean         => Json.fromString("boolean")
      case SchemaType.Array           => Json.fromString("array")
      case SchemaType.Object          => Json.fromString("object")

  given Decoder[SchemaType] with
    def apply(c: io.circe.HCursor): Decoder.Result[SchemaType] = c.as[String].flatMap {
      case "TYPE_UNSPECIFIED" => Right(SchemaType.TypeUnspecified)
      case "string"           => Right(SchemaType.String)
      case "number"           => Right(SchemaType.Number)
      case "integer"          => Right(SchemaType.Integer)
      case "boolean"          => Right(SchemaType.Boolean)
      case "array"            => Right(SchemaType.Array)
      case "object"           => Right(SchemaType.Object)
      case other              => Left(io.circe.DecodingFailure(s"Unknown SchemaType: $other", c.history))
    }

  given Encoder[Schema] with
    def apply(schema: Schema): Json =
      val fields = List(
        Some("type"                                          -> schema.tpe.asJson),
        schema.format.map(f => "format" -> Json.fromString(f)),
        schema.title.map(t => "title" -> Json.fromString(t)),
        schema.description.map(d => "description" -> Json.fromString(d)),
        schema.nullable.map(n => "nullable" -> Json.fromBoolean(n)),
        schema.default.map(d => "default" -> Json.fromString(d)),
        schema.items.map(i => "items" -> i.asJson),
        schema.minItems.map(m => "minItems" -> Json.fromLong(m)),
        schema.maxItems.map(m => "maxItems" -> Json.fromLong(m)),
        Option.when(schema.enm.nonEmpty)("enum"              -> Json.fromValues(schema.enm.map(Json.fromString))),
        Option.when(schema.properties.nonEmpty)("properties" -> Json.obj(schema.properties.map { case (key, value) => key -> value.asJson }.toSeq*)),
        Option.when(schema.required.nonEmpty)("required"     -> Json.fromValues(schema.required.map(Json.fromString))),
        schema.minProperties.map(m => "minProperties" -> Json.fromLong(m)),
        schema.maxProperties.map(m => "maxProperties" -> Json.fromLong(m)),
        schema.minimum.map(m => "minimum" -> Json.fromDoubleOrNull(m)),
        schema.maximum.map(m => "maximum" -> Json.fromDoubleOrNull(m)),
        schema.minLength.map(m => "minLength" -> Json.fromLong(m)),
        schema.maxLength.map(m => "maxLength" -> Json.fromLong(m)),
        schema.pattern.map(p => "pattern" -> Json.fromString(p)),
        schema.example.map(e => "example" -> Json.fromString(e))
      ).flatten
      Json.obj(fields*)

  given Decoder[Schema] with
    def apply(c: io.circe.HCursor): Decoder.Result[Schema] = for
      tpe           <- c.get[SchemaType]("type")
      format        <- c.get[Option[String]]("format")
      title         <- c.get[Option[String]]("title")
      description   <- c.get[Option[String]]("description")
      nullable      <- c.get[Option[Boolean]]("nullable")
      default       <- c.get[Option[String]]("default")
      items         <- c.get[Option[Schema]]("items")
      minItems      <- c.get[Option[Long]]("minItems")
      maxItems      <- c.get[Option[Long]]("maxItems")
      enm           <- c.getOrElse[List[String]]("enum")(List.empty)
      properties    <- c.getOrElse[Map[String, Schema]]("properties")(Map.empty)
      required      <- c.getOrElse[List[String]]("required")(List.empty)
      minProperties <- c.get[Option[Long]]("minProperties")
      maxProperties <- c.get[Option[Long]]("maxProperties")
      minimum       <- c.get[Option[Double]]("minimum")
      maximum       <- c.get[Option[Double]]("maximum")
      minLength     <- c.get[Option[Long]]("minLength")
      maxLength     <- c.get[Option[Long]]("maxLength")
      pattern       <- c.get[Option[String]]("pattern")
      example       <- c.get[Option[String]]("example")
    yield Schema(
      tpe = tpe,
      format = format,
      title = title,
      description = description,
      nullable = nullable,
      default = default,
      items = items,
      minItems = minItems,
      maxItems = maxItems,
      enm = enm,
      properties = properties,
      required = required,
      minProperties = minProperties,
      maxProperties = maxProperties,
      minimum = minimum,
      maximum = maximum,
      minLength = minLength,
      maxLength = maxLength,
      pattern = pattern,
      example = example
    )

  given Encoder[FunctionDeclaration] with
    def apply(func: FunctionDeclaration): Json =
      val fields = List(
        "name" -> Json.fromString(func.name)
      ) ++ func.description.map(d => "description" -> Json.fromString(d)).toList ++
        func.parameters.map(p => "parameters" -> p.asJson).toList
      Json.obj(fields*)

  given Decoder[FunctionDeclaration] with
    def apply(c: io.circe.HCursor): Decoder.Result[FunctionDeclaration] = for
      name        <- c.get[String]("name")
      description <- c.get[Option[String]]("description")
      parameters  <- c.get[Option[Schema]]("parameters")
    yield FunctionDeclaration(name, description, parameters)

  given Encoder[FunctionResponse] with
    def apply(response: FunctionResponse): Json = Json.obj(
      "name"           -> Json.fromString(response.name),
      "response"       -> response.response.asJson,
      "functionCallId" -> response.functionCallId.asJson
    )

  given Decoder[FunctionResponse] with
    def apply(c: io.circe.HCursor): Decoder.Result[FunctionResponse] = for
      name           <- c.get[String]("name")
      response       <- c.get[Struct]("response")
      functionCallId <- c.get[Option[String]]("functionCallId")
    yield FunctionResponse(name, response, functionCallId)

  given Encoder[FunctionCall] with
    def apply(call: FunctionCall): Json = Json.obj(
      "name"   -> Json.fromString(call.name),
      "args"   -> call.args.asJson,
      "callId" -> call.callId.asJson
    )

  given Decoder[FunctionCall] with
    def apply(c: io.circe.HCursor): Decoder.Result[FunctionCall] = for
      name   <- c.get[String]("name")
      args   <- c.get[Struct]("args")
      callId <- c.get[Option[String]]("callId")
    yield FunctionCall(name, args, callId)

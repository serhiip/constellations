package io.github.serhiip.constellations.common

import cats.syntax.option.*

final case class FunctionCall(name: String, args: Struct, callId: Option[String])

object FunctionCall:
  def apply(name: String, args: Struct): FunctionCall =
    new FunctionCall(name, args, none)

  def fromMethod(className: String, methodName: String, args: Struct): FunctionCall =
    FunctionCall(s"${className}_${methodName}", args)

  def empty(name: String): FunctionCall =
    FunctionCall(name, Struct.empty)

enum ContentPart:
  case Text(text: String)
  case Image(base64Encoded: String)

enum Message:
  case User(content: List[ContentPart])
  case Assistant(content: Option[String], images: List[ContentPart.Image] = List.empty)
  case System(content: String)
  case Tool(content: FunctionCall)
  case ToolResult(content: FunctionResponse)

final case class ToolCall(id: String, function: FunctionCall)

enum FinishReason:
  case ToolCalls, Stop, Length, ContentFilter, Error

enum Value:
  case NullValue
  case NumberValue(value: Double)
  case StringValue(value: String)
  case BoolValue(value: Boolean)
  case StructValue(value: Struct)
  case ListValue(value: List[Value])

final case class Struct(fields: Map[String, Value])

object Struct:
  def empty: Struct                           = Struct(Map.empty)
  def apply(fields: (String, Value)*): Struct = Struct(fields.toMap)
  def fromMap(data: Map[String, Any]): Struct = Struct(data.view.mapValues(Value.fromAny).toMap)

object Value:
  def nullValue: Value = Value.NullValue

  def number(value: Double): Value = Value.NumberValue(value)
  def number(value: Int): Value    = Value.NumberValue(value.toDouble)
  def number(value: Long): Value   = Value.NumberValue(value.toDouble)
  def number(value: Float): Value  = Value.NumberValue(value.toDouble)

  def string(value: String): Value = Value.StringValue(value)

  def bool(value: Boolean): Value = Value.BoolValue(value)

  def struct(value: Struct): Value              = Value.StructValue(value)
  def struct(fields: (String, Value)*): Value   = Value.StructValue(Struct(fields.toMap))
  def struct(fields: Map[String, Value]): Value = Value.StructValue(Struct(fields))

  def list(values: List[Value]): Value = Value.ListValue(values)
  def list(values: Value*): Value      = Value.ListValue(values.toList)

  def fromAny(value: Any): Value = value match
    case null         => Value.NullValue
    case s: String    => Value.StringValue(s)
    case i: Int       => Value.NumberValue(i.toDouble)
    case l: Long      => Value.NumberValue(l.toDouble)
    case d: Double    => Value.NumberValue(d)
    case f: Float     => Value.NumberValue(f.toDouble)
    case b: Boolean   => Value.BoolValue(b)
    case m: Map[?, ?] =>
      try
        val stringMap = m.asInstanceOf[Map[String, Any]]
        Value.StructValue(Struct.fromMap(stringMap))
      catch case _: ClassCastException => Value.StringValue(value.toString)
    case l: List[?]   => Value.ListValue(l.map(Value.fromAny))
    case s: Seq[?]    => Value.ListValue(s.map(Value.fromAny).toList)
    case _            => Value.StringValue(value.toString)

enum SchemaType:
  case TypeUnspecified, String, Number, Integer, Boolean, Array, Object

final case class Schema(
    tpe: SchemaType,
    format: Option[String] = None,
    title: Option[String] = None,
    description: Option[String] = None,
    nullable: Option[Boolean] = None,
    default: Option[String] = None,
    items: Option[Schema] = None,
    minItems: Option[Long] = None,
    maxItems: Option[Long] = None,
    enm: List[String] = List.empty,
    properties: Map[String, Schema] = Map.empty,
    required: List[String] = List.empty,
    minProperties: Option[Long] = None,
    maxProperties: Option[Long] = None,
    minimum: Option[Double] = None,
    maximum: Option[Double] = None,
    minLength: Option[Long] = None,
    maxLength: Option[Long] = None,
    pattern: Option[String] = None,
    example: Option[String] = None
)

object Schema:
  def string(
      format: Option[String] = None,
      description: Option[String] = None,
      nullable: Option[Boolean] = None,
      minLength: Option[Long] = None,
      maxLength: Option[Long] = None,
      pattern: Option[String] = None,
      enm: List[String] = List.empty
  ): Schema =
    Schema(
      tpe = SchemaType.String,
      format = format,
      description = description,
      nullable = nullable,
      minLength = minLength,
      maxLength = maxLength,
      pattern = pattern,
      enm = enm
    )

  def number(
      description: Option[String] = None,
      nullable: Option[Boolean] = None,
      minimum: Option[Double] = None,
      maximum: Option[Double] = None
  ): Schema =
    Schema(
      tpe = SchemaType.Number,
      description = description,
      nullable = nullable,
      minimum = minimum,
      maximum = maximum
    )

  def integer(
      description: Option[String] = None,
      nullable: Option[Boolean] = None,
      minimum: Option[Double] = None,
      maximum: Option[Double] = None
  ): Schema =
    Schema(
      tpe = SchemaType.Integer,
      description = description,
      nullable = nullable,
      minimum = minimum,
      maximum = maximum
    )

  def boolean(
      description: Option[String] = None,
      nullable: Option[Boolean] = None
  ): Schema =
    Schema(
      tpe = SchemaType.Boolean,
      description = description,
      nullable = nullable
    )

  def array(
      items: Schema,
      description: Option[String] = None,
      nullable: Option[Boolean] = None,
      minItems: Option[Long] = None,
      maxItems: Option[Long] = None
  ): Schema =
    Schema(
      tpe = SchemaType.Array,
      items = Some(items),
      description = description,
      nullable = nullable,
      minItems = minItems,
      maxItems = maxItems
    )

  def obj(
      properties: Map[String, Schema] = Map.empty,
      required: List[String] = List.empty,
      description: Option[String] = None,
      nullable: Option[Boolean] = None,
      minProperties: Option[Long] = None,
      maxProperties: Option[Long] = None
  ): Schema =
    Schema(
      tpe = SchemaType.Object,
      properties = properties,
      required = required,
      description = description,
      nullable = nullable,
      minProperties = minProperties,
      maxProperties = maxProperties
    )

final case class FunctionDeclaration(name: String, description: Option[String] = None, parameters: Option[Schema] = None)

object FunctionDeclaration:
  def apply(name: String, description: String): FunctionDeclaration =
    new FunctionDeclaration(name, Some(description))

  def apply(name: String, description: String, parameters: Schema): FunctionDeclaration =
    new FunctionDeclaration(name, Some(description), Some(parameters))

  def fromMethod(className: String, methodName: String, description: String): FunctionDeclaration =
    FunctionDeclaration(s"${className}_${methodName}", Some(description))

  def fromMethod(className: String, methodName: String, description: String, parameters: Schema): FunctionDeclaration =
    FunctionDeclaration(s"${className}_${methodName}", Some(description), Some(parameters))

final case class FunctionResponse(name: String, response: Struct, functionCallId: Option[String])

object FunctionResponse:
  def apply(name: String, response: Struct): FunctionResponse =
    new FunctionResponse(name, response, none)

  def fromMethod(className: String, methodName: String, response: Struct): FunctionResponse =
    FunctionResponse(s"${className}_${methodName}", response)

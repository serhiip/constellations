package io.github.serhiip.constellations.bedrock

import io.github.serhiip.constellations.common.{Struct, Value}
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.core.document.Document

object Codecs:

  def valueToDocument(value: Value): Document = value match
    case Value.NullValue        => Document.fromNull()
    case Value.NumberValue(n)   => Document.fromNumber(n)
    case Value.StringValue(s)   => Document.fromString(s)
    case Value.BoolValue(b)     => Document.fromBoolean(b)
    case Value.StructValue(str) => structToDocument(str)
    case Value.ListValue(lst)   => Document.fromList(lst.map(valueToDocument).asJava)

  def structToDocument(struct: Struct): Document =
    Document.fromMap(struct.fields.view.mapValues(valueToDocument).toMap.asJava)

  def documentToValue(document: Document): Value =
    if document.isNull() then Value.NullValue
    else if document.isString() then Value.StringValue(document.asString())
    else if document.isNumber() then Value.NumberValue(document.asNumber().doubleValue())
    else if document.isBoolean() then Value.BoolValue(document.asBoolean())
    else if document.isMap() then Value.StructValue(documentToStruct(document))
    else if document.isList() then Value.ListValue(document.asList().asScala.map(documentToValue).toList)
    else Value.NullValue

  def documentToStruct(document: Document): Struct =
    if document.isMap() then Struct(document.asMap().asScala.view.mapValues(documentToValue).toMap)
    else Struct.empty

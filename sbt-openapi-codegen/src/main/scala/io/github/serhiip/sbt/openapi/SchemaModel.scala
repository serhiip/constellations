package io.github.serhiip.sbt.openapi

import io.circe.{Json, JsonNumber, JsonObject}
import io.circe.parser.parse

import scala.collection.mutable

object SchemaModel {

  val GeneratorVersion = "1"

  enum TypeRef {
    case StringType
    case LongType
    case DoubleType
    case BooleanType
    case JsonType
    case NullType
    case Named(name: String)
    case ListOf(element: TypeRef)
    case MapOf(value: TypeRef)
    case Optional(inner: TypeRef)
    case Union(members: List[TypeRef])
    case External(scala: String)
    case Invalid
  }

  final case class Field(
      jsonName: String,
      tpe: TypeRef,
      required: Boolean,
      defaultLiteral: Option[String],
      syntheticMap: Boolean
  )

  sealed trait Definition {
    def name: String
  }

  final case class RecordDef(name: String, fields: List[Field], parents: List[String]) extends Definition
  final case class EnumCase(ident: String, literal: String)
  final case class EnumDef(name: String, valueType: String, cases: List[EnumCase], unknownIdent: String) extends Definition
  final case class Discriminator(property: String, tags: List[(String, String)])
  final case class SumDef(name: String, parents: List[String], variants: List[String], discriminator: Option[Discriminator])
      extends Definition
  final case class AliasDef(name: String, tpe: TypeRef) extends Definition

  final case class ExampleSpec(name: String, scalaType: String, json: Json)
  final case class SchemaFailure(path: String, reason: String) {
    override def toString: String = s"$path: $reason"
  }
  final case class Model(definitions: List[Definition], examples: List[ExampleSpec])

  final case class Resolved(tpe: TypeRef, defaultLiteral: Option[String])

  private final case class RecordDraft(fields: List[Field])
  private final case class SumDraft(variants: List[String], discriminator: Option[Discriminator])

  private val AnnotationKeys = Set(
    "description",
    "title",
    "example",
    "examples",
    "deprecated",
    "format",
    "default",
    "externalDocs",
    "readOnly",
    "writeOnly",
    "xml",
    "minimum",
    "maximum",
    "exclusiveMinimum",
    "exclusiveMaximum",
    "minLength",
    "maxLength",
    "minItems",
    "maxItems",
    "pattern",
    "uniqueItems",
    "multipleOf",
    "minProperties",
    "maxProperties"
  )

  private val UnsupportedKeys = Set(
    "not",
    "prefixItems",
    "patternProperties",
    "if",
    "then",
    "else",
    "dependentSchemas",
    "propertyNames",
    "contains",
    "unevaluatedProperties",
    "unevaluatedItems"
  )

  def analyze(
      rawJson: String,
      overrides: Map[String, String],
      exclusions: Set[String],
      tagHints: List[String]
  ): Either[List[SchemaFailure], Model] = {
    val root = parse(rawJson).fold(err => sys.error(s"Invalid OpenAPI JSON: ${err.getMessage}"), identity)
    val schemas = root.hcursor
      .downField("components")
      .downField("schemas")
      .as[JsonObject]
      .fold(err => sys.error(s"Missing components.schemas: ${err.getMessage}"), identity)
    val analyzer = Analyzer(schemas, overrides, exclusions, tagHints)
    analyzer.run()
  }

  def render(tpe: TypeRef, nested: Boolean = false): String = tpe match {
    case TypeRef.StringType     => "String"
    case TypeRef.LongType       => "Long"
    case TypeRef.DoubleType     => "Double"
    case TypeRef.BooleanType    => "Boolean"
    case TypeRef.JsonType       => "io.circe.Json"
    case TypeRef.NullType       => "JsonNull"
    case TypeRef.Named(name)    => name
    case TypeRef.ListOf(elem)   => s"List[${render(elem, true)}]"
    case TypeRef.MapOf(value)   => s"Map[String, ${render(value, true)}]"
    case TypeRef.Optional(inner) => s"Option[${render(inner, true)}]"
    case TypeRef.Union(members) =>
      val text = members.map(member => render(member, true)).mkString(" | ")
      if nested then s"($text)" else text
    case TypeRef.External(scala) => scala
    case TypeRef.Invalid         => "Nothing"
  }

  def toPascal(raw: String): String = {
    val parts = raw.split("[^A-Za-z0-9]+").filter(_.nonEmpty)
    val pascal = parts.map(part => part.substring(0, 1).toUpperCase + part.substring(1)).mkString
    if pascal.isEmpty then "Unnamed"
    else if pascal.charAt(0).isDigit then "N" + pascal
    else pascal
  }

  private final class Analyzer(
      schemas: JsonObject,
      overrides: Map[String, String],
      exclusions: Set[String],
      tagHints: List[String]
  ) {
    private val schemaList = schemas.toList
    private val schemaMap = schemaList.toMap
    private val failures = mutable.ListBuffer.empty[SchemaFailure]
    private val records = mutable.LinkedHashMap.empty[String, RecordDraft]
    private val enums = mutable.LinkedHashMap.empty[String, EnumDef]
    private val sums = mutable.LinkedHashMap.empty[String, SumDraft]
    private val aliases = mutable.LinkedHashMap.empty[String, AliasDef]
    private val parents = mutable.LinkedHashMap.empty[String, mutable.LinkedHashSet[String]]
    private val nullableNominal = mutable.LinkedHashSet.empty[String]
    private val reserved = mutable.LinkedHashSet.empty[String]
    private val visiting = mutable.LinkedHashSet.empty[String]
    private val stringEnumNames = mutable.LinkedHashSet.empty[String]

    def run(): Either[List[SchemaFailure], Model] = {
      schemaList.foreach { (raw, _) => reserved += toPascal(raw) }
      schemaList.foreach { (raw, _) => ensure(raw) }
      checkParents()
      val expanded = expandDefinitions()
      checkExtendsCycles()
      if failures.nonEmpty then Left(failures.toList.distinct)
      else {
        val definitions = freeze(expanded)
        val examples = exampleSpecs()
        Right(Model(definitions, examples))
      }
    }

    private def ensure(raw: String): Unit = {
      val name = toPascal(raw)
      if isDefined(name) || visiting.contains(name) then ()
      else
        schemaMap.get(raw) match {
          case None =>
            fail(raw, "unknown schema " + raw)
          case Some(schema) =>
            visiting += name
            resolve(schema, raw, name, Some(name))
            visiting -= name
        }
    }

    private def resolve(schema: Json, path: String, hint: String, bind: Option[String]): Resolved =
      overrides.get(path) match {
        case Some(scalaType) =>
          val external = TypeRef.External(scalaType)
          bind.foreach { name =>
            if !isDefined(name) then aliases(name) = AliasDef(name, external)
          }
          Resolved(external, None)
        case None if schema.isBoolean && schema.asBoolean.contains(true) =>
          Resolved(TypeRef.JsonType, None)
        case None =>
          val (core, nullable) = peel(schema)
          if nullable then bind.foreach(nullableNominal += _)
          val resolved = resolveCore(core, path, hint, bind)
          val nominal = bind.exists(isNominal)
          if nullable && bind.exists(name => !isNominal(name)) then bind.foreach(nullableNominal -= _)
          if bind.exists(name => !nominal && !isDefined(name)) then
            val stored = if nullable then optional(resolved.tpe) else resolved.tpe
            aliases(bind.get) = AliasDef(bind.get, stored)
          val tpe = if nullable then optional(resolved.tpe) else resolved.tpe
          Resolved(tpe, if nullable then None else resolved.defaultLiteral)
      }

    private def resolveCore(schema: Json, path: String, hint: String, bind: Option[String]): Resolved = {
      val obj = schema.asObject match {
        case None =>
          return failResolved(path, "schema is not a JSON object")
        case Some(value) => value
      }
      unsupportedKey(obj, path) match {
        case Some(key) => return failResolved(path, s"unsupported JSON Schema keyword '$key'")
        case None      => ()
      }
      if obj.contains("allOf") then expandAllOf(schema, path, Set.empty) match {
        case Left(reason) => failResolved(path, reason)
        case Right(flat) if flat.asObject.exists(_.contains("allOf")) =>
          failResolved(path, "allOf did not flatten")
        case Right(flat) => resolveCore(flat, path, hint, bind)
      }
      else if isBareRef(obj) then resolveRef(obj, path)
      else if obj.contains("oneOf") || obj.contains("anyOf") then resolveCombinator(obj, path, hint, bind)
      else if obj.contains("enum") then resolveEnum(obj, path, hint, bind)
      else if obj.contains("const") then resolveConst(obj, path)
      else resolveTyped(obj, path, hint, bind)
    }

    private def resolveRef(obj: JsonObject, path: String): Resolved =
      obj("$ref").flatMap(_.asString) match {
        case None => failResolved(path, "missing $ref")
        case Some(ref) =>
          val prefix = "#/components/schemas/"
          if !ref.startsWith(prefix) || ref.stripPrefix(prefix).contains("/") then
            failResolved(path, s"$$ref must point at #/components/schemas/<Name>, got $ref")
          else
            val raw = ref.stripPrefix(prefix)
            if !schemaMap.contains(raw) then failResolved(path, s"unresolvable $$ref #/components/schemas/$raw")
            else {
              ensure(raw)
              val name = toPascal(raw)
              val named = TypeRef.Named(name)
              Resolved(if nullableNominal.contains(name) then optional(named) else named, None)
            }
      }

    private def resolveCombinator(obj: JsonObject, path: String, hint: String, bind: Option[String]): Resolved = {
      val key = if obj.contains("oneOf") then "oneOf" else "anyOf"
      val variants = obj(key).flatMap(_.asArray).getOrElse(Vector.empty)
      if variants.isEmpty then failResolved(path, s"empty $key")
      else if variants.forall(variant => isRecordLike(variant, Set.empty)) then {
        val name = bind.getOrElse(allocate(hint))
        if sums.contains(name) then Resolved(TypeRef.Named(name), None)
        else {
          sums(name) = SumDraft(Nil, None)
          val names = variants.zipWithIndex.map { (variant, index) =>
            defineVariant(variant, s"$path/$key/$index", name, index)
          }.toList
          names.foreach(child => addParent(child, name))
          val discriminator = buildDiscriminator(obj, names, variants)
          sums(name) = SumDraft(names, discriminator)
          Resolved(TypeRef.Named(name), None)
        }
      }
      else {
        val members = variants.zipWithIndex.map { (variant, index) =>
          resolve(variant, s"$path/$key/$index", s"${hint}Choice${index + 1}", None).tpe
        }.toList
        Resolved(collapseUnion(members, path), None)
      }
    }

    private def defineVariant(schema: Json, path: String, sumName: String, index: Int): String = {
      val (core, _) = peel(schema)
      core.asObject.flatMap(refRaw) match {
        case Some(raw) if schemaMap.contains(raw) =>
          ensure(raw)
          toPascal(raw)
        case _ =>
          val hint = tagHint(core).fold(s"${sumName}Variant${index + 1}")(tag => sumName + toPascal(tag))
          val name = allocate(hint)
          resolve(core, path, name, Some(name))
          if !isNominal(name) then fail(path, s"sum variant $name is not a record or sealed trait")
          name
      }
    }

    private def resolveEnum(obj: JsonObject, path: String, hint: String, bind: Option[String]): Resolved = {
      val values = obj("enum").flatMap(_.asArray).getOrElse(Vector.empty).filterNot(_.isNull)
      if values.isEmpty then failResolved(path, "enum has no values")
      else if bind.isEmpty && values.size == 1 then constResolved(values.head, obj("type").flatMap(_.asString), path)
      else {
        val kind = enumKind(values, obj("type").flatMap(_.asString), path)
        kind match {
          case None => failResolved(path, "mixed or unsupported enum values")
          case Some(valueType) =>
            val name = bind.getOrElse(allocate(hint))
            if !enums.contains(name) then enums(name) = buildEnum(name, valueType, values, path)
            if valueType == "String" then stringEnumNames += name
            Resolved(TypeRef.Named(name), None)
        }
      }
    }

    private def buildEnum(name: String, valueType: String, values: Vector[Json], path: String): EnumDef = {
      val used = mutable.LinkedHashSet("Unknown")
      val cases = values.flatMap { value =>
        val ident = valueType match {
          case "String" => enumIdent(value.asString.getOrElse(""), name, used)
          case "Long"   => enumIdent(numericIdent(value.asNumber.flatMap(_.toLong).get.toString), name, used)
          case _        => enumIdent(numericIdent(doubleText(value.asNumber.get)), name, used)
        }
        val literal = valueType match {
          case "String" => stringLit(value.asString.getOrElse(""))
          case "Long"   => value.asNumber.flatMap(_.toLong).fold("0L")(n => s"${n}L")
          case _        => doubleLit(value.asNumber.fold(0.0)(_.toDouble))
        }
        if ident.isEmpty then {
          fail(path, "enum value has no Scala name")
          None
        } else Some(EnumCase(ident, literal))
      }.toList
      EnumDef(name, valueType, cases, "Unknown")
    }

    private def enumKind(values: Vector[Json], typeName: Option[String], path: String): Option[String] =
      typeName match {
        case Some("string") =>
          if values.forall(_.isString) then Some("String") else None
        case Some("integer") =>
          if values.forall(_.asNumber.flatMap(_.toLong).isDefined) then Some("Long") else None
        case Some("number") =>
          if values.forall(_.asNumber.isDefined) then Some("Double") else None
        case Some(_) => None
        case None =>
          if values.forall(_.isString) then Some("String")
          else if values.forall(_.asNumber.flatMap(_.toLong).isDefined) then Some("Long")
          else if values.forall(_.asNumber.isDefined) then Some("Double")
          else None
      }

    private def resolveConst(obj: JsonObject, path: String): Resolved =
      obj("const") match {
        case None        => failResolved(path, "missing const")
        case Some(value) => constResolved(value, obj("type").flatMap(_.asString), path)
      }

    private def constResolved(value: Json, typeName: Option[String], path: String): Resolved =
      if value.isString then Resolved(TypeRef.StringType, value.asString.map(stringLit))
      else if value.isBoolean then
        Resolved(TypeRef.BooleanType, Some(if value.asBoolean.contains(true) then "true" else "false"))
      else if value.isNumber then numberResolved(value.asNumber.get, typeName, path)
      else failResolved(path, "unsupported const value")

    private def numberResolved(number: JsonNumber, typeName: Option[String], path: String): Resolved = {
      val asLong = number.toLong
      typeName match {
        case Some("integer") =>
          asLong match {
            case Some(value) => Resolved(TypeRef.LongType, Some(s"${value}L"))
            case None        => failResolved(path, "integer does not fit in Long")
          }
        case Some("number") => Resolved(TypeRef.DoubleType, Some(doubleLit(number.toDouble)))
        case _ =>
          asLong match {
            case Some(value) if number.toDouble == value.toDouble => Resolved(TypeRef.LongType, Some(s"${value}L"))
            case _                                                 => Resolved(TypeRef.DoubleType, Some(doubleLit(number.toDouble)))
          }
      }
    }

    private def resolveTyped(obj: JsonObject, path: String, hint: String, bind: Option[String]): Resolved = {
      val typeName = obj("type").flatMap(_.asString)
      if obj.contains("properties") || typeName.contains("object") then resolveObject(obj, path, hint, bind)
      else
        typeName match {
          case Some("string")  => Resolved(TypeRef.StringType, None)
          case Some("integer") => Resolved(TypeRef.LongType, None)
          case Some("number")  => Resolved(TypeRef.DoubleType, None)
          case Some("boolean") => Resolved(TypeRef.BooleanType, None)
          case Some("array") =>
            val items = obj("items").getOrElse(Json.obj())
            val element = resolve(items, s"$path/items", s"${hint}Item", None).tpe
            Resolved(TypeRef.ListOf(element), None)
          case Some("null") => Resolved(TypeRef.NullType, None)
          case Some(other)  => failResolved(path, s"unsupported type '$other'")
          case None if isFreeForm(Json.fromJsonObject(obj)) => Resolved(TypeRef.JsonType, None)
          case None                     => failResolved(path, "schema has no type")
        }
    }

    private def resolveObject(obj: JsonObject, path: String, hint: String, bind: Option[String]): Resolved = {
      val props = obj("properties").flatMap(_.asObject).getOrElse(JsonObject.empty)
      val additional = obj("additionalProperties")
      if props.isEmpty then additional match {
        case Some(value) if value.isBoolean && value.asBoolean.contains(false) =>
          defineRecord(JsonObject.empty, Set.empty, None, path, bind.getOrElse(allocate(hint)))
        case Some(value) if isFreeForm(value) =>
          Resolved(TypeRef.MapOf(TypeRef.JsonType), None)
        case Some(value) =>
          val mapped = resolve(value, s"$path/additionalProperties", s"${hint}Value", None).tpe
          Resolved(TypeRef.MapOf(mapped), None)
        case None =>
          Resolved(TypeRef.MapOf(TypeRef.JsonType), None)
      }
      else {
        val required = obj("required").flatMap(_.asArray).map(_.flatMap(_.asString).toSet).getOrElse(Set.empty)
        val name = bind.getOrElse(allocate(hint))
        defineRecord(props, required, additional, path, name)
      }
    }

    private def defineRecord(
        props: JsonObject,
        required: Set[String],
        additional: Option[Json],
        path: String,
        name: String
    ): Resolved = {
      if records.contains(name) then Resolved(TypeRef.Named(name), None)
      else {
        records(name) = RecordDraft(Nil)
        val fields = props.toList.flatMap { (jsonName, fieldSchema) =>
          if jsonName.contains("`") then fail(s"$path/properties/$jsonName", "field name contains a backtick")
          val resolved = resolve(fieldSchema, s"$path/properties/$jsonName", name + toPascal(jsonName), None)
          if resolved.tpe == TypeRef.NullType || resolved.tpe == TypeRef.Optional(TypeRef.NullType) then Nil
          else if required.contains(jsonName) then List(Field(jsonName, resolved.tpe, true, resolved.defaultLiteral, false))
          else List(Field(jsonName, optional(resolved.tpe), false, None, false))
        }
        val extra = syntheticMap(additional, path, name, fields.map(_.jsonName).toSet)
        records(name) = RecordDraft(fields ++ extra.toList)
        Resolved(TypeRef.Named(name), None)
      }
    }

    private def syntheticMap(
        additional: Option[Json],
        path: String,
        recordName: String,
        fieldNames: Set[String]
    ): Option[Field] =
      additional match {
        case Some(value) if !isFreeForm(value) && !(value.isBoolean && value.asBoolean.contains(false)) =>
          if fieldNames.contains("additionalProperties") then {
            fail(s"$path/additionalProperties", "cannot add a map field; additionalProperties is already a property")
            None
          } else {
            val mapped = resolve(value, s"$path/additionalProperties", recordName + "Additional", None).tpe
            Some(Field("additionalProperties", TypeRef.MapOf(mapped), false, Some("Map.empty"), true))
          }
        case _ => None
      }

    private def collapseUnion(members: List[TypeRef], path: String): TypeRef = {
      val flat = members.flatMap(flattenMember).filterNot(_ == TypeRef.Invalid)
      if members.exists(containsInvalid) then TypeRef.Invalid
      else if flat.isEmpty then fail(path, "empty union")
      else {
        val withoutLong =
          if flat.contains(TypeRef.DoubleType) && flat.contains(TypeRef.LongType) then flat.filterNot(_ == TypeRef.LongType)
          else flat
        val stringEnums = withoutLong.collect { case named @ TypeRef.Named(name) if stringEnumNames.contains(name) => named }
        val collapsed =
          if withoutLong.contains(TypeRef.StringType) then withoutLong.filterNot(stringEnums.toSet.contains)
          else withoutLong
        val distinct = collapsed.distinct
        val sorted = distinct.sortBy(member => (-specificity(member), render(member)))
        sorted match {
          case Nil         => fail(path, "empty union")
          case one :: Nil  => one
          case many        =>
            checkDistinguishable(many, path)
            TypeRef.Union(many)
        }
      }
    }

    private def flattenMember(tpe: TypeRef): List[TypeRef] = tpe match {
      case TypeRef.Union(members) => members.flatMap(flattenMember)
      case TypeRef.Invalid        => Nil
      case other                  => List(other)
    }

    private def checkDistinguishable(members: List[TypeRef], path: String): Unit = {
      members.groupBy(erasure).foreach { (key, group) =>
        if group.size > 1 then
          key match {
            case "List" => checkDistinguishable(group.collect { case TypeRef.ListOf(element) => element }, path)
            case "Map"  => checkDistinguishable(group.collect { case TypeRef.MapOf(value) => value }, path)
            case _      =>
              fail(path, s"union members cannot be told apart at runtime: ${group.map(render(_)).mkString(" | ")}")
          }
      }
    }

    private def expandDefinitions(): Unit = {
      val memo = mutable.HashMap.empty[String, TypeRef]
      def expandAlias(name: String, seen: Set[String]): TypeRef =
        memo.getOrElseUpdate(
          name,
          if seen.contains(name) then {
            fail(name, "cyclic type alias")
            TypeRef.Invalid
          } else expandType(aliases(name).tpe, seen + name)
        )
      def expandType(tpe: TypeRef, seen: Set[String]): TypeRef = tpe match {
        case TypeRef.Named(name) if aliases.contains(name) => expandAlias(name, seen)
        case TypeRef.Union(members) =>
          collapseUnion(members.map(member => expandType(member, seen)), "union")
        case TypeRef.Optional(inner) => optional(expandType(inner, seen))
        case TypeRef.ListOf(element) => TypeRef.ListOf(expandType(element, seen))
        case TypeRef.MapOf(value)    => TypeRef.MapOf(expandType(value, seen))
        case other                    => other
      }
      aliases.keys.toList.foreach { name =>
        aliases(name) = AliasDef(name, expandAlias(name, Set.empty))
      }
      records.keys.toList.foreach { name =>
        val draft = records(name)
        records(name) = draft.copy(fields = draft.fields.map { field =>
          field.copy(tpe = expandType(field.tpe, Set.empty))
        })
      }
    }

    private def freeze(ignored: Unit): List[Definition] = {
      val _ = ignored
      val recordDefs = records.toList.map { (name, draft) =>
        RecordDef(name, draft.fields, parentList(name))
      }
      val enumDefs = enums.values.toList
      val sumDefs = sums.toList.map { (name, draft) =>
        SumDef(name, parentList(name), draft.variants, draft.discriminator)
      }
      val aliasDefs = aliases.values.toList
      recordDefs ++ enumDefs ++ sumDefs ++ aliasDefs
    }

    private def exampleSpecs(): List[ExampleSpec] =
      schemaList.flatMap { (raw, schema) =>
        val name = toPascal(raw)
        if exclusions.contains(raw) || exclusions.contains(name) then Nil
        else
          schema.asObject.flatMap(_("example")) match {
            case None => Nil
            case Some(example) =>
              val scalaType =
                if nullableNominal.contains(name) && isNominal(name) then s"Option[$name]"
                else name
              if isDefined(name) then List(ExampleSpec(name, scalaType, example)) else Nil
          }
      }

    private def parentList(name: String): List[String] =
      parents.get(name).fold(List.empty[String])(_.toList.sorted)

    private def checkParents(): Unit =
      parents.foreach { (child, ps) =>
        if !records.contains(child) && !sums.contains(child) then
          fail(child, "sealed parent attached to a type that is not a record or sum")
        ps.foreach { parent =>
          if !sums.contains(parent) then fail(parent, "sealed parent is not a sum")
        }
      }

    private def checkExtendsCycles(): Unit = {
      val stack = mutable.LinkedHashSet.empty[String]
      def walk(name: String): Unit =
        if stack.contains(name) then fail(name, s"cyclic sealed hierarchy: ${stack.mkString(" -> ")} -> $name")
        else {
          stack += name
          parents.get(name).foreach(_.foreach(walk))
          stack -= name
        }
      (records.keys ++ sums.keys).foreach { name =>
        if !stack.contains(name) then walk(name)
      }
    }

    private def buildDiscriminator(
        obj: JsonObject,
        names: List[String],
        variants: Vector[Json]
    ): Option[Discriminator] = {
      val explicit = obj("discriminator").flatMap(_.asObject)
      val property = explicit.flatMap(_("propertyName")).flatMap(_.asString).orElse(inferProperty(variants))
      property.flatMap { prop =>
        val mapped = explicit
          .flatMap(_("mapping"))
          .flatMap(_.asObject)
          .map(_.toList.flatMap { (tag, ref) =>
            ref.asString.map(value => tag -> toPascal(value.split("/").last))
          })
          .getOrElse(Nil)
        val tagged = names.map { name =>
          val fromMap = mapped.collectFirst { case (tag, variant) if variant == name => tag }
          val fromConst = variants.zip(names).collectFirst {
            case (variant, variantName) if variantName == name => requiredOrAnyTag(variant, prop)
          }.flatten
          name -> fromMap.orElse(fromConst)
        }
        val tags = tagged.flatMap(_._2)
        if tagged.forall(_._2.isDefined) && tags.distinct.size == tags.size then
          Some(Discriminator(prop, tagged.map((name, tag) => tag.get -> name)))
        else None
      }
    }

    private def inferProperty(variants: Vector[Json]): Option[String] = {
      val keys = variants.flatMap { variant =>
        objectView(variant, Set.empty).toList.flatMap { obj =>
          obj("properties").flatMap(_.asObject).toList.flatMap(_.keys)
        }
      }.distinct
      val good = keys.filter { prop =>
        val tags = variants.map(variant => requiredTag(variant, prop))
        tags.forall(_.isDefined) && tags.flatten.distinct.size == tags.size
      }
      tagHints.find(good.contains).orElse(good.sorted.headOption)
    }

    private def requiredTag(schema: Json, property: String): Option[String] =
      objectView(schema, Set.empty).flatMap { obj =>
        val required = obj("required").flatMap(_.asArray).map(_.flatMap(_.asString).toSet).getOrElse(Set.empty)
        if !required.contains(property) then None
        else obj("properties").flatMap(_.asObject).flatMap(_(property)).flatMap(singleStringConst)
      }

    private def requiredOrAnyTag(schema: Json, property: String): Option[String] =
      requiredTag(schema, property).orElse {
        objectView(schema, Set.empty).flatMap { obj =>
          obj("properties").flatMap(_.asObject).flatMap(_(property)).flatMap(singleStringConst)
        }
      }

    private def objectView(schema: Json, seen: Set[String]): Option[JsonObject] = {
      val (core, _) = peel(schema)
      core.asObject.flatMap { obj =>
        if obj.contains("allOf") then
          expandAllOf(core, "discriminator", seen).toOption.flatMap(flat => objectView(flat, seen))
        else if isBareRef(obj) then
          refRaw(obj).flatMap { raw =>
            if seen.contains(raw) then None
            else schemaMap.get(raw).flatMap(target => objectView(target, seen + raw))
          }
        else if obj.contains("properties") then Some(obj)
        else None
      }
    }

    private def isRecordLike(schema: Json, seen: Set[String]): Boolean = {
      val (core, _) = peel(schema)
      core.asObject match {
        case None => false
        case Some(obj) if UnsupportedKeys.exists(obj.contains) => false
        case Some(obj) if obj.contains("allOf") =>
          expandAllOf(core, "record", seen) match {
            case Left(_) => false
            case Right(flat) if flat == core => false
            case Right(flat)                 => isRecordLike(flat, seen)
          }
        case Some(obj) if isBareRef(obj) =>
          refRaw(obj) match {
            case Some(raw) if seen.contains(raw) => true
            case Some(raw) =>
              schemaMap.get(raw) match {
                case Some(target) => isRecordLike(target, seen + raw)
                case None         => false
              }
            case None => false
          }
        case Some(obj) if obj.contains("oneOf") || obj.contains("anyOf") =>
          val key = if obj.contains("oneOf") then "oneOf" else "anyOf"
          val variants = obj(key).flatMap(_.asArray).getOrElse(Vector.empty)
          variants.nonEmpty && variants.forall(variant => isRecordLike(variant, seen))
        case Some(obj) if obj.contains("enum") || obj.contains("const") => false
        case Some(obj)                                                   => obj.contains("properties")
      }
    }

    private def expandAllOf(schema: Json, path: String, seen: Set[String]): Either[String, Json] = {
      val acc = AllOfAcc(schemaMap)
      acc.absorb(schema, path, seen)
      acc.error.toLeft(acc.toSchema)
    }

    private def peel(schema: Json): (Json, Boolean) =
      schema.asObject match {
        case None => (schema, false)
        case Some(obj) =>
          val nullableFlag = obj("nullable").flatMap(_.asBoolean).contains(true)
          if obj.contains("enum") then peelEnum(obj, nullableFlag)
          else if obj.contains("oneOf") || obj.contains("anyOf") then peelCombinator(obj, nullableFlag)
          else if obj("type").exists(_.isArray) then peelTypeArray(obj, nullableFlag)
          else (Json.fromJsonObject(obj.remove("nullable")), nullableFlag || typeContainsNull(obj))
      }

    private def peelEnum(obj: JsonObject, nullableFlag: Boolean): (Json, Boolean) = {
      val values = obj("enum").flatMap(_.asArray).getOrElse(Vector.empty)
      val nonNull = values.filterNot(_.isNull)
      val nullable = nullableFlag || nonNull.size != values.size || typeContainsNull(obj)
      val typed =
        if typeContainsNull(obj) then nonNullType(obj).fold(obj)(tpe => obj.add("type", Json.fromString(tpe)))
        else obj
      (Json.fromJsonObject(typed.remove("nullable").add("enum", Json.arr(nonNull*))), nullable)
    }

    private def peelCombinator(obj: JsonObject, nullableFlag: Boolean): (Json, Boolean) = {
      val key = if obj.contains("oneOf") then "oneOf" else "anyOf"
      val variants = obj(key).flatMap(_.asArray).getOrElse(Vector.empty)
      val nonNull = variants.filterNot(isNullSchema)
      val nullable = nullableFlag || nonNull.size != variants.size
      if nonNull.size == 1 && !obj.contains("discriminator") then {
        val (inner, innerNull) = peel(nonNull.head)
        (inner, nullable || innerNull)
      } else (Json.fromJsonObject(obj.remove("nullable").add(key, Json.arr(nonNull*))), nullable)
    }

    private def peelTypeArray(obj: JsonObject, nullableFlag: Boolean): (Json, Boolean) = {
      val types = obj("type").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString).toList
      val nonNull = types.filterNot(_ == "null")
      val nullable = nullableFlag || nonNull.size != types.size
      nonNull match {
        case Nil =>
          (Json.obj("type" -> Json.fromString("null")), true)
        case one :: Nil =>
          (Json.fromJsonObject(obj.remove("nullable").add("type", Json.fromString(one))), nullable)
        case many =>
          val parts = many.map(name => Json.obj("type" -> Json.fromString(name)))
          (Json.obj("anyOf" -> Json.arr(parts*)), nullable)
      }
    }

    private def isNullSchema(schema: Json): Boolean =
      schema.asObject.exists { obj =>
        obj("type").flatMap(_.asString).contains("null") ||
        obj("type").flatMap(_.asArray).exists(values => values.nonEmpty && values.forall(_.asString.contains("null"))) ||
        obj("const").exists(_.isNull) ||
        obj("enum").flatMap(_.asArray).exists(values => values.nonEmpty && values.forall(_.isNull))
      }

    private def typeContainsNull(obj: JsonObject): Boolean =
      obj("type").exists { tpe =>
        tpe.asString.contains("null") || tpe.asArray.exists(_.exists(_.asString.contains("null")))
      }

    private def nonNullType(obj: JsonObject): Option[String] =
      obj("type").flatMap { tpe =>
        tpe.asString.filter(_ != "null").orElse {
          tpe.asArray.flatMap(_.flatMap(_.asString).find(_ != "null"))
        }
      }

    private def isBareRef(obj: JsonObject): Boolean =
      obj.contains("$ref") && !obj.contains("allOf") && !obj.contains("oneOf") && !obj.contains("anyOf") &&
        !obj.contains("properties") && !obj.contains("enum") && !obj.contains("const")

    private def refRaw(obj: JsonObject): Option[String] =
      obj("$ref").flatMap(_.asString).map(_.stripPrefix("#/components/schemas/")).filterNot(_.contains("/"))

    private def isFreeForm(schema: Json): Boolean =
      schema.asBoolean.contains(true) || schema.asObject.exists { obj =>
        obj.isEmpty || obj.keys.forall(key => AnnotationKeys.contains(key) || key.startsWith("x-"))
      }

    private def unsupportedKey(obj: JsonObject, path: String): Option[String] =
      UnsupportedKeys.find(obj.contains)

    private def tagHint(schema: Json): Option[String] = {
      val props = schema.asObject.flatMap(_("properties")).flatMap(_.asObject).getOrElse(JsonObject.empty)
      def tag(key: String): Option[String] = props(key).flatMap(singleStringConst)
      tagHints.iterator.map(tag).collectFirst { case Some(value) => value }
    }

    private def singleStringConst(schema: Json): Option[String] = {
      val (core, _) = peel(schema)
      core.asObject.flatMap { obj =>
        obj("const").flatMap(_.asString).orElse {
          val values = obj("enum").flatMap(_.asArray).map(_.flatMap(_.asString)).getOrElse(Vector.empty)
          if values.size == 1 then Some(values.head) else None
        }
      }
    }

    private def optional(tpe: TypeRef): TypeRef = tpe match {
      case TypeRef.Optional(inner) => TypeRef.Optional(inner)
      case TypeRef.Invalid         => TypeRef.Invalid
      case other                    => TypeRef.Optional(other)
    }

    private def specificity(tpe: TypeRef): Int = tpe match {
      case TypeRef.JsonType | TypeRef.NullType | TypeRef.Invalid | TypeRef.External(_) => 0
      case TypeRef.StringType | TypeRef.DoubleType                  => 1
      case TypeRef.BooleanType | TypeRef.LongType                   => 2
      case TypeRef.Named(name) if stringEnumNames.contains(name)    => 4
      case TypeRef.Named(name) if enums.contains(name)              => 4
      case TypeRef.Named(_)                                         => 3
      case TypeRef.ListOf(element)                                  => 2 + specificity(element)
      case TypeRef.MapOf(value)                                     => 2 + specificity(value)
      case TypeRef.Optional(inner)                                  => specificity(inner)
      case TypeRef.Union(members)                                   => members.map(specificity).maxOption.getOrElse(0)
    }

    private def erasure(tpe: TypeRef): String = tpe match {
      case TypeRef.StringType     => "String"
      case TypeRef.LongType       => "Long"
      case TypeRef.DoubleType     => "Double"
      case TypeRef.BooleanType    => "Boolean"
      case TypeRef.JsonType => "Json"
      case TypeRef.NullType => "Null"
      case TypeRef.Named(name)    => "N:" + name
      case TypeRef.ListOf(_)      => "List"
      case TypeRef.MapOf(_)       => "Map"
      case TypeRef.Optional(_)    => "Option"
      case TypeRef.Union(_)       => "Union"
      case TypeRef.External(scala) => "E:" + scala
      case TypeRef.Invalid        => "Invalid"
    }

    private def containsInvalid(tpe: TypeRef): Boolean = tpe match {
      case TypeRef.Invalid         => true
      case TypeRef.ListOf(element) => containsInvalid(element)
      case TypeRef.MapOf(value)    => containsInvalid(value)
      case TypeRef.Optional(inner) => containsInvalid(inner)
      case TypeRef.Union(members)  => members.exists(containsInvalid)
      case _                        => false
    }

    private def allocate(hint: String): String = {
      val base = toPascal(hint) match {
        case "Unnamed" => "UnnamedSchema"
        case other     => other
      }
      var name = base
      var index = 2
      while reserved.contains(name) do {
        name = base + index
        index += 1
      }
      reserved += name
      name
    }

    private def enumIdent(raw: String, enumName: String, used: mutable.LinkedHashSet[String]): String = {
      val pascal = toPascal(raw)
      val base =
        if pascal == "Unknown" || pascal == enumName then pascal + "Value"
        else pascal
      var name = base
      var index = 2
      while used.contains(name) do {
        name = base + index
        index += 1
      }
      used += name
      name
    }

    private def numericIdent(text: String): String = {
      val body = text.replace("-", "Minus").replace(".", "Point").replace("+", "")
      "V" + body
    }

    private def addParent(child: String, parent: String): Unit =
      if child != parent then parents.getOrElseUpdate(child, mutable.LinkedHashSet.empty) += parent

    private def isDefined(name: String): Boolean =
      records.contains(name) || enums.contains(name) || sums.contains(name) || aliases.contains(name)

    private def isNominal(name: String): Boolean =
      records.contains(name) || enums.contains(name) || sums.contains(name)

    private def fail(path: String, reason: String): TypeRef = {
      failures += SchemaFailure(path, reason)
      TypeRef.Invalid
    }

    private def failResolved(path: String, reason: String): Resolved =
      Resolved(fail(path, reason), None)
  }

  private final class AllOfAcc(schemaMap: Map[String, Json]) {
    val props = mutable.LinkedHashMap.empty[String, Json]
    val required = mutable.LinkedHashSet.empty[String]
    var additional: Option[Json] = None
    var typeJson: Option[Json] = None
    var enumJson: Option[Json] = None
    var constJson: Option[Json] = None
    var combKey: Option[String] = None
    var combJson: Option[Json] = None
    var discriminator: Option[Json] = None
    var error: Option[String] = None

    def absorb(schema: Json, path: String, seen: Set[String]): Unit =
      if error.isDefined then ()
      else
        schema.asObject match {
          case None => error = Some(s"$path: allOf part is not an object")
          case Some(obj) if obj.contains("$ref") && !obj.contains("allOf") =>
            val raw = obj("$ref").flatMap(_.asString).getOrElse("").stripPrefix("#/components/schemas/")
            if seen.contains(raw) then error = Some(s"$path: cyclic allOf via $raw")
            else
              schemaMap.get(raw) match {
                case None         => error = Some(s"$path: unresolved $$ref $raw")
                case Some(target) => absorb(target, path, seen + raw)
              }
          case Some(obj) if obj.contains("allOf") =>
            obj("allOf").flatMap(_.asArray).getOrElse(Vector.empty).zipWithIndex.foreach { (part, index) =>
              absorb(part, s"$path/allOf/$index", seen)
            }
            absorbRest(obj.remove("allOf"), path)
          case Some(obj) => absorbRest(obj, path)
        }

    def toSchema: Json =
      if combKey.isDefined && props.isEmpty then {
        var obj = JsonObject.empty.add(combKey.get, combJson.get)
        discriminator.foreach(value => obj = obj.add("discriminator", value))
        typeJson.foreach(value => obj = obj.add("type", value))
        enumJson.foreach(value => obj = obj.add("enum", value))
        Json.fromJsonObject(obj)
      } else {
        var obj = JsonObject.empty
        if props.nonEmpty then obj = obj.add("properties", Json.fromJsonObject(JsonObject.fromIterable(props.toList)))
        if required.nonEmpty then obj = obj.add("required", Json.arr(required.toList.map(Json.fromString)*))
        additional.foreach(value => obj = obj.add("additionalProperties", value))
        typeJson.orElse(Option.when(props.nonEmpty)(Json.fromString("object"))).foreach(value => obj = obj.add("type", value))
        enumJson.foreach(value => obj = obj.add("enum", value))
        constJson.foreach(value => obj = obj.add("const", value))
        discriminator.foreach(value => obj = obj.add("discriminator", value))
        Json.fromJsonObject(obj)
      }

    private def absorbRest(obj: JsonObject, path: String): Unit = {
      obj("properties").flatMap(_.asObject).foreach { properties =>
        if combKey.isDefined then error = Some(s"$path: cannot merge properties into oneOf/anyOf")
        else properties.toList.foreach { (key, value) => props.put(key, value) }
      }
      obj("required").flatMap(_.asArray).foreach(_.flatMap(_.asString).foreach(required += _))
      obj("additionalProperties").foreach(value => additional = Some(value))
      obj("type").foreach(value => typeJson = Some(value))
      obj("enum").foreach(value => enumJson = Some(value))
      obj("const").foreach(value => constJson = Some(value))
      List("oneOf", "anyOf").foreach { key =>
        obj(key).foreach { value =>
          if props.nonEmpty then error = Some(s"$path: cannot merge $key into an object schema")
          else {
            combKey = Some(key)
            combJson = Some(value)
          }
        }
      }
      obj("discriminator").foreach(value => discriminator = Some(value))
    }
  }

  private def doubleLit(value: Double): String = {
    val text = java.lang.Double.toString(value)
    if text.contains('.') || text.contains('e') || text.contains('E') then text else text + ".0"
  }

  private def doubleText(number: JsonNumber): String =
    number.toLong.map(_.toString).getOrElse(doubleLit(number.toDouble))

  def stringLit(value: String): String = {
    val body = value.flatMap {
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case ch if ch.isControl => f"\\u${ch.toInt}%04x"
      case ch => ch.toString
    }
    "\"" + body + "\""
  }
}

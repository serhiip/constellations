import OpenRouterSchemaModel.*
import OpenRouterSchemaModel.TypeRef.*

import scala.collection.mutable

object OpenRouterSchemaEmitter {

  val ExplicitCodecFieldThreshold = 64
  private val DefinitionsPerFile = 40
  private val ChunkSize = 20

  private val Keywords = Set(
    "abstract", "case", "catch", "class", "def", "do", "else", "enum", "export", "extends", "false", "final",
    "finally", "for", "given", "if", "implicit", "import", "lazy", "match", "new", "null", "object", "override",
    "package", "private", "protected", "return", "sealed", "super", "then", "this", "throw", "trait", "true",
    "try", "type", "val", "var", "while", "with", "yield", "derives", "end", "extension", "using"
  )

  final case class GeneratedFiles(main: Map[String, String], testSource: String, examplesJson: String)

  def emit(model: SchemaModel, pkg: String, specUrl: String): GeneratedFiles = {
    val records = model.definitions.collect { case record: RecordDef => record.name -> record }.toMap
    val enums = model.definitions.collect { case enumDef: EnumDef => enumDef.name -> enumDef }.toMap
    val sums = model.definitions.collect { case sum: SumDef => sum.name -> sum }.toMap
    val context = Context(pkg, specUrl, records, enums, sums)
    val files = pack(model.definitions).zipWithIndex.map { (definitions, index) =>
      val fileName = f"OpenRouterSchema${index + 1}%02d.scala"
      fileName -> context.file(definitions)
    }.toMap
    val unions = collectUnions(model.definitions)
    GeneratedFiles(
      main = files + ("UnionCodecs.scala" -> context.unionCodecs(unions)),
      testSource = context.examplesSuite(model.examples),
      examplesJson = examplesJson(model.examples)
    )
  }

  private def examplesJson(examples: List[ExampleSpec]): String = {
    val fields = examples.map(example => example.name -> example.json)
    io.circe.JsonObject.fromIterable(fields).toJson.noSpaces
  }

  private def collectUnions(definitions: List[Definition]): List[Union] = {
    val found = mutable.LinkedHashMap.empty[String, Union]
    def walk(tpe: TypeRef): Unit = tpe match {
      case union @ Union(members) =>
        found.getOrElseUpdate(render(union), union)
        members.foreach(walk)
      case ListOf(element) => walk(element)
      case MapOf(value)    => walk(value)
      case Optional(inner) => walk(inner)
      case _               => ()
    }
    definitions.foreach {
      case record: RecordDef => record.fields.foreach(field => walk(field.tpe))
      case alias: AliasDef   => walk(alias.tpe)
      case _                 => ()
    }
    found.values.toList
  }

  private def pack(definitions: List[Definition]): List[List[Definition]] = {
    val names = definitions.map(_.name)
    val index = names.zipWithIndex.toMap
    val parent = Array.tabulate(names.size)(identity)
    def find(item: Int): Int =
      if parent(item) == item then item
      else {
        val root = find(parent(item))
        parent(item) = root
        root
      }
    def union(left: String, right: String): Unit = {
      val leftRoot = find(index(left))
      val rightRoot = find(index(right))
      if leftRoot != rightRoot then parent(leftRoot) = rightRoot
    }
    definitions.foreach {
      case sum: SumDef =>
        sum.variants.foreach(variant => if index.contains(variant) then union(sum.name, variant))
        sum.parents.foreach(parentName => if index.contains(parentName) then union(sum.name, parentName))
      case record: RecordDef =>
        record.parents.foreach(parentName => if index.contains(parentName) then union(record.name, parentName))
      case _ => ()
    }
    val groups = definitions.groupBy(defn => find(index(defn.name))).values.toList.sortBy(_.map(_.name).min)
    val packed = mutable.ListBuffer.empty[List[Definition]]
    val current = mutable.ListBuffer.empty[Definition]
    groups.foreach { group =>
      if current.nonEmpty && current.size + group.size > DefinitionsPerFile then {
        packed += current.toList
        current.clear()
      }
      current ++= group
    }
    if current.nonEmpty then packed += current.toList
    packed.toList.map(topo)
  }

  private def topo(definitions: List[Definition]): List[Definition] = {
    val byName = definitions.map(defn => defn.name -> defn).toMap
    val incoming = mutable.Map.empty[String, Int].withDefaultValue(0)
    val edges = mutable.Map.empty[String, List[String]].withDefaultValue(Nil)
    def parentsOf(defn: Definition): List[String] = defn match {
      case record: RecordDef => record.parents
      case sum: SumDef       => sum.parents
      case _                 => Nil
    }
    definitions.foreach { defn =>
      parentsOf(defn).foreach { parentName =>
        if byName.contains(parentName) then {
          edges(parentName) = defn.name :: edges(parentName)
          incoming(defn.name) = incoming(defn.name) + 1
        }
      }
    }
    val ready = mutable.Queue.from(definitions.map(_.name).sorted.filter(name => incoming(name) == 0))
    val ordered = mutable.ListBuffer.empty[Definition]
    while ready.nonEmpty do {
      val name = ready.dequeue()
      ordered += byName(name)
      edges(name).sorted.foreach { child =>
        incoming(child) = incoming(child) - 1
        if incoming(child) == 0 then ready.enqueue(child)
      }
    }
    if ordered.size == definitions.size then ordered.toList else definitions.sortBy(_.name)
  }

  private final class Context(
      pkg: String,
      specUrl: String,
      records: Map[String, RecordDef],
      enums: Map[String, EnumDef],
      sums: Map[String, SumDef]
  ) {
    def file(definitions: List[Definition]): String = {
      val body = definitions.map(emit).mkString("\n\n")
      s"""package $pkg
         |
         |// AUTO-GENERATED FROM $specUrl by OpenRouterCodegen. Do not edit.
         |import io.circe.*
         |import io.circe.syntax.*
         |
         |import UnionCodecs.given
         |
         |$body
         |""".stripMargin
    }

    def unionCodecs(unions: List[Union]): String = {
      val givens = unions.zipWithIndex.map(emitUnion).mkString("\n\n")
      s"""package $pkg
         |
         |// AUTO-GENERATED FROM $specUrl by OpenRouterCodegen. Do not edit.
         |import io.circe.*
         |import io.circe.syntax.*
         |
         |/** Codecs for Scala 3 unions produced from OpenRouter anyOf and oneOf. */
         |object UnionCodecs:
         |  def encodeErasedList(
         |      values: List[?],
         |      ifEmpty: Json,
         |      arms: ((Any => Boolean, List[?] => Json))*
         |  ): Json =
         |    values.headOption match
         |      case None => ifEmpty
         |      case Some(head) =>
         |        arms.collectFirst { case (matches, encode) if matches(head) => encode(values) }.getOrElse(
         |          throw IllegalArgumentException("No OpenRouter union arm matched list element " + head.getClass.getName)
         |        )
         |
         |  def encodeErasedMap(
         |      values: Map[String, ?],
         |      ifEmpty: Json,
         |      arms: ((Any => Boolean, Map[String, ?] => Json))*
         |  ): Json =
         |    values.values.headOption match
         |      case None => ifEmpty
         |      case Some(head) =>
         |        arms.collectFirst { case (matches, encode) if matches(head) => encode(values) }.getOrElse(
         |          throw IllegalArgumentException("No OpenRouter union arm matched map value " + head.getClass.getName)
         |        )
         |
         |$givens
         |
         |export UnionCodecs.given
         |""".stripMargin
    }

    def examplesSuite(examples: List[ExampleSpec]): String = {
      val tests = examples.map { example =>
        s"""  test(${stringLit(example.name)}):
           |    check[${example.scalaType}](${stringLit(example.name)})""".stripMargin
      }.mkString("\n\n")
      s"""package $pkg
         |
         |import io.circe.*
         |import io.circe.parser.{decode, parse}
         |import io.circe.syntax.*
         |import scala.io.Source
         |import scala.util.Using
         |
         |class SchemaExamplesSuite extends munit.FunSuite:
         |  private val examples: Map[String, Json] =
         |    val text = Using.resource(Source.fromResource("openrouter-schema-examples.json"))(_.mkString)
         |    parse(text).fold(err => throw err, _.asObject.get.toMap)
         |
         |$tests
         |
         |  private def check[A: Decoder: Encoder](name: String): Unit =
         |    val json = examples(name)
         |    val decoded = decode[A](json.noSpaces).fold(err => fail(name + " did not decode: " + err.getMessage), identity)
         |    val again = decode[A](Encoder[A].apply(decoded).noSpaces).fold(err => fail(name + " did not round-trip: " + err.getMessage), identity)
         |    assertEquals(again, decoded)
         |""".stripMargin
    }

    private def emit(definition: Definition): String = definition match {
      case record: RecordDef => emitRecord(record)
      case enumDef: EnumDef  => emitEnum(enumDef)
      case sum: SumDef       => emitSum(sum)
      case alias: AliasDef   => s"type ${alias.name} = ${render(alias.tpe)}"
    }

    private def emitRecord(record: RecordDef): String = {
      val fields = record.fields.map(emitField).mkString(",\n")
      val params = if record.fields.isEmpty then "" else s"(\n$fields\n)"
      val explicit = record.fields.size > ExplicitCodecFieldThreshold || record.fields.exists(_.syntheticMap)
      val body =
        if explicit then explicitCodec(record)
        else
          s"""  given Codec.AsObject[${record.name}] =
             |    val derivedCodec = Codec.AsObject.derived[${record.name}]
             |    Codec.AsObject.from(derivedCodec, derivedCodec.mapJsonObject(_.filter(${nullFilter(record)})))""".stripMargin
      s"""final case class ${record.name}$params${extendsClause(record.parents)}
         |object ${record.name}:
         |  import UnionCodecs.given
         |$body""".stripMargin
    }

    private def nullFilter(record: RecordDef): String = {
      val keep = record.fields.filter(field => field.required && field.tpe == TypeRef.JsonType).map(field => stringLit(field.jsonName))
      if keep.isEmpty then "!_._2.isNull"
      else s"{ case (key, json) => !json.isNull || Set(${keep.mkString(", ")}).contains(key) }"
    }

    private def emitField(field: Field): String = {
      val default =
        if field.syntheticMap then field.defaultLiteral.fold("")(literal => " = " + literal)
        else if !field.required then " = None"
        else field.defaultLiteral.fold("")(literal => " = " + literal)
      s"  ${scalaIdent(field.jsonName)}: ${render(field.tpe)}$default"
    }

    private def explicitCodec(record: RecordDef): String = {
      val plain = record.fields.filterNot(_.syntheticMap)
      val synthetic = record.fields.find(_.syntheticMap)
      val chunks = plain.grouped(ChunkSize).toList.zipWithIndex
      val readers = chunks.map { (fields, index) =>
        val reads = fields.zipWithIndex.map { (field, fieldIndex) =>
          val expr =
            if field.required && field.defaultLiteral.isDefined && !isOptional(field.tpe) then
              s"cursor.getOrElse[${render(field.tpe)}](${stringLit(field.jsonName)})(${field.defaultLiteral.get})"
            else s"cursor.get[${render(field.tpe)}](${stringLit(field.jsonName)})"
          s"    f$fieldIndex <- $expr"
        }
        val yielded = fields.indices.map(i => s"f$i").mkString(", ")
        s"""  private def read$index(cursor: HCursor) =
           |    for
           |${reads.mkString("\n")}
           |    yield ($yielded)""".stripMargin
      }
      val plainArgs = chunks.flatMap { (fields, index) =>
        if fields.size == 1 then List(s"p$index")
        else fields.indices.map(fieldIndex => s"p$index._${fieldIndex + 1}")
      }
      val args = plainArgs ++ synthetic.map(_ => "extra")
      val readLines = chunks.indices.map(index => s"        p$index <- read$index(cursor)") ++
        synthetic.map(field => s"        extra <- readAdditional(cursor)")
      val encoders = chunks.map { (fields, index) =>
        val pairs = fields.map { field =>
          s"${stringLit(field.jsonName)} -> value.${scalaIdent(field.jsonName)}.asJson"
        }
        val body = if pairs.isEmpty then "Nil" else s"List(\n    ${pairs.mkString(",\n    ")}\n  )"
        s"  private def encoded$index(value: ${record.name}): List[(String, Json)] = $body"
      }
      val knownExpr = if chunks.isEmpty then "Nil" else chunks.indices.map(index => s"encoded$index(value)").mkString(" ++ ")
      val knownKeys = plain.map(field => stringLit(field.jsonName)).mkString(", ")
      val additionalReader = synthetic.fold("") { field =>
        s"""  private def readAdditional(cursor: HCursor) =
           |    val rest = JsonObject.fromIterable(cursor.value.asObject.fold(Nil)(_.toList.filterNot((key, _) => Set($knownKeys).contains(key))))
           |    Decoder[${render(field.tpe)}].decodeJson(Json.fromJsonObject(rest))
           |"""
      }
      val extraEncode = synthetic match {
        case None =>
          s"JsonObject.fromIterable($knownExpr).filter(${nullFilter(record)})"
        case Some(field) =>
          s"""val known = JsonObject.fromIterable($knownExpr).filter(${nullFilter(record)})
             |      val extra = Encoder[${render(field.tpe)}].apply(value.${scalaIdent(field.jsonName)}).asObject.getOrElse(JsonObject.empty)
             |      JsonObject.fromIterable(extra.toList.filterNot((key, _) => Set($knownKeys).contains(key)) ++ known.toList)""".stripMargin
      }
      s"""${readers.mkString("\n")}
         |${encoders.mkString("\n")}
         |$additionalReader
         |  given Codec.AsObject[${record.name}] = Codec.AsObject.from(
         |    Decoder.instance { cursor =>
         |      for
         |${readLines.mkString("\n")}
         |      yield ${record.name}(${args.mkString(", ")})
         |    },
         |    Encoder.AsObject.instance { value =>
         |      $extraEncode
         |    }
         |  )""".stripMargin
    }

    private def emitEnum(enumDef: EnumDef): String = {
      val valueType = enumDef.valueType
      val cases = enumDef.cases.map(enumCase => s"  case ${enumCase.ident} extends ${enumDef.name}(${enumCase.literal})").mkString("\n")
      val (encode, decode) = valueType match {
        case "String" => ("Encoder.encodeString", "Decoder.decodeString")
        case "Long"   => ("Encoder.encodeLong", "Decoder.decodeLong")
        case _        => ("Encoder.encodeDouble", "Decoder.decodeDouble")
      }
      s"""enum ${enumDef.name}(val value: $valueType):
         |$cases
         |  case ${enumDef.unknownIdent}(raw: $valueType) extends ${enumDef.name}(raw)
         |object ${enumDef.name}:
         |  val known: List[${enumDef.name}] = List(${enumDef.cases.map(_.ident).mkString(", ")})
         |  given Encoder[${enumDef.name}] = $encode.contramap(_.value)
         |  given Decoder[${enumDef.name}] = $decode.map { text =>
         |    known.find(_.value == text).getOrElse(${enumDef.unknownIdent}(text))
         |  }""".stripMargin
    }

    private def emitSum(sum: SumDef): String = {
      val variants = sum.variants.filter(name => records.contains(name) || sums.contains(name))
      val recordsFirst = variants.filter(records.contains) ++ variants.filter(sums.contains)
      val encoderCases = recordsFirst.map { name =>
        s"    case value: $name => Encoder.AsObject[$name].encodeObject(value)"
      }.mkString("\n")
      val decoder = sum.discriminator match {
        case Some(discriminator) =>
          val cases = discriminator.tags.map { (tag, name) =>
            s"      case ${stringLit(tag)} => cursor.as[$name]"
          }.mkString("\n")
          s"""Decoder.instance { cursor =>
             |    cursor.get[String](${stringLit(discriminator.property)}).flatMap {
             |$cases
             |      case other => Left(DecodingFailure("Unknown ${sum.name} ${discriminator.property}: " + other, cursor.history))
             |    }
             |  }""".stripMargin
        case None =>
          val ordered = variants.zipWithIndex.sortBy { (name, index) =>
            val requiredCount = records.get(name).fold(0)(_.fields.count(_.required))
            (-requiredCount, index)
          }.map(_._1)
          ordered.map(name => s"Decoder[$name].map[${sum.name}](value => value)").mkString(".or(") match {
            case single if !single.contains(".or(") => single
            case chain                               =>
              val pieces = ordered.map(name => s"Decoder[$name].map[${sum.name}](value => value)")
              pieces.tail.foldLeft(pieces.head)((acc, next) => s"$acc.or($next)")
          }
      }
      s"""sealed trait ${sum.name}${extendsClause(sum.parents)}
         |object ${sum.name}:
         |  import UnionCodecs.given
         |  given Encoder.AsObject[${sum.name}] = Encoder.AsObject.instance {
         |$encoderCases
         |  }
         |  given Decoder[${sum.name}] = $decoder""".stripMargin
    }

    private def emitUnion(indexed: (Union, Int)): String = {
      val (union, index) = indexed
      val rendered = render(union)
      val members = union.members
      val allStringEnums = members.forall {
        case Named(name) => enums.get(name).exists(_.valueType == "String")
        case _           => false
      }
      val decoder =
        if allStringEnums then stringEnumDecoder(rendered, members)
        else members.map(member => memberDecoder(member, rendered)).mkString("\n    .or(").replace("\n    .or(", ".or(") match {
          case text =>
            val parts = members.map(member => memberDecoder(member, rendered))
            parts.tail.foldLeft(parts.head)((acc, next) => s"$acc.or($next)")
        }
      s"""  given unionEncoder$index: Encoder[$rendered] = Encoder.instance {
         |${encodeBody(members)}
         |  }
         |  given unionDecoder$index: Decoder[$rendered] = $decoder""".stripMargin
    }

    private def stringEnumDecoder(rendered: String, members: List[TypeRef]): String = {
      val lookups = members.collect { case Named(name) =>
        s"""$name.known.find(_.value == text).map(value => (value: $rendered))"""
      }
      val first = members.collectFirst { case Named(name) => name }.get
      val unknown = enums(first).unknownIdent
      val chain = lookups.tail.foldLeft(lookups.head)((acc, next) => s"$acc.orElse($next)")
      s"""Decoder.instance { cursor =>
         |    cursor.as[String].map { text =>
         |      $chain.getOrElse(($first.$unknown(text): $rendered))
         |    }
         |  }"""
    }

    private def memberDecoder(tpe: TypeRef, rendered: String): String = tpe match {
      case Named(name) if enums.get(name).exists(_.valueType == "String") =>
        s"""Decoder.decodeString.emap(text => $name.known.find(_.value == text).toRight("Unknown " + text)).map[$rendered](value => value)"""
      case Named(name) if enums.get(name).exists(_.valueType == "Long") =>
        s"""Decoder.decodeLong.emap(number => $name.known.find(_.value == number).toRight("Unknown " + number)).map[$rendered](value => value)"""
      case Named(name) if enums.get(name).exists(_.valueType == "Double") =>
        s"""Decoder.decodeDouble.emap(number => $name.known.find(_.value == number).toRight("Unknown " + number)).map[$rendered](value => value)"""
      case other =>
        s"Decoder[${render(other)}].map[$rendered](value => value)"
    }

    private def encodeBody(members: List[TypeRef]): String = {
      val lists = members.collect { case list @ ListOf(_) => list }
      val maps = members.collect { case map @ MapOf(_) => map }
      val lines = mutable.ListBuffer.empty[String]
      var listsDone = false
      var mapsDone = false
      members.foreach {
        case ListOf(_) if !listsDone =>
          listsDone = true
          lines += listCase(lists)
        case ListOf(_) => ()
        case MapOf(_) if !mapsDone =>
          mapsDone = true
          lines += mapCase(maps)
        case MapOf(_) => ()
        case other => lines += simpleCase(other)
      }
      lines.mkString("\n")
    }

    private def simpleCase(tpe: TypeRef): String = tpe match {
      case StringType     => "    case value: String => Encoder[String].apply(value)"
      case LongType       => "    case value: Long => Encoder[Long].apply(value)"
      case DoubleType     => "    case value: Double => Encoder[Double].apply(value)"
      case BooleanType    => "    case value: Boolean => Encoder[Boolean].apply(value)"
      case JsonType       => "    case value: io.circe.Json => Encoder[io.circe.Json].apply(value)"
      case NullType       => "    case value: JsonNull => Encoder[JsonNull].apply(value)"
      case Named(name)    => s"    case value: $name => Encoder[$name].apply(value)"
      case Optional(_)    => s"    case value: Option[?] => Encoder[${render(tpe)}].apply(value.asInstanceOf[${render(tpe)}])"
      case External(scala) => s"    case value: $scala => Encoder[$scala].apply(value.asInstanceOf[$scala])"
      case other          => sys.error(s"No encoder case for ${render(other)}")
    }

    private def listCase(lists: List[ListOf]): String =
      if lists.size == 1 then
        val rendered = render(lists.head)
        s"    case value: List[?] => Encoder[$rendered].apply(value.asInstanceOf[$rendered])"
      else {
        val empty = s"Encoder[${render(lists.head)}].apply(Nil)"
        val arms = lists.map { list =>
          val rendered = render(list)
          s"""      ((head: Any) => ${elementPredicate(list.element, "head")}, (xs: List[?]) => Encoder[$rendered].apply(xs.asInstanceOf[$rendered]))"""
        }
        s"""    case value: List[?] =>
           |      UnionCodecs.encodeErasedList(
           |        value,
           |        $empty,
           |${arms.mkString(",\n")}
           |      )""".stripMargin
      }

    private def mapCase(maps: List[MapOf]): String =
      if maps.size == 1 then
        val rendered = render(maps.head)
        s"    case value: Map[?, ?] => Encoder[$rendered].apply(value.asInstanceOf[$rendered])"
      else {
        val empty = s"Encoder[${render(maps.head)}].apply(Map.empty)"
        val arms = maps.map { map =>
          val rendered = render(map)
          s"""      ((head: Any) => ${elementPredicate(map.value, "head")}, (xs: Map[String, ?]) => Encoder[$rendered].apply(xs.asInstanceOf[$rendered]))"""
        }
        s"""    case value: Map[?, ?] =>
           |      UnionCodecs.encodeErasedMap(
           |        value.asInstanceOf[Map[String, ?]],
           |        $empty,
           |${arms.mkString(",\n")}
           |      )""".stripMargin
      }

    private def elementPredicate(tpe: TypeRef, expr: String): String = tpe match {
      case StringType  => s"$expr.isInstanceOf[String]"
      case LongType    => s"$expr.isInstanceOf[Long]"
      case DoubleType  => s"$expr.isInstanceOf[Double]"
      case BooleanType => s"$expr.isInstanceOf[Boolean]"
      case JsonType    => s"$expr.isInstanceOf[io.circe.Json]"
      case NullType    => s"$expr.isInstanceOf[JsonNull]"
      case Named(name) => s"$expr.isInstanceOf[$name]"
      case ListOf(element) =>
        s"$expr.isInstanceOf[List[?]] && $expr.asInstanceOf[List[?]].headOption.forall(inner => ${elementPredicate(element, "inner")})"
      case MapOf(value) =>
        s"$expr.isInstanceOf[scala.collection.Map[?, ?]] && $expr.asInstanceOf[scala.collection.Map[?, ?]].values.headOption.forall(inner => ${elementPredicate(value, "inner")})"
      case Optional(inner) =>
        s"$expr.isInstanceOf[Option[?]] && $expr.asInstanceOf[Option[?]].forall(inner => ${elementPredicate(inner, "inner")})"
      case _ => "false"
    }

    private def extendsClause(parents: List[String]): String =
      if parents.isEmpty then "" else " extends " + parents.mkString(", ")

    private def isOptional(tpe: TypeRef): Boolean = tpe match {
      case Optional(_) => true
      case _           => false
    }

    private def scalaIdent(name: String): String = {
      val plain = name.matches("[A-Za-z_][A-Za-z0-9_]*") && !Keywords.contains(name)
      if plain then name else "`" + name + "`"
    }
  }
}

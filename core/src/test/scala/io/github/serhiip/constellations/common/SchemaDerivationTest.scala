package io.github.serhiip.constellations.common

import munit.FunSuite
import io.github.serhiip.constellations.schema.ToSchema

class SchemaDerivationSuite extends FunSuite:
  /** Address for a person. */
  case class Address(
      /** Street name. */
      street: String,
      /** Zip code. */
      zip: Int
  )

  /** Person record. */
  case class Person(
      name: String,
      age: Int,
      /** Optional nickname. */
      nickname: Option[String],
      address: Address,
      /** Tags for filtering. */
      tags: List[String] = List.empty
  )

  /** Account status. */
  enum Status:
    case Active, Inactive

  /** Complex enum. */
  enum ComplexEnum(val parent: String):
    case Child1(f: String) extends ComplexEnum("Child1")
    case Child2            extends ComplexEnum("Child2")

  /** Device mode. */
  enum DeviceMode:
    case Auto, Manual

  case class Profile(person: Person, status: Status, mode: DeviceMode)

  /** All supported field types. */
  case class SupportedFieldTypes(
      text: String,
      count: Int,
      total: Long,
      ratio: Double,
      weight: Float,
      active: Boolean,
      note: Option[String],
      list: List[Int],
      seq: Seq[Double],
      address: Address,
      status: Status,
      method: PaymentMethod,
      complex: ComplexEnum,
      mode: DeviceMode,
      optionalMode: Option[DeviceMode],
      optionalTags: Option[List[String]],
      defaulted: Int = 7
  )

  /** Docstring record. */
  @llmHint(description = Some("Annotated record"), title = Some("record"), minProperties = Some(1L), maxProperties = Some(5L))
  case class AnnotatedRecord(
      /** Docstring name. */
      @llmHint(
        description = Some("Annotated name"),
        minLength = Some(2L),
        maxLength = Some(20L),
        pattern = Some("[a-z]+"),
        example = Some("alice")
      )
      name: String,
      @llmHint(minimum = Some(0.0), maximum = Some(100.0))
      score: Double,
      @llmHint(minItems = Some(1L), maxItems = Some(3L))
      tags: List[String],
      @llmHint(nullable = Some(true), default = Some("unknown"))
      note: String
  )

  /** Hint coverage record. */
  case class HintCoverage(
      @llmHint(
        format = Some("email"),
        title = Some("email-title"),
        description = Some("Email address"),
        nullable = Some(false),
        default = Some("user@example.com"),
        minLength = Some(5L),
        maxLength = Some(100L),
        pattern = Some(".+@.+"),
        example = Some("a@b.com"),
        enm = Some(List("a@b.com", "c@d.com"))
      )
      email: String
  )

  /** Docstring fallback record. */
  @llmHint(title = Some("fallback"))
  case class DocFallback(
      /** Docstring label. */
      @llmHint(minLength = Some(3L))
      label: String
  )

  /** Docstring fallback enum. */
  @llmHint(title = Some("enum-fallback"))
  enum DocFallbackEnum:
    case Alpha, Beta

  /** Docstring fallback trait. */
  @llmHint(title = Some("trait-fallback"))
  sealed trait DocFallbackTrait

  object DocFallbackTrait:
    case object One                  extends DocFallbackTrait
    final case class Two(value: Int) extends DocFallbackTrait

  /** Payment method. */
  sealed trait PaymentMethod

  object PaymentMethod:
    /** Credit card. */
    final case class Card(last4: String) extends PaymentMethod
    case object Cash                     extends PaymentMethod
    final case class Wire(bank: String)  extends PaymentMethod

  private val expectedAddressSchema = Schema.obj(
    properties = Map(
      "street" -> Schema.string(),
      "zip"    -> Schema.integer()
    ),
    required = List("street", "zip")
  )

  private val expectedPersonSchema = Schema.obj(
    properties = Map(
      "name"     -> Schema.string(),
      "age"      -> Schema.integer(),
      "nickname" -> Schema.string().copy(nullable = Some(true)),
      "address"  -> expectedAddressSchema,
      "tags"     -> Schema.array(items = Schema.string())
    ),
    required = List("name", "age", "address")
  )

  private val expectedAnnotatedSchema =
    Schema
      .obj(
        description = Some("Annotated record"),
        minProperties = Some(1),
        maxProperties = Some(5),
        properties = Map(
          "name"  -> Schema
            .string(
              description = Some("Annotated name"),
              minLength = Some(2),
              maxLength = Some(20),
              pattern = Some("[a-z]+")
            )
            .copy(example = Some("alice")),
          "score" -> Schema.number(minimum = Some(0.0), maximum = Some(100.0)),
          "tags"  -> Schema.array(items = Schema.string(), minItems = Some(1), maxItems = Some(3)),
          "note"  -> Schema.string(nullable = Some(true)).copy(default = Some("unknown"))
        ),
        required = List("name", "score", "tags", "note")
      )
      .copy(title = Some("record"))

  private val expectedHintCoverageSchema = Schema.obj(
    properties = Map(
      "email" -> Schema
        .string(
          format = Some("email"),
          description = Some("Email address"),
          nullable = Some(false),
          minLength = Some(5),
          maxLength = Some(100),
          pattern = Some(".+@.+"),
          enm = List("a@b.com", "c@d.com")
        )
        .copy(title = Some("email-title"), default = Some("user@example.com"), example = Some("a@b.com"))
    ),
    required = List("email")
  )

  private val expectedDocFallbackSchema =
    Schema
      .obj(
        properties = Map(
          "label" -> Schema.string(minLength = Some(3))
        ),
        required = List("label")
      )
      .copy(title = Some("fallback"))

  private val expectedDocFallbackEnumSchema =
    Schema.string(enm = List("Alpha", "Beta")).copy(title = Some("enum-fallback"))

  private val expectedDocFallbackTraitSchema =
    Schema.string(enm = List("One", "Two")).copy(title = Some("trait-fallback"))

  private val expectedSupportedFieldSchema = Schema.obj(
    properties = Map(
      "text"         -> Schema.string(),
      "count"        -> Schema.integer(),
      "total"        -> Schema.integer(),
      "ratio"        -> Schema.number(),
      "weight"       -> Schema.number(),
      "active"       -> Schema.boolean(),
      "note"         -> Schema.string().copy(nullable = Some(true)),
      "list"         -> Schema.array(items = Schema.integer()),
      "seq"          -> Schema.array(items = Schema.number()),
      "address"      -> expectedAddressSchema,
      "status"       -> Schema.string(enm = List("Active", "Inactive")),
      "method"       -> Schema.string(enm = List("Card", "Cash", "Wire")),
      "complex"      -> Schema.string(enm = List("Child1", "Child2")),
      "mode"         -> Schema.string(enm = List("Auto", "Manual")),
      "optionalMode" -> Schema
        .string(enm = List("Auto", "Manual"))
        .copy(nullable = Some(true)),
      "optionalTags" -> Schema.array(items = Schema.string()).copy(nullable = Some(true)),
      "defaulted"    -> Schema.integer()
    ),
    required = List(
      "text",
      "count",
      "total",
      "ratio",
      "weight",
      "active",
      "list",
      "seq",
      "address",
      "status",
      "method",
      "complex",
      "mode"
    )
  )

  test("Schema.derived should derive schema for case classes") {
    val schema = Schema.derived[Person]

    assertEquals(schema, expectedPersonSchema)
  }

  test("Schema.derived should derive schema for enums") {
    val schema = Schema.derived[Status]

    assertEquals(schema, Schema.string(enm = List("Active", "Inactive")))
  }

  test("Schema.derived should derive schema for complex enums") {
    val schema = Schema.derived[ComplexEnum]

    assertEquals(schema, Schema.string(enm = List("Child1", "Child2")))
  }

  test("Schema.derived should derive schema for nested enums and case classes") {
    val schema = Schema.derived[Profile]

    val expected = Schema.obj(
      properties = Map(
        "person" -> expectedPersonSchema,
        "status" -> Schema.string(enm = List("Active", "Inactive")),
        "mode"   -> Schema.string(enm = List("Auto", "Manual"))
      ),
      required = List("person", "status", "mode")
    )

    assertEquals(schema, expected)
  }

  test("Schema.derived should derive schema for all supported field types") {
    val schema = Schema.derived[SupportedFieldTypes]

    assertEquals(schema, expectedSupportedFieldSchema)
  }

  test("Schema.derived should apply llmHint overrides") {
    val schema = Schema.derived[AnnotatedRecord]

    assertEquals(schema, expectedAnnotatedSchema)
  }

  test("Schema.derived should cover all llmHint fields") {
    val schema = Schema.derived[HintCoverage]

    assertEquals(schema, expectedHintCoverageSchema)
  }

  test("Schema.derived should use docstring fallback with llmHint") {
    val schema = Schema.derived[DocFallback]

    assertEquals(schema, expectedDocFallbackSchema)
  }

  test("Schema.derived should use docstring fallback for enums with llmHint") {
    val schema = Schema.derived[DocFallbackEnum]

    assertEquals(schema, expectedDocFallbackEnumSchema)
  }

  test("Schema.derived should use docstring fallback for sealed traits with llmHint") {
    val schema = Schema.derived[DocFallbackTrait]

    assertEquals(schema, expectedDocFallbackTraitSchema)
  }

  test("Schema.derived should derive schema for sealed trait hierarchies") {
    val schema = Schema.derived[PaymentMethod]

    assertEquals(schema, Schema.string(enm = List("Card", "Cash", "Wire")))
  }

  test("Structured typeclass should provide derived schema for case classes") {
    val schema = ToSchema[Person].schema

    assertEquals(schema, expectedPersonSchema)
  }

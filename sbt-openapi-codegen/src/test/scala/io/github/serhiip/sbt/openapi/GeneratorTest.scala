package io.github.serhiip.sbt.openapi

import scala.io.Source
import scala.util.Using

class GeneratorTest extends munit.FunSuite {

  private def text(name: String): String =
    Using.resource(Source.fromResource(s"specs/$name"))(_.mkString)

  private def spec(name: String, packageName: String = "demo"): OpenApiSpec =
    OpenApiSpec(name, OpenApiSource.FromFile(new java.io.File(name)), packageName)

  private def generated(resource: String, name: String): ScalaEmitter.GeneratedFiles =
    Generator.generate(text(resource), resource, spec(name), resource, "test").fold(err => fail(err.mkString("\n")), _.files)

  test("OpenAPI 3.0 YAML generates allOf, oneOf, nullable and enum") {
    val files = generated("shop.yaml", "Shop")
    val source = files.main.values.mkString("\n")
    assert(source.contains("sealed trait Animal"))
    assert(source.contains("enum PetStatus"))
    assert(source.contains("nickname: Option[String]"))
    assert(files.testSource.exists(_.contains("class ShopSchemaExamplesSuite")))
    assert(files.examplesJson.exists(_.contains("doggo")))
  }

  test("OpenAPI 3.1 JSON generates type arrays and const") {
    val source = generated("counts.json", "Counts").main.values.mkString("\n")
    assert(source.contains("value: Option[Long]"))
    assert(source.contains(""""count""""))
  }

  test("Petstore 3.0 generates the Pet schema and its example") {
    val files = generated("petstore.json", "Petstore")
    val source = files.main.values.mkString("\n")
    assert(source.contains("final case class Pet"))
    assert(source.contains("final case class Order"))
    assert(files.testSource.exists(_.contains("check[Pet]")))
  }

  test("Swagger 2.0 is rejected") {
    val result = Generator.generate(text("swagger.yaml"), "swagger.yaml", spec("Old"), "swagger.yaml", "test")
    assertEquals(result.left.map(_.mkString), Left("Swagger 2.0 specs are not supported; convert the spec to OpenAPI 3"))
  }

  test("a $ref outside components.schemas reports its path") {
    val result = Generator.generate(text("bad-ref.json"), "bad-ref.json", spec("Bad"), "bad-ref.json", "test")
    val message = result.left.map(_.mkString("\n")).fold(identity, _ => "")
    assert(message.contains("Thing"))
    assert(message.contains("#/components/parameters/Nope"))
  }

  test("two specs that emit the same name in one package collide") {
    val message = Generator.collisions(
      List(
        ("Widgets", "demo.api", Set("Widget", "Shared")),
        ("Gadgets", "demo.api", Set("Gadget", "Shared"))
      )
    )
    assertEquals(message, List("Widgets and Gadgets both generate Shared in package demo.api"))
  }

  test("specs in different packages may reuse a name") {
    val message = Generator.collisions(
      List(
        ("Widgets", "demo.widgets", Set("Shared")),
        ("Gadgets", "demo.gadgets", Set("Shared"))
      )
    )
    assertEquals(message, Nil)
  }
}

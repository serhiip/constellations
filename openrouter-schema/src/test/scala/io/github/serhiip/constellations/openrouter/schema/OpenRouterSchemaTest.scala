package io.github.serhiip.constellations.openrouter.schema

import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*

import chat.messages.{ChatMessages, ChatUserMessage}
import chat.request.ChatRequest

class OpenRouterSchemaTest extends munit.FunSuite {

  private def user(content: String): ChatMessages =
    ChatUserMessage(content = content)

  test("ChatRequest stop accepts a string or a list of strings") {
    val asString = ChatRequest(messages = List(user("hi")), stop = Some("END"))
    val asList = ChatRequest(messages = List(user("hi")), stop = Some(List("a", "b")))

    val decodedString = decode[ChatRequest](asString.asJson.noSpaces)
    val decodedList = decode[ChatRequest](asList.asJson.noSpaces)
    assertEquals(decodedString, Right(asString))
    assertEquals(decodedList, Right(asList))
  }

  test("ChatMessages decodes by the role discriminator") {
    val message = ChatUserMessage(content = "hello")
    val json = (message: ChatMessages).asJson
    assertEquals(json.hcursor.get[String]("role"), Right("user"))
    assert(!json.asObject.get.contains("name"))
    assertEquals(decode[ChatMessages](json.noSpaces), Right(message: ChatMessages))
  }

  test("list union encoding dispatches on the element type") {
    val nonempty = OpenRouterUnionCodecs.encodeErasedList(
      List("a", "b"),
      Json.arr(),
      ((head: Any) => head.isInstanceOf[String], (xs: List[?]) => xs.asInstanceOf[List[String]].asJson),
      ((head: Any) => head.isInstanceOf[Long], (xs: List[?]) => xs.asInstanceOf[List[Long]].asJson)
    )
    assertEquals(nonempty, Json.arr(Json.fromString("a"), Json.fromString("b")))

    val empty = OpenRouterUnionCodecs.encodeErasedList(
      List.empty[String],
      Json.arr(),
      ((head: Any) => head.isInstanceOf[String], (xs: List[?]) => xs.asInstanceOf[List[String]].asJson),
      ((head: Any) => head.isInstanceOf[Long], (xs: List[?]) => xs.asInstanceOf[List[Long]].asJson)
    )
    assertEquals(empty, Json.arr())
  }

  test("None fields are omitted") {
    val json = ChatRequest(messages = List(user("hi"))).asJson.asObject.get
    assert(!json.contains("temperature"))
    assert(!json.contains("stop"))
    assert(!json.contains("model"))
  }

  test("unknown enum values decode to Unknown") {
    assertEquals(
      decode[ChatFinishReasonEnum]("\"brand-new-reason\""),
      Right(ChatFinishReasonEnum.Unknown("brand-new-reason"))
    )
    assertEquals(decode[ChatFinishReasonEnum]("\"stop\""), Right(ChatFinishReasonEnum.Stop))
  }
}

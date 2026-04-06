package io.github.serhiip.constellations.embeddings

import cats.data.NonEmptyList as NEL
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite

import io.github.serhiip.constellations.embeddings.OpenRouter
import io.github.serhiip.constellations.openrouter.*

class OpenRouterEmbeddingsTest extends CatsEffectSuite:

  private class StubClient(requestRef: Ref[IO, Option[EmbeddingsRequest]], responseData: List[EmbeddingData]) extends Client[IO]:

    def createChatCompletion(request: ChatCompletionRequest): IO[ChatCompletionResponse] =
      IO.raiseError(RuntimeException("Not implemented"))

    def createCompletion(request: CompletionRequest): IO[CompletionResponse] =
      IO.raiseError(RuntimeException("Not implemented"))

    def createEmbeddings(request: EmbeddingsRequest): IO[EmbeddingsResponse] =
      requestRef.set(request.some) *>
        IO.pure(
          EmbeddingsResponse(
            `object` = "list",
            data = responseData,
            model = request.model,
            usage = EmbeddingsUsage(promptTokens = 10, totalTokens = 10)
          )
        )

    def listModels(): IO[ModelsResponse] =
      IO.raiseError(RuntimeException("Not implemented"))

    def getGenerationStats(generationId: String): IO[GenerationStats] =
      IO.raiseError(RuntimeException("Not implemented"))

  test("embed sends correct request and returns embedding") {
    val embedding    = List(0.1f, 0.2f, 0.3f)
    val responseData = List(EmbeddingData(`object` = "embedding", embedding = embedding, index = 0))

    for
      requestRef <- Ref.of[IO, Option[EmbeddingsRequest]](none)
      client      = new StubClient(requestRef, responseData)
      embeddings  = OpenRouter[IO](client, OpenRouter.Config(model = "text-embedding-3-small"))
      result     <- embeddings.embed("hello world")
      captured   <- requestRef.get
    yield
      assertEquals(result, NEL.fromListUnsafe(embedding))
      assert(captured.isDefined)
      assertEquals(captured.get.model, "text-embedding-3-small")
  }

  test("embed fails on empty response") {
    for
      requestRef <- Ref.of[IO, Option[EmbeddingsRequest]](none)
      client      = new StubClient(requestRef, Nil)
      embeddings  = OpenRouter[IO](client, OpenRouter.Config(model = "text-embedding-3-small"))
      result     <- embeddings.embed("hello").attempt
    yield assert(result.isLeft)
  }

  test("embedBatch sends correct request and returns embeddings in order") {
    val responseData = List(
      EmbeddingData(`object` = "embedding", embedding = List(0.1f, 0.1f), index = 1),
      EmbeddingData(`object` = "embedding", embedding = List(0.2f, 0.2f), index = 0)
    )

    for
      requestRef <- Ref.of[IO, Option[EmbeddingsRequest]](none)
      client      = new StubClient(requestRef, responseData)
      embeddings  = OpenRouter[IO](client, OpenRouter.Config(model = "text-embedding-3-small"))
      result     <- embeddings.embedBatch(NEL.of("first", "second"))
      captured   <- requestRef.get
    yield
      assertEquals(result, NEL.of(List(0.2f, 0.2f), List(0.1f, 0.1f)))
      assert(captured.isDefined)
      val inputJson = captured.get.input
      assert(inputJson.isArray)
  }

  test("embedBatch fails on dimension mismatch") {
    val responseData = List(
      EmbeddingData(`object` = "embedding", embedding = List(0.1f), index = 0),
      EmbeddingData(`object` = "embedding", embedding = List(0.2f, 0.2f), index = 1)
    )

    for
      requestRef <- Ref.of[IO, Option[EmbeddingsRequest]](none)
      client      = new StubClient(requestRef, responseData)
      embeddings  = OpenRouter[IO](client, OpenRouter.Config(model = "text-embedding-3-small"))
      result     <- embeddings.embedBatch(NEL.of("a", "b")).attempt
    yield assert(result.isLeft)
  }

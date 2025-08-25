package io.github.serhiip.constellations.openrouter

import cats.effect.{IO, Resource}
import cats.syntax.all.*

import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client as HTTPClient
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.log4cats.StructuredLogger

final class ClientTest extends CatsEffectSuite:

  private val testApiKey = "test-api-key"
  private val testConfig = Client.Config()

  private def createStubClient[F[_]: cats.effect.kernel.MonadCancelThrow](response: Response[F]): HTTPClient[F] =
    HTTPClient[F] { _ => Resource.pure(response) }

  private def createTestClient(stubClient: HTTPClient[IO]): Client[IO] =
    given StructuredLogger[IO] = NoOpLogger[IO]
    Client.apply[IO](stubClient, testApiKey, testConfig)

  private val sampleUsage = ChatCompletionUsage(promptTokens = 50, completionTokens = 15, totalTokens = 65)

  test("createCompletion should send correct request and parse response") {
    val expectedRequest = CompletionRequest(
      model = "test-model",
      prompt = "Write a short poem",
      temperature = 0.8.some,
      maxTokens = 50.some
    )

    val expectedResponse = CompletionResponse(
      id = "test-id",
      `object` = "text_completion",
      created = 1234567890L,
      model = "test-model",
      choices = List(
        CompletionChoice(
          text = "Roses are red,\nViolets are blue,\nCode is fun,\nAnd so are you!",
          index = 0.some,
          finishReason = "stop".some
        )
      ),
      usage = ChatCompletionUsage(promptTokens = 5, completionTokens = 25, totalTokens = 30)
    )

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(expectedResponse))

    val client = createTestClient(stubClient)
    client.createCompletion(expectedRequest).map { response =>
      assertEquals(response, expectedResponse)
    }
  }

  test("listModels should send correct request and parse response") {
    val expectedResponse = ModelsResponse(
      data = List(
        Model(
          id = "anthropic/claude-3.7-sonnet",
          name = "Claude 3.7 Sonnet",
          created = 1741818122L.some,
          description = "State-of-the-art general model".some,
          architecture = ModelArchitecture(inputModalities = List("text", "image"), outputModalities = List("text"), tokenizer = "GPT".some).some,
          topProvider = ModelTopProvider(isModerated = true, contextLength = 128000, maxCompletionTokens = 16384).some,
          pricing = ModelPricing(
            prompt = "0.0000007",
            completion = "0.0000007",
            image = "0".some,
            request = "0".some,
            webSearch = "0".some,
            internalReasoning = "0".some,
            inputCacheRead = "0".some,
            inputCacheWrite = "0".some
          ).some,
          canonicalSlug = "anthropic/claude-3.7-sonnet".some,
          contextLength = 128000.some,
          huggingFaceId = none,
          perRequestLimits = Some(PerRequestLimits()),
          supportedParameters = Some(List("temperature", "top_p"))
        )
      )
    )

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(expectedResponse))
    val client     = createTestClient(stubClient)

    client.listModels().map { response =>
      assertEquals(response, expectedResponse)
    }
  }

  test("getGenerationStats should send correct request and parse response") {
    val generationId     = "gen-1234567890"
    val expectedResponse = GenerationStats(
      id = generationId,
      totalCost = 0.05,
      createdAt = "2024-01-01T00:00:00Z",
      model = "gpt-4o",
      origin = "api",
      usage = 0.05,
      isByok = false,
      upstreamId = "upstream-123".some,
      cacheDiscount = none,
      upstreamInferenceCost = 0.05,
      appId = none,
      externalUser = none,
      streamed = false,
      cancelled = false,
      providerName = "OpenAI",
      latency = 1000,
      moderationLatency = none,
      generationTime = 800,
      finishReason = "stop",
      nativeFinishReason = "stop",
      tokensPrompt = 100,
      tokensCompletion = 50,
      nativeTokensPrompt = 100,
      nativeTokensCompletion = 50,
      nativeTokensReasoning = 0,
      nativeTokensCached = 0,
      numMediaPrompt = none,
      numMediaCompletion = none,
      numSearchResults = none
    )

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(GenerationStatsResponse(expectedResponse)))
    val client     = createTestClient(stubClient)

    client.getGenerationStats(generationId).map { response =>
      assertEquals(response, expectedResponse)
    }
  }

  test("createChatCompletion should handle multimodal content") {
    val multimodalRequest = ChatCompletionRequest(
      model = "gpt-4o",
      messages = List(
        ChatMessage(
          role = "user",
          content = Json
            .arr(
              Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString("What's in this image?")),
              Json.obj(
                "type"        -> Json.fromString("image_url"),
                "image_url"   -> Json.obj("url" -> Json.fromString("data:image/jpeg;base64,test"))
              )
            )
            .some
        )
      )
    )

    val expectedResponse = ChatCompletionResponse(
      id = "test-id",
      `object` = "chat.completion",
      created = 1234567890L,
      model = "gpt-4o",
      choices = List(
        ChatCompletionChoice(
          index = 0,
          message = ChatMessage(role = "assistant", content = Json.fromString("I can see an image in the message.").some),
          finishReason = "stop".some
        )
      ),
      usage = sampleUsage
    )

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(expectedResponse))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(multimodalRequest).map { response =>
      assertEquals(response, expectedResponse)
    }
  }

  test("should handle error responses gracefully") {
    val request = ChatCompletionRequest(
      model = "test-model",
      messages = List(ChatMessage(role = "user", content = Json.fromString("test").some))
    )

    val stubClient = createStubClient(Response[IO](Status.BadRequest).withEntity("Invalid request"))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(request).attempt.map { result =>
      assert(result.isLeft)
    }
  }

  test("should handle OpenRouter API error responses with proper format") {
    val request = ChatCompletionRequest(
      model = "test-model",
      messages = List(ChatMessage(role = "user", content = Json.fromString("test").some))
    )

    val errorResponse = ErrorResponse(
      error = ErrorDetails(
        code = 400,
        message = "Bad Request: Invalid model",
        metadata = Some(Map("provider_name" -> Json.fromString("openai")))
      )
    )

    val stubClient = createStubClient(Response[IO](Status.BadRequest).withEntity(errorResponse))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(request).attempt.map { result =>
      assert(result.isLeft)
      result.left.map { error =>
        assert(error.getMessage.contains("OpenRouter API error: Bad Request: Invalid model (code: 400)"))
      }
    }
  }

  test("should handle OpenRouter API error responses without metadata") {
    val request = ChatCompletionRequest(
      model = "test-model",
      messages = List(ChatMessage(role = "user", content = Json.fromString("test").some))
    )

    val errorResponse = ErrorResponse(
      error = ErrorDetails(
        code = 401,
        message = "Invalid API key",
        metadata = None
      )
    )

    val stubClient = createStubClient(Response[IO](Status.Unauthorized).withEntity(errorResponse))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(request).attempt.map { result =>
      assert(result.isLeft)
      result.left.map { error =>
        assert(error.getMessage.contains("OpenRouter API error: Invalid API key (code: 401)"))
      }
    }
  }

  test("createChatCompletion should handle tool calls") {
    val searchTool = createSearchTool()

    val request = ChatCompletionRequest(
      model = "google/gemini-2.0-flash-001",
      messages = List(ChatMessage.user("What are the titles of some James Joyce books?")),
      tools = List(searchTool).some,
      toolChoice = ToolChoice.auto.some
    )

    val expectedResponse = ChatCompletionResponse(
      id = "test-id",
      `object` = "chat.completion",
      created = 1234567890L,
      model = "google/gemini-2.0-flash-001",
      choices = List(
        ChatCompletionChoice(
          index = 0,
          message = ChatMessage.assistantWithToolCalls(
            List(
              ToolCall(
                id = "call_abc123",
                `type` = "function",
                function = ToolCallFunction(
                  name = "search_gutenberg_books",
                  arguments = """{"search_terms": ["James", "Joyce"]}"""
                )
              )
            )
          ),
          finishReason = "tool_calls".some
        )
      ),
      usage = sampleUsage
    )

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(expectedResponse))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(request).map { response =>
      assertEquals(response, expectedResponse)
      assertEquals(response.choices.head.message.toolCalls.get.head.function.name, "search_gutenberg_books")
      assertEquals(response.choices.head.finishReason, "tool_calls".some)
    }
  }

  test("createChatCompletion should handle tool results") {
    val toolResultMessage = ChatMessage.tool(
      toolCallId = "call_abc123",
      name = "search_gutenberg_books",
      content = """[{"id": 4300, "title": "Ulysses", "authors": [{"name": "Joyce, James"}]}]"""
    )

    val request = ChatCompletionRequest(
      model = "google/gemini-2.0-flash-001",
      messages = List(
        ChatMessage.user("What are the titles of some James Joyce books?"),
        ChatMessage.assistantWithToolCalls(
          List(
            ToolCall(
              id = "call_abc123",
              `type` = "function",
              function = ToolCallFunction(
                name = "search_gutenberg_books",
                arguments = """{"search_terms": ["James", "Joyce"]}"""
              )
            )
          )
        ),
        toolResultMessage
      ),
      tools = List(createSearchTool()).some
    )

    val expectedResponse = ChatCompletionResponse(
      id = "test-id",
      `object` = "chat.completion",
      created = 1234567890L,
      model = "google/gemini-2.0-flash-001",
      choices = List(
        ChatCompletionChoice(
          index = 0,
          message = ChatMessage.assistant("Based on the search results, I found 'Ulysses' by James Joyce in the Project Gutenberg library."),
          finishReason = "stop".some
        )
      ),
      usage = ChatCompletionUsage(promptTokens = 100, completionTokens = 25, totalTokens = 125)
    )

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(expectedResponse))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(request).map { response =>
      assertEquals(response, expectedResponse)
      assertEquals(response.choices.head.finishReason, "stop".some)
    }
  }

  private def createSearchTool(): Tool =
    Tool.function(
      name = "search_gutenberg_books",
      description = "Search for books in the Project Gutenberg library".some,
      parameters = Json.obj(
        "type"       -> Json.fromString("object"),
        "properties" -> Json.obj(
          "search_terms" -> Json.obj(
            "type"        -> Json.fromString("array"),
            "items"       -> Json.obj("type" -> Json.fromString("string")),
            "description" -> Json.fromString("List of search terms to find books")
          )
        ),
        "required"   -> Json.arr(Json.fromString("search_terms"))
      )
    )

  test("should include attribution headers when configured") {
    val configWithAttribution = testConfig.copy(
      appUrl = "https://myapp.com".some,
      appTitle = "My AI Assistant".some
    )

    val expectedResponse = ChatCompletionResponse(
      id = "test-id",
      `object` = "chat.completion",
      created = 1234567890L,
      model = "gpt-4o",
      choices = List(
        ChatCompletionChoice(
          index = 0,
          message = ChatMessage.assistant("Hello!"),
          finishReason = "stop".some
        )
      ),
      usage = sampleUsage
    )

    val stubClient             = createStubClient(Response[IO](Status.Ok).withEntity(expectedResponse))
    given StructuredLogger[IO] = NoOpLogger[IO]
    val client                 = Client.apply[IO](stubClient, testApiKey, configWithAttribution)

    val request = ChatCompletionRequest(
      model = "gpt-4o",
      messages = List(ChatMessage.user("Hello"))
    )

    client.createChatCompletion(request).map { response =>
      assertEquals(response, expectedResponse)
    }
  }

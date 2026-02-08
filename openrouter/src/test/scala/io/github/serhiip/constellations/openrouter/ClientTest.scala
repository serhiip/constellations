package io.github.serhiip.constellations.openrouter

import cats.effect.{IO, Resource}
import cats.syntax.all.*

import io.circe.{Encoder, Json}
import io.circe.parser.*
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
          architecture = ModelArchitecture(
            inputModalities = Set(Modality.Text, Modality.Image),
            outputModalities = Set(Modality.Text),
            tokenizer = Tokenizer.GPT.some
          ).some,
          topProvider = ModelTopProvider(isModerated = true, contextLength = Some(128000), maxCompletionTokens = Some(16384)).some,
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
          supportedParameters = Some(List(SupportedParameter.Temperature, SupportedParameter.TopP))
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

    val errorDetails  = ErrorDetails(
      code = 400,
      message = "Bad Request: Invalid model",
      metadata = Some(Map("provider_name" -> Json.fromString("openai")))
    )
    val errorResponse = ErrorResponse(error = errorDetails)

    val stubClient = createStubClient(Response[IO](Status.BadRequest).withEntity(errorResponse))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(request).attempt.map { result =>
      assert(result.isLeft)
      result.left.map { error =>
        assertEquals(error, Client.Error.BadRequest("Bad Request: Invalid model", errorDetails))
        assert(error.getMessage.contains("Bad Request: Bad Request: Invalid model (code: 400)"))
      }
    }
  }

  test("should handle OpenRouter API error responses without metadata") {
    val request = ChatCompletionRequest(
      model = "test-model",
      messages = List(ChatMessage(role = "user", content = Json.fromString("test").some))
    )

    val errorDetails  = ErrorDetails(
      code = 401,
      message = "Invalid API key",
      metadata = None
    )
    val errorResponse = ErrorResponse(error = errorDetails)

    val stubClient = createStubClient(Response[IO](Status.Unauthorized).withEntity(errorResponse))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(request).attempt.map { result =>
      assert(result.isLeft)
      result.left.map { error =>
        assertEquals(error, Client.Error.Unauthorized("Invalid API key", errorDetails))
        assert(error.getMessage.contains("Unauthorized: Invalid API key (code: 401)"))
      }
    }
  }

  test("should raise BadRequest domain error for 400 status") {
    val request = ChatCompletionRequest(
      model = "test-model",
      messages = List(ChatMessage(role = "user", content = Json.fromString("test").some))
    )

    val errorDetails  = ErrorDetails(
      code = 400,
      message = "Invalid model parameter",
      metadata = Some(Map("field" -> Json.fromString("model")))
    )
    val errorResponse = ErrorResponse(error = errorDetails)

    val stubClient = createStubClient(Response[IO](Status.BadRequest).withEntity(errorResponse))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(request).attempt.map { result =>
      assert(result.isLeft)
      result.left.map { error =>
        assertEquals(error, Client.Error.BadRequest("Invalid model parameter", errorDetails))
        assert(error.getMessage.contains("Bad Request: Invalid model parameter (code: 400)"))
      }
    }
  }

  test("should raise Unauthorized domain error for 401 status") {
    val request = ChatCompletionRequest(
      model = "test-model",
      messages = List(ChatMessage(role = "user", content = Json.fromString("test").some))
    )

    val errorDetails  = ErrorDetails(
      code = 401,
      message = "Invalid API key",
      metadata = None
    )
    val errorResponse = ErrorResponse(error = errorDetails)

    val stubClient = createStubClient(Response[IO](Status.Unauthorized).withEntity(errorResponse))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(request).attempt.map { result =>
      assert(result.isLeft)
      result.left.map { error =>
        assertEquals(error, Client.Error.Unauthorized("Invalid API key", errorDetails))
        assert(error.getMessage.contains("Unauthorized: Invalid API key (code: 401)"))
      }
    }
  }

  test("should raise TooManyRequests domain error for 429 status") {
    val request = ChatCompletionRequest(
      model = "test-model",
      messages = List(ChatMessage(role = "user", content = Json.fromString("test").some))
    )

    val errorDetails  = ErrorDetails(
      code = 429,
      message = "Rate limit exceeded",
      metadata = Some(Map("retry_after" -> Json.fromInt(60)))
    )
    val errorResponse = ErrorResponse(error = errorDetails)

    val stubClient = createStubClient(Response[IO](Status.TooManyRequests).withEntity(errorResponse))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(request).attempt.map { result =>
      assert(result.isLeft)
      result.left.map { error =>
        assertEquals(error, Client.Error.TooManyRequests("Rate limit exceeded", errorDetails))
        assert(error.getMessage.contains("Too Many Requests: Rate limit exceeded (code: 429)"))
      }
    }
  }

  test("should raise InternalServerError domain error for 500 status") {
    val request = ChatCompletionRequest(
      model = "test-model",
      messages = List(ChatMessage(role = "user", content = Json.fromString("test").some))
    )

    val errorDetails  = ErrorDetails(
      code = 500,
      message = "Internal server error",
      metadata = None
    )
    val errorResponse = ErrorResponse(error = errorDetails)

    val stubClient = createStubClient(Response[IO](Status.InternalServerError).withEntity(errorResponse))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(request).attempt.map { result =>
      assert(result.isLeft)
      result.left.map { error =>
        assertEquals(error, Client.Error.InternalServerError("Internal server error", errorDetails))
        assert(error.getMessage.contains("Internal Server Error: Internal server error (code: 500)"))
      }
    }
  }

  test("should raise UnknownError domain error for other status codes") {
    val request = ChatCompletionRequest(
      model = "test-model",
      messages = List(ChatMessage(role = "user", content = Json.fromString("test").some))
    )

    val errorDetails  = ErrorDetails(
      code = 503,
      message = "Service unavailable",
      metadata = Some(Map("retry_after" -> Json.fromInt(300)))
    )
    val errorResponse = ErrorResponse(error = errorDetails)

    val stubClient = createStubClient(Response[IO](Status.ServiceUnavailable).withEntity(errorResponse))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(request).attempt.map { result =>
      assert(result.isLeft)
      result.left.map { error =>
        assertEquals(error, Client.Error.UnknownError(503, "Service unavailable", errorDetails))
        assert(error.getMessage.contains("Unknown Error (status 503): Service unavailable (code: 503)"))
      }
    }
  }

  test("should treat success status with error payload as domain error") {
    val request = ChatCompletionRequest(
      model = "test-model",
      messages = List(ChatMessage(role = "user", content = Json.fromString("test").some))
    )

    val errorPayload = Json.obj(
      "error"   -> Json.obj("message" -> Json.fromString("Internal Server Error"), "code" -> Json.fromInt(500)),
      "user_id" -> Json.fromString("test")
    )

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(errorPayload))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(request).attempt.map { result =>
      assert(result.isLeft)
      result.left.map { error =>
        val expectedDetails = ErrorDetails(code = 500, message = "Internal Server Error", metadata = None)
        assertEquals(error, Client.Error.InternalServerError("Internal Server Error", expectedDetails))
      }
    }
  }

  test("should classify error payloads using status and code precedence") {
    val request = ChatCompletionRequest(
      model = "test-model",
      messages = List(ChatMessage(role = "user", content = Json.fromString("test").some))
    )

    final case class ErrCase(status: Status, code: Int, message: String, expected: ErrorDetails => Client.Error)

    val cases = List(
      ErrCase(Status.Ok, 400, "Bad Request", details => Client.Error.BadRequest(details.message, details)),
      ErrCase(Status.Ok, 401, "Unauthorized", details => Client.Error.Unauthorized(details.message, details)),
      ErrCase(Status.Ok, 429, "Too Many Requests", details => Client.Error.TooManyRequests(details.message, details)),
      ErrCase(Status.Ok, 500, "Internal Server Error", details => Client.Error.InternalServerError(details.message, details)),
      ErrCase(Status.Ok, 418, "I'm a teapot", details => Client.Error.UnknownError(Status.Ok.code, details.message, details)),
      ErrCase(Status.ServiceUnavailable, 429, "Retry later", details => Client.Error.TooManyRequests(details.message, details)),
      ErrCase(Status.BadRequest, 503, "Bad request despite code", details => Client.Error.BadRequest(details.message, details)),
      ErrCase(Status.Unauthorized, 500, "Unauthorized takes precedence", details => Client.Error.Unauthorized(details.message, details))
    )

    cases.traverse_ { errCase =>
      val errorPayload = Json.obj(
        "error" -> Json.obj("message" -> Json.fromString(errCase.message), "code" -> Json.fromInt(errCase.code))
      )

      val stubClient = createStubClient(Response[IO](errCase.status).withEntity(errorPayload))
      val client     = createTestClient(stubClient)

      client.createChatCompletion(request).attempt.map { result =>
        assert(result.isLeft)
        result.left.map { error =>
          val expectedDetails = ErrorDetails(code = errCase.code, message = errCase.message, metadata = None)
          assertEquals(error, errCase.expected(expectedDetails))
        }
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

  test("createChatCompletion parses real response with tool calls from example JSON") {
    val jsonStr =
      """
      |{
      |  "id": "gen-1000000001-XyZ9AbCdEfGhIjKlMnOpQrSt",
      |  "provider": "Google",
      |  "model": "google/gemini-3-pro-preview",
      |  "object": "chat.completion",
      |  "created": 1769961862,
      |  "choices": [
      |    {
      |      "logprobs": null,
      |      "finish_reason": "tool_calls",
      |      "native_finish_reason": "STOP",
      |      "index": 0,
      |      "message": {
      |        "role": "assistant",
      |        "content": "",
      |        "refusal": null,
      |        "reasoning": "**Assessing Weather Data Retrieval**\n\nI've located a tool, `ExampleFunctions_getWeather`, that should get the weather. It takes a city parameter, which is easy enough to get. \"london\" is what I have to work with, so I'll feed that into the tool.\n\n\n**Executing the Weather Call**\n\nI'm ready to run the tool. I've confirmed that the `ExampleFunctions_getWeather` tool can accept \"london\" as the city parameter. Now, the execution of `ExampleFunctions_getWeather(city='london')` is my next action.\n\n\n",
      |        "tool_calls": [
      |          {
      |            "type": "function",
      |            "index": 0,
      |            "id": "tool_ExampleFunctions_getWeather_xYz9AbCdEfGhIjKlMnOpQr",
      |            "function": {
      |              "name": "ExampleFunctions_getWeather",
      |              "arguments": "{\"city\":\"london\"}"
      |            }
      |          }
      |        ],
      |        "reasoning_details": [
      |          {
      |            "format": "google-gemini-v1",
      |            "index": 0,
      |            "type": "reasoning.text",
      |            "text": "**Assessing Weather Data Retrieval**\n\nI've located a tool, `ExampleFunctions_getWeather`, that should get the weather. It takes a city parameter, which is easy enough to get. \"london\" is what I have to work with, so I'll feed that into the tool.\n\n\n**Executing the Weather Call**\n\nI'm ready to run the tool. I've confirmed that the `ExampleFunctions_getWeather` tool can accept \"london\" as the city parameter. Now, the execution of `ExampleFunctions_getWeather(city='london')` is my next action.\n\n\n"
      |          },
      |          {
      |            "id": "tool_ExampleFunctions_getWeather_xYz9AbCdEfGhIjKlMnOpQr",
      |            "format": "google-gemini-v1",
      |            "index": 0,
      |            "type": "reasoning.encrypted",
      |            "data": "VGVzdEVuY3J5cHRlZERhdGFCbG9iRm9yVW5pdFRlc3RPbmx5"
      |          }
      |        ],
      |        "annotations": []
      |      }
      |    }
      |  ],
      |  "usage": {
      |    "prompt_tokens": 45,
      |    "completion_tokens": 98,
      |    "total_tokens": 143,
      |    "cost": 0.001266,
      |    "is_byok": false,
      |    "prompt_tokens_details": {
      |      "cached_tokens": 0,
      |      "cache_write_tokens": 0,
      |      "audio_tokens": 0,
      |      "video_tokens": 0
      |    },
      |    "cost_details": {
      |      "upstream_inference_cost": 0.001266,
      |      "upstream_inference_prompt_cost": 0.00009,
      |      "upstream_inference_completions_cost": 0.001176
      |    },
      |    "completion_tokens_details": { "reasoning_tokens": 87, "image_tokens": 0 }
      |  }
      |}
      |""".stripMargin
    decode[ChatCompletionResponse](jsonStr).fold(
      err => IO.raiseError(err),
      response =>
        IO {
          assertEquals(response.choices.size, 1)
          assertEquals(response.choices.head.finishReason, Some("tool_calls"))
          val toolCalls = response.choices.head.message.toolCalls.getOrElse(fail("tool_calls missing"))
          assertEquals(toolCalls.size, 1)
          assertEquals(toolCalls.head.id, "tool_ExampleFunctions_getWeather_xYz9AbCdEfGhIjKlMnOpQr")
          assertEquals(toolCalls.head.function.name, "ExampleFunctions_getWeather")
          assertEquals(toolCalls.head.function.arguments, """{"city":"london"}""")
        }
    )
  }

  test("ChatCompletionRequest encoder should drop null fields") {
    val req = ChatCompletionRequest(
      model = "m",
      messages = List(ChatMessage.user("hi")),
      temperature = None,
      maxTokens = None,
      topP = None,
      stream = false,
      presencePenalty = None,
      frequencyPenalty = None,
      logitBias = None,
      tools = None,
      toolChoice = None,
      modalities = None
    )

    val json = implicitly[Encoder[ChatCompletionRequest]].apply(req)
    assertEquals(json.hcursor.downField("temperature").focus, None)
    assertEquals(json.hcursor.downField("max_tokens").focus, None)
    assertEquals(json.hcursor.downField("top_p").focus, None)
    assertEquals(json.hcursor.downField("presence_penalty").focus, None)
    assertEquals(json.hcursor.downField("frequency_penalty").focus, None)
    assertEquals(json.hcursor.downField("logit_bias").focus, None)
    assertEquals(json.hcursor.downField("tools").focus, None)
    assertEquals(json.hcursor.downField("tool_choice").focus, None)
    assertEquals(json.hcursor.downField("modalities").focus, None)
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
          message = ChatMessage.assistant(
            "Based on the search results, I found 'Ulysses' by James Joyce in the Project Gutenberg library."
          ),
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

  test("listModels should decode real OpenRouter API response with all fields") {
    val jsonString = """{
      "id" : "openai/o3-deep-research",
      "canonical_slug" : "openai/o3-deep-research-2025-06-26",
      "hugging_face_id" : "",
      "name" : "OpenAI: o3 Deep Research",
      "created" : 1760129661,
      "description" : "o3-deep-research is OpenAI's advanced model for deep research, designed to tackle complex, multi-step research tasks.\n\nNote: This model always uses the 'web_search' tool which adds additional cost.",
      "context_length" : 200000,
      "architecture" : {
        "modality" : "text+image->text",
        "input_modalities" : [
          "image",
          "text",
          "file"
        ],
        "output_modalities" : [
          "text"
        ],
        "tokenizer" : "GPT",
        "instruct_type" : null
      },
      "pricing" : {
        "prompt" : "0.00001",
        "completion" : "0.00004",
        "request" : "0",
        "image" : "0.00765",
        "web_search" : "0.01",
        "internal_reasoning" : "0",
        "input_cache_read" : "0.0000025"
      },
      "top_provider" : {
        "context_length" : 200000,
        "max_completion_tokens" : 100000,
        "is_moderated" : true
      },
      "per_request_limits" : null,
      "supported_parameters" : [
        "frequency_penalty",
        "include_reasoning",
        "logit_bias",
        "logprobs",
        "max_tokens",
        "presence_penalty",
        "reasoning",
        "response_format",
        "seed",
        "stop",
        "structured_outputs",
        "temperature",
        "tool_choice",
        "tools",
        "top_logprobs",
        "top_p"
      ],
      "temperature" : null,
      "top_p" : null,
      "frequency_penalty" : null
    }"""

    val modelsResponseJson = s"""{"data": [$jsonString]}"""

    val decoded = parse(modelsResponseJson).flatMap(_.as[ModelsResponse])

    decoded match
      case Right(response) =>
        assertEquals(response.data.size, 1)
        val model = response.data.head
        assertEquals(model.id, "openai/o3-deep-research")
        assertEquals(model.name, "OpenAI: o3 Deep Research")
        assertEquals(model.canonicalSlug, Some("openai/o3-deep-research-2025-06-26"))
        assertEquals(model.huggingFaceId, Some(""))
        assertEquals(model.created, Some(1760129661L))
        assertEquals(model.contextLength, Some(200000))
        assertEquals(model.temperature, None)
        assertEquals(model.topP, None)
        assertEquals(model.frequencyPenalty, None)

        model.architecture match
          case Some(arch) =>
            assertEquals(arch.modality, Some("text+image->text"))
            assertEquals(arch.inputModalities, Set(Modality.Image, Modality.Text, Modality.File))
            assertEquals(arch.outputModalities, Set(Modality.Text))
            assertEquals(arch.tokenizer, Some(Tokenizer.GPT))
            assertEquals(arch.instructType, None)
          case None       => fail("architecture should be present")

        model.pricing match
          case Some(pricing) =>
            assertEquals(pricing.prompt, "0.00001")
            assertEquals(pricing.completion, "0.00004")
            assertEquals(pricing.request, Some("0"))
            assertEquals(pricing.image, Some("0.00765"))
            assertEquals(pricing.webSearch, Some("0.01"))
            assertEquals(pricing.internalReasoning, Some("0"))
            assertEquals(pricing.inputCacheRead, Some("0.0000025"))
          case None          => fail("pricing should be present")

        model.topProvider match
          case Some(provider) =>
            assertEquals(provider.contextLength, Some(200000))
            assertEquals(provider.maxCompletionTokens, Some(100000))
            assertEquals(provider.isModerated, true)
          case None           => fail("topProvider should be present")

        assertEquals(model.perRequestLimits, None)
        assertEquals(
          model.supportedParameters,
          Some(
            List(
              SupportedParameter.FrequencyPenalty,
              SupportedParameter.IncludeReasoning,
              SupportedParameter.LogitBias,
              SupportedParameter.Logprobs,
              SupportedParameter.MaxTokens,
              SupportedParameter.PresencePenalty,
              SupportedParameter.Reasoning,
              SupportedParameter.ResponseFormat,
              SupportedParameter.Seed,
              SupportedParameter.Stop,
              SupportedParameter.StructuredOutputs,
              SupportedParameter.Temperature,
              SupportedParameter.ToolChoice,
              SupportedParameter.Tools,
              SupportedParameter.TopLogprobs,
              SupportedParameter.TopP
            )
          )
        )
      case Left(error)     => fail(s"Failed to decode JSON: ${error.getMessage}")
  }

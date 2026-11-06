package io.github.serhiip.constellations.openrouter

import cats.effect.{IO, Resource}
import cats.syntax.all.*

import io.circe.{Encoder, Json}
import io.github.serhiip.constellations.openrouter.schema.additional.tools.item
import io.github.serhiip.constellations.openrouter.schema.chat.choice.ChatChoice
import io.github.serhiip.constellations.openrouter.schema.chat.content.image.ImageUrl
import io.github.serhiip.constellations.openrouter.schema.chat.content.items.{ChatContentImage, ChatContentItems, ChatContentText}
import io.github.serhiip.constellations.openrouter.schema.chat.finish.reason.enum_.ChatFinishReasonEnum
import io.github.serhiip.constellations.openrouter.schema.chat.function.tool.FunctionFunction
import io.github.serhiip.constellations.openrouter.schema.chat.messages.{ChatAssistantMessage, ChatToolMessage, ChatUserMessage}
import io.github.serhiip.constellations.openrouter.schema.chat.request.ChatRequest
import io.github.serhiip.constellations.openrouter.schema.chat.result.ChatResult
import io.github.serhiip.constellations.openrouter.schema.chat.tool.call
import io.github.serhiip.constellations.openrouter.schema.chat.tool.call.ChatToolCall
import io.github.serhiip.constellations.openrouter.schema.generation.response.GenerationResponse
import io.github.serhiip.constellations.openrouter.schema.input.modality.InputModality
import io.github.serhiip.constellations.openrouter.schema.model.group.ModelGroup
import io.github.serhiip.constellations.openrouter.schema.models.list.response.ModelsListResponse
import io.github.serhiip.constellations.openrouter.schema.output.modality.OutputModality
import io.github.serhiip.constellations.openrouter.schema.parameter.Parameter
import io.github.serhiip.constellations.openrouter.schema.public.pricing.PublicPricing
import io.circe.parser.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client as HTTPClient

final class ClientTest extends CatsEffectSuite:

  private val testApiKey = "test-api-key"
  private val testConfig = Client.Config()

  private def createStubClient[F[_]: cats.effect.kernel.MonadCancelThrow](response: Response[F]): HTTPClient[F] =
    HTTPClient[F] { _ => Resource.pure(response) }

  private def createTestClient(stubClient: HTTPClient[IO]): Client[IO] =
    Client.core[IO](testApiKey, testConfig)(stubClient)

  test("createEmbeddings should send correct request and parse response") {
    val expectedRequest = EmbeddingsRequest(
      model = "openai/text-embedding-3-small",
      input = Json.fromString("hello world")
    )

    val expectedResponse = EmbeddingsResponse(
      `object` = "list",
      data = List(
        EmbeddingData(`object` = "embedding", embedding = List(0.1f, 0.2f, 0.3f), index = 0)
      ),
      model = "openai/text-embedding-3-small",
      usage = EmbeddingsUsage(promptTokens = 2, totalTokens = 2)
    )

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(expectedResponse))
    val client     = createTestClient(stubClient)

    client.createEmbeddings(expectedRequest).map { response =>
      assertEquals(response, expectedResponse)
    }
  }

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
    val expectedResponse = TestResponses.modelsList()

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(expectedResponse))
    val client     = createTestClient(stubClient)

    client.listModels().map { response =>
      assertEquals(response, expectedResponse)
    }
  }

  test("getGenerationStats should send correct request and parse response") {
    val generationId     = "gen-1234567890"
    val expectedResponse = TestResponses.generationData(generationId)

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(GenerationResponse(data = expectedResponse)))
    val client     = createTestClient(stubClient)

    client.getGenerationStats(generationId).map { response =>
      assertEquals(response, expectedResponse)
    }
  }

  test("createChatCompletion should handle multimodal content") {
    val parts: List[ChatContentItems] = List(
      ChatContentText(text = "What's in this image?"),
      ChatContentImage(image_url = ImageUrl(url = "data:image/jpeg;base64,test"))
    )
    val multimodalRequest             = ChatRequest(model = Some("gpt-4o"), messages = List(ChatUserMessage(content = parts)))
    val expectedResponse              = TestResponses.chatResult("gpt-4o", "I can see an image in the message.")

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(expectedResponse))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(multimodalRequest).map { response =>
      assertEquals(response, expectedResponse)
    }
  }

  test("should handle error responses gracefully") {
    val request = ChatRequest(
      model = Some("test-model"),
      messages = List(ChatUserMessage(content = "test"))
    )

    val stubClient = createStubClient(Response[IO](Status.BadRequest).withEntity("Invalid request"))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(request).attempt.map { result =>
      assert(result.isLeft)
    }
  }

  test("should handle OpenRouter API error responses with proper format") {
    val request = ChatRequest(
      model = Some("test-model"),
      messages = List(ChatUserMessage(content = "test"))
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
    val request = ChatRequest(
      model = Some("test-model"),
      messages = List(ChatUserMessage(content = "test"))
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
    val request = ChatRequest(
      model = Some("test-model"),
      messages = List(ChatUserMessage(content = "test"))
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
    val request = ChatRequest(
      model = Some("test-model"),
      messages = List(ChatUserMessage(content = "test"))
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
    val request = ChatRequest(
      model = Some("test-model"),
      messages = List(ChatUserMessage(content = "test"))
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
    val request = ChatRequest(
      model = Some("test-model"),
      messages = List(ChatUserMessage(content = "test"))
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
    val request = ChatRequest(
      model = Some("test-model"),
      messages = List(ChatUserMessage(content = "test"))
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
    val request = ChatRequest(
      model = Some("test-model"),
      messages = List(ChatUserMessage(content = "test"))
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
    val request = ChatRequest(
      model = Some("test-model"),
      messages = List(ChatUserMessage(content = "test"))
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
    val searchTool       = createSearchTool()
    val request          = ChatRequest(
      model = Some("google/gemini-2.0-flash-001"),
      messages = List(ChatUserMessage(content = "What are the titles of some James Joyce books?")),
      tools = List(searchTool).some,
      tool_choice = Some("auto")
    )
    val expectedResponse = TestResponses
      .chatResult("google/gemini-2.0-flash-001")
      .copy(choices =
        List(
          ChatChoice(
            finish_reason = Some(ChatFinishReasonEnum.ToolCalls),
            index = 0L,
            message = ChatAssistantMessage(
              tool_calls = Some(
                List(
                  ChatToolCall(
                    id = "call_abc123",
                    function = call.Function(name = "search_gutenberg_books", arguments = """{"search_terms": ["James", "Joyce"]}""")
                  )
                )
              )
            )
          )
        )
      )

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(expectedResponse))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(request).map { response =>
      assertEquals(response, expectedResponse)
      assertEquals(response.choices.head.message.tool_calls.get.head.function.name, "search_gutenberg_books")
      assertEquals(response.choices.head.finish_reason, Some(ChatFinishReasonEnum.ToolCalls))
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
    decode[ChatResult](jsonStr).fold(
      err => IO.raiseError(err),
      response =>
        IO {
          assertEquals(response.choices.size, 1)
          assertEquals(response.choices.head.finish_reason, Some(ChatFinishReasonEnum.ToolCalls))
          val toolCalls = response.choices.head.message.tool_calls.getOrElse(fail("tool_calls missing"))
          assertEquals(toolCalls.size, 1)
          assertEquals(toolCalls.head.id, "tool_ExampleFunctions_getWeather_xYz9AbCdEfGhIjKlMnOpQr")
          assertEquals(toolCalls.head.function.name, "ExampleFunctions_getWeather")
          assertEquals(toolCalls.head.function.arguments, """{"city":"london"}""")
          val reasoning = response.choices.head.message.reasoning.getOrElse(fail("reasoning missing"))
          assert(reasoning.startsWith("**Assessing Weather Data Retrieval**"))
          assert(!reasoning.contains("VGVzdEVuY3J5cHRlZERhdGFCbG9i"))
        }
    )
  }

  test("ChatRequest encoder should drop null fields") {
    val req = ChatRequest(model = Some("m"), messages = List(ChatUserMessage(content = "hi")))

    val json = Encoder[ChatRequest].apply(req)
    assertEquals(json.hcursor.downField("temperature").focus, None)
    assertEquals(json.hcursor.downField("max_tokens").focus, None)
    assertEquals(json.hcursor.downField("top_p").focus, None)
    assertEquals(json.hcursor.downField("presence_penalty").focus, None)
    assertEquals(json.hcursor.downField("frequency_penalty").focus, None)
    assertEquals(json.hcursor.downField("logit_bias").focus, None)
    assertEquals(json.hcursor.downField("tools").focus, None)
    assertEquals(json.hcursor.downField("tool_choice").focus, None)
    assertEquals(json.hcursor.downField("modalities").focus, None)
    assertEquals(json.hcursor.downField("trace").focus, None)
    assertEquals(json.hcursor.downField("messages").downArray.downField("reasoning").focus, None)
  }

  test("createChatCompletion should handle tool results") {
    val toolResultMessage = ChatToolMessage(
      tool_call_id = "call_abc123",
      content = """[{"id": 4300, "title": "Ulysses", "authors": [{"name": "Joyce, James"}]}]"""
    )
    val request           = ChatRequest(
      model = Some("google/gemini-2.0-flash-001"),
      messages = List(
        ChatUserMessage(content = "What are the titles of some James Joyce books?"),
        ChatAssistantMessage(
          tool_calls = Some(
            List(
              ChatToolCall(
                id = "call_abc123",
                function = call.Function(name = "search_gutenberg_books", arguments = """{"search_terms": ["James", "Joyce"]}""")
              )
            )
          )
        ),
        toolResultMessage
      ),
      tools = List(createSearchTool()).some
    )
    val expectedResponse  = TestResponses.chatResult(
      "google/gemini-2.0-flash-001",
      "Based on the search results, I found 'Ulysses' by James Joyce in the Project Gutenberg library."
    )

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(expectedResponse))
    val client     = createTestClient(stubClient)

    client.createChatCompletion(request).map { response =>
      assertEquals(response, expectedResponse)
      assertEquals(response.choices.head.finish_reason, Some(ChatFinishReasonEnum.Stop))
    }
  }

  private def createSearchTool(): item.Function =
    item.Function(
      function = FunctionFunction(
        name = "search_gutenberg_books",
        description = Some("Search for books in the Project Gutenberg library"),
        parameters = Some(
          Map(
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
      )
    )

  test("should include attribution headers when configured") {
    val configWithAttribution = testConfig.copy(
      appUrl = "https://myapp.com".some,
      appTitle = "My AI Assistant".some
    )

    val expectedResponse = TestResponses.chatResult("gpt-4o", "Hello!")

    val stubClient = createStubClient(Response[IO](Status.Ok).withEntity(expectedResponse))
    val client     = Client.core[IO](testApiKey, configWithAttribution)(stubClient)

    val request = ChatRequest(
      model = Some("gpt-4o"),
      messages = List(ChatUserMessage(content = "Hello"))
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
      "frequency_penalty" : null,
      "default_parameters" : null,
      "supported_voices" : null,
      "links" : { "details" : "https://openrouter.ai/openai/o3-deep-research" },
      "endpoints" : []
    }"""

    val modelsResponseJson = s"""{"data": [$jsonString], "links": {"next": null}, "total_count": 1}"""

    val decoded = parse(modelsResponseJson).flatMap(_.as[ModelsListResponse])

    decoded match
      case Right(response) =>
        assertEquals(response.data.size, 1)
        val model = response.data.head
        assertEquals(model.id, "openai/o3-deep-research")
        assertEquals(model.name, "OpenAI: o3 Deep Research")
        assertEquals(model.canonical_slug, "openai/o3-deep-research-2025-06-26")
        assertEquals(model.hugging_face_id, Some(""))
        assertEquals(model.created, 1760129661L)
        assertEquals(model.context_length, Some(200000L))
        assertEquals(model.default_parameters, None)

        val arch = model.architecture
        assertEquals(arch.modality, Some("text+image->text"))
        assertEquals(arch.input_modalities, List(InputModality.Image, InputModality.Text, InputModality.File))
        assertEquals(arch.output_modalities, List(OutputModality.Text))
        assertEquals(arch.tokenizer, Some(ModelGroup.GPT))
        assertEquals(arch.instruct_type, None)

        val pricing = model.pricing
        assertEquals(pricing.prompt, "0.00001")
        assertEquals(pricing.completion, "0.00004")
        assertEquals(pricing.request, Some("0"))
        assertEquals(pricing.image, Some("0.00765"))
        assertEquals(pricing.web_search, Some("0.01"))
        assertEquals(pricing.internal_reasoning, Some("0"))
        assertEquals(pricing.input_cache_read, Some("0.0000025"))

        val provider = model.top_provider
        assertEquals(provider.context_length, Some(200000L))
        assertEquals(provider.max_completion_tokens, Some(100000L))
        assertEquals(provider.is_moderated, true)

        assertEquals(model.per_request_limits, None)
        assertEquals(
          model.supported_parameters,
          List(
            Parameter.FrequencyPenalty,
            Parameter.IncludeReasoning,
            Parameter.LogitBias,
            Parameter.Logprobs,
            Parameter.MaxTokens,
            Parameter.PresencePenalty,
            Parameter.Reasoning,
            Parameter.ResponseFormat,
            Parameter.Seed,
            Parameter.Stop,
            Parameter.StructuredOutputs,
            Parameter.Temperature,
            Parameter.ToolChoice,
            Parameter.Tools,
            Parameter.TopLogprobs,
            Parameter.TopP
          )
        )
      case Left(error)     => fail(s"Failed to decode JSON: ${error.getMessage}")
  }

  test("PublicPricing decodes price fields as strings") {
    val json =
      """{"prompt":"0.00001","completion":"0.00004","request":"0","image":"0.00765","web_search":"0.01","internal_reasoning":"0","input_cache_read":"0.0000025"}"""
    parse(json).flatMap(_.as[PublicPricing]) match
      case Right(pricing) =>
        assertEquals(pricing.prompt, "0.00001")
        assertEquals(pricing.completion, "0.00004")
        assertEquals(pricing.request, Some("0"))
        assertEquals(pricing.image, Some("0.00765"))
        assertEquals(pricing.web_search, Some("0.01"))
        assertEquals(pricing.internal_reasoning, Some("0"))
        assertEquals(pricing.input_cache_read, Some("0.0000025"))
        assertEquals(pricing.input_cache_write, None)
      case Left(e)        => fail(s"Failed to decode PublicPricing from strings: $e")
  }

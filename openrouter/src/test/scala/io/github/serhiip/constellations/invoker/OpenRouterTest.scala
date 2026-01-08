package io.github.serhiip.constellations.invoker

import cats.data.NonEmptyChain as NEC
import cats.effect.{IO, Ref}
import cats.syntax.all.*

import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.openrouter.*
import io.circe.Json
import munit.CatsEffectSuite

class OpenRouterTest extends CatsEffectSuite:

  private class StubClient(chatRequestRef: Ref[IO, Option[ChatCompletionRequest]], completionRequestRef: Ref[IO, Option[CompletionRequest]])
      extends Client[IO]:

    def createChatCompletion(request: ChatCompletionRequest): IO[ChatCompletionResponse] =
      chatRequestRef.set(request.some) *>
        IO.pure(
          ChatCompletionResponse(
            id = "test-id",
            `object` = "chat.completion",
            created = 1234567890L,
            model = request.model,
            choices = List(
              ChatCompletionChoice(
                index = 0,
                message = ChatMessage(role = "assistant", content = Some(Json.fromString("Mock response"))),
                finishReason = "stop".some
              )
            ),
            usage = ChatCompletionUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
          )
        )

    def createCompletion(request: CompletionRequest): IO[CompletionResponse] =
      completionRequestRef.set(request.some) *>
        IO.pure(
          CompletionResponse(
            id = "test-id",
            `object` = "text_completion",
            created = 1234567890L,
            model = request.model,
            choices = List(
              CompletionChoice(
                text = "Mock completion response",
                index = 0.some,
                finishReason = "stop".some
              )
            ),
            usage = ChatCompletionUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
          )
        )

    def listModels(): IO[ModelsResponse] =
      IO.pure(
        ModelsResponse(
          data = List(
            Model(
              id = "gpt-4o",
              name = "GPT-4o",
              description = "Test model".some,
              pricing = ModelPricing(prompt = "0.005", completion = "0.015").some,
              contextLength = 128000.some,
              architecture = ModelArchitecture(
                inputModalities = List("text"),
                outputModalities = List("text"),
                tokenizer = "cl100k_base".some
              ).some,
              topProvider = ModelTopProvider(
                isModerated = false,
                contextLength = Some(128000),
                maxCompletionTokens = Some(128000)
              ).some,
              perRequestLimits = none
            )
          )
        )
      )

    def getGenerationStats(generationId: String): IO[GenerationStats] =
      IO.pure(
        GenerationStats(
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
      )

  test("chatCompletion should convert text-only messages correctly") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatCompletionRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      stubClient            = new StubClient(chatRequestRef, completionRequestRef)
      invoker               = OpenRouter.chatCompletion[IO](
                                client = stubClient,
                                config = OpenRouter.Config(model = "gpt-4o", temperature = 0.7.some, maxTokens = 100.some)
                              )
      history               = NEC.one(Message.User(List(ContentPart.Text("Hello, how are you?"))))
      response             <- invoker.generate(history)
      capturedRequest      <- chatRequestRef.get
    yield
      assert(capturedRequest.isDefined)
      val request = capturedRequest.get
      assertEquals(request.model, "gpt-4o")
      assertEquals(request.temperature, 0.7.some)
      assertEquals(request.maxTokens, 100.some)
      assertEquals(request.messages.length, 1)
      val message = request.messages.head
      assertEquals(message.role, "user")
      assertEquals(message.content.flatMap(_.asString), Some("Hello, how are you?"))
      assertEquals(message.images, None)
      assertEquals(response.model, "gpt-4o")
  }

  test("chatCompletion should convert multimodal messages correctly") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatCompletionRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      stubClient            = new StubClient(chatRequestRef, completionRequestRef)
      invoker               = OpenRouter.chatCompletion[IO](
                                client = stubClient,
                                config = OpenRouter.Config(model = "gpt-4o")
                              )
      history               = NEC.one(
                                Message.User(
                                  List(
                                    ContentPart.Text("What's in this image?"),
                                    ContentPart.Image("base64encodedimage")
                                  )
                                )
                              )
      _                    <- invoker.generate(history)
      capturedRequest      <- chatRequestRef.get
    yield
      assert(capturedRequest.isDefined)
      val request = capturedRequest.get
      assertEquals(request.messages.length, 1)
      val message = request.messages.head
      assertEquals(message.role, "user")
      assertEquals(message.content.flatMap(_.asString), Some("What's in this image?"))
      val imgs    = message.images.get
      assertEquals(imgs.length, 1)
      assertEquals(imgs.head.imageUrl.url, "data:image/png;base64,base64encodedimage")
  }

  test("chatCompletion should handle multiple message types") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatCompletionRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      stubClient            = new StubClient(chatRequestRef, completionRequestRef)
      invoker               = OpenRouter.chatCompletion[IO](
                                client = stubClient,
                                config = OpenRouter.Config(model = "gpt-4o")
                              )
      history               = NEC
                                .fromSeq(
                                  List(
                                    Message.System("You are a helpful assistant"),
                                    Message.User(List(ContentPart.Text("Hello"))),
                                    Message.Assistant("Hi there! How can I help you?".some, List.empty),
                                    Message.User(List(ContentPart.Text("What's 2+2?")))
                                  )
                                )
                                .get
      _                    <- invoker.generate(history)
      capturedRequest      <- chatRequestRef.get
    yield
      assert(capturedRequest.isDefined)
      val request = capturedRequest.get
      assertEquals(request.messages.length, 4)
      assertEquals(request.messages(0).role, "system")
      assertEquals(request.messages(0).content, Some(Json.fromString("You are a helpful assistant")))
      assertEquals(request.messages(1).role, "user")
      assertEquals(request.messages(1).content.flatMap(_.asString), Some("Hello"))
      assertEquals(request.messages(2).role, "assistant")
      assertEquals(request.messages(2).content, Some(Json.fromString("Hi there! How can I help you?")))
      assertEquals(request.messages(3).role, "user")
      assertEquals(request.messages(3).content.flatMap(_.asString), Some("What's 2+2?"))
  }

  test("chatCompletion should handle tool messages") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatCompletionRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      stubClient            = new StubClient(chatRequestRef, completionRequestRef)
      invoker               = OpenRouter.chatCompletion[IO](
                                client = stubClient,
                                config = OpenRouter.Config(model = "gpt-4o")
                              )
      // Create a calculator function call with actual arguments
      calculatorArgs        = Struct(Map("a" -> Value.NumberValue(2.0), "b" -> Value.NumberValue(3.0)))
      calculatorResult      = Struct(Map("result" -> Value.NumberValue(5.0), "operation" -> Value.StringValue("addition")))

      history          = NEC
                           .fromSeq(
                             List(
                               Message.User(List(ContentPart.Text("Calculate 2+3"))),
                               Message.Tool(FunctionCall("calculator", calculatorArgs, "call-123".some)),
                               Message.ToolResult(FunctionResponse("calculator", calculatorResult, "call-123".some))
                             )
                           )
                           .get
      _               <- invoker.generate(history)
      capturedRequest <- chatRequestRef.get
    yield
      assert(capturedRequest.isDefined)
      val request = capturedRequest.get
      assertEquals(request.messages.length, 3)

      // Check the tool call message (assistant)
      assertEquals(request.messages(1).role, "assistant")
      assert(request.messages(1).toolCalls.isDefined)
      val toolCalls = request.messages(1).toolCalls.get
      assertEquals(toolCalls.length, 1)
      val toolCall  = toolCalls.head
      assertEquals(toolCall.`type`, "function")
      assertEquals(toolCall.function.name, "calculator")
      // The arguments should be the JSON string representation of calculatorArgs
      assertEquals(toolCall.function.arguments, """{"a":2.0,"b":3.0}""")

      // Check the tool result message
      assertEquals(request.messages(2).role, "tool")
      assertEquals(request.messages(2).name, Some("calculator"))
      assert(request.messages(2).toolCallId.isDefined)
      // The content should be the JSON representation of calculatorResult
      assertEquals(
        request.messages(2).content,
        Some(Json.obj("result" -> Json.fromDoubleOrNull(5.0), "operation" -> Json.fromString("addition")))
      )
  }

  test("completion should concatenate messages into single prompt") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatCompletionRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      stubClient            = new StubClient(chatRequestRef, completionRequestRef)
      invoker               = OpenRouter.completion[IO](
                                client = stubClient,
                                config = OpenRouter.Config(model = "gpt-3.5-turbo", temperature = 0.8.some, maxTokens = 50.some)
                              )
      history               = NEC
                                .fromSeq(
                                  List(
                                    Message.System("You are a helpful assistant"),
                                    Message.User(List(ContentPart.Text("Hello"))),
                                    Message.Assistant("Hi there!".some, List.empty),
                                    Message.User(List(ContentPart.Text("What's 2+2?")))
                                  )
                                )
                                .get
      _                    <- invoker.generate(history)
      capturedRequest      <- completionRequestRef.get
    yield
      assert(capturedRequest.isDefined)
      val request        = capturedRequest.get
      assertEquals(request.model, "gpt-3.5-turbo")
      assertEquals(request.temperature, 0.8.some)
      assertEquals(request.maxTokens, 50.some)
      val expectedPrompt = "You are a helpful assistant\nHello\nHi there!\nWhat's 2+2?"
      assertEquals(request.prompt, expectedPrompt)
  }

  test("completion should handle multimodal content by extracting text only") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatCompletionRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      stubClient            = new StubClient(chatRequestRef, completionRequestRef)
      invoker               = OpenRouter.completion[IO](
                                client = stubClient,
                                config = OpenRouter.Config(model = "gpt-3.5-turbo")
                              )
      history               = NEC.one(
                                Message.User(
                                  List(
                                    ContentPart.Text("What's in this image?"),
                                    ContentPart.Image("base64encodedimage")
                                  )
                                )
                              )
      _                    <- invoker.generate(history)
      capturedRequest      <- completionRequestRef.get
    yield
      assert(capturedRequest.isDefined)
      val request = capturedRequest.get
      assertEquals(request.prompt, "What's in this image?")
  }

  test("completion should handle multiple text parts in user message") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatCompletionRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      stubClient            = new StubClient(chatRequestRef, completionRequestRef)
      invoker               = OpenRouter.completion[IO](
                                client = stubClient,
                                config = OpenRouter.Config(model = "gpt-3.5-turbo")
                              )
      history               = NEC.one(
                                Message.User(
                                  List(
                                    ContentPart.Text("First part"),
                                    ContentPart.Text("Second part"),
                                    ContentPart.Text("Third part")
                                  )
                                )
                              )
      _                    <- invoker.generate(history)
      capturedRequest      <- completionRequestRef.get
    yield
      assert(capturedRequest.isDefined)
      val request = capturedRequest.get
      assertEquals(request.prompt, "First part Second part Third part")
  }

  test("chatCompletion should handle empty content list") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatCompletionRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      stubClient            = new StubClient(chatRequestRef, completionRequestRef)
      invoker               = OpenRouter.chatCompletion[IO](
                                client = stubClient,
                                config = OpenRouter.Config(model = "gpt-4o")
                              )
      history               = NEC.one(Message.User(List.empty))
      _                    <- invoker.generate(history)
      capturedRequest      <- chatRequestRef.get
    yield
      assert(capturedRequest.isDefined)
      val request = capturedRequest.get
      assertEquals(request.messages.length, 1)
      val message = request.messages.head
      assertEquals(message.role, "user")
      assertEquals(message.content, None)
      assertEquals(message.images, None)
  }

  test("chatCompletion should include function declarations as tools in request") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatCompletionRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      stubClient            = new StubClient(chatRequestRef, completionRequestRef)

      // Define function declarations
      searchBooksFunction = FunctionDeclaration(
                              name = "search_books",
                              description = "Search for books by title or author",
                              parameters = Schema.obj(
                                properties = Map(
                                  "query" -> Schema.string(description = Some("Search query for books")),
                                  "limit" -> Schema.integer(description = Some("Maximum number of results"))
                                ),
                                required = List("query")
                              )
                            )

      calculatorFunction = FunctionDeclaration(
                             name = "calculate",
                             description = "Perform mathematical calculations",
                             parameters = Schema.obj(
                               properties = Map(
                                 "operation" -> Schema.string(description = Some("Mathematical operation")),
                                 "a"         -> Schema.number(description = Some("First number")),
                                 "b"         -> Schema.number(description = Some("Second number"))
                               ),
                               required = List("operation", "a", "b")
                             )
                           )

      invoker = OpenRouter.chatCompletion[IO](
                  client = stubClient,
                  config = OpenRouter.Config(model = "gpt-4o"),
                  functionDeclarations = List(searchBooksFunction, calculatorFunction)
                )

      history          = NEC.one(Message.User(List(ContentPart.Text("Hello"))))
      _               <- invoker.generate(history)
      capturedRequest <- chatRequestRef.get
    yield
      assert(capturedRequest.isDefined)
      val request = capturedRequest.get

      // Verify tools are included in the request
      assert(request.tools.isDefined)
      val tools = request.tools.get
      assertEquals(tools.length, 2)

      // Verify tool choice is set to auto
      assert(request.toolChoice.isDefined)
      assertEquals(request.toolChoice.get.asString.get, "auto")

      // Verify first tool (search_books)
      val searchTool = tools.head
      assertEquals(searchTool.`type`, "function")
      assertEquals(searchTool.function.name, "search_books")
      assertEquals(searchTool.function.description, Some("Search for books by title or author"))

      // Verify search_books parameters
      val searchParams     = searchTool.function.parameters.asObject.get
      assertEquals(searchParams("type").get.asString.get, "object")
      val searchProperties = searchParams("properties").get.asObject.get
      assertEquals(searchProperties("query").get.asObject.get("type").get.asString.get, "string")
      assertEquals(searchProperties("query").get.asObject.get("description").get.asString.get, "Search query for books")
      assertEquals(searchProperties("limit").get.asObject.get("type").get.asString.get, "integer")
      assertEquals(searchProperties("limit").get.asObject.get("description").get.asString.get, "Maximum number of results")
      val searchRequired   = searchParams("required").get.asArray.get
      assertEquals(searchRequired.length, 1)
      assertEquals(searchRequired(0).asString.get, "query")

      // Verify second tool (calculate)
      val calcTool = tools(1)
      assertEquals(calcTool.`type`, "function")
      assertEquals(calcTool.function.name, "calculate")
      assertEquals(calcTool.function.description, Some("Perform mathematical calculations"))

      // Verify calculate parameters
      val calcParams     = calcTool.function.parameters.asObject.get
      assertEquals(calcParams("type").get.asString.get, "object")
      val calcProperties = calcParams("properties").get.asObject.get
      assertEquals(calcProperties("operation").get.asObject.get("type").get.asString.get, "string")
      assertEquals(calcProperties("operation").get.asObject.get("description").get.asString.get, "Mathematical operation")
      assertEquals(calcProperties("a").get.asObject.get("type").get.asString.get, "number")
      assertEquals(calcProperties("a").get.asObject.get("description").get.asString.get, "First number")
      assertEquals(calcProperties("b").get.asObject.get("type").get.asString.get, "number")
      assertEquals(calcProperties("b").get.asObject.get("description").get.asString.get, "Second number")
      val calcRequired   = calcParams("required").get.asArray.get
      assertEquals(calcRequired.length, 3)
      assertEquals(calcRequired(0).asString.get, "operation")
      assertEquals(calcRequired(1).asString.get, "a")
      assertEquals(calcRequired(2).asString.get, "b")
  }

  test("chatCompletion should not include tools when no function declarations are provided") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatCompletionRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      stubClient            = new StubClient(chatRequestRef, completionRequestRef)
      invoker               = OpenRouter.chatCompletion[IO](
                                client = stubClient,
                                config = OpenRouter.Config(model = "gpt-4o")
                              )
      history               = NEC.one(Message.User(List(ContentPart.Text("Hello"))))
      _                    <- invoker.generate(history)
      capturedRequest      <- chatRequestRef.get
    yield
      assert(capturedRequest.isDefined)
      val request = capturedRequest.get

      // Verify tools are not included when no function declarations are provided
      assertEquals(request.tools, None)
      assertEquals(request.toolChoice, None)
  }

  // Tests for Gemini message handling
  test("GeminiMessageHandler should convert single text content to string") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatCompletionRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      stubClient            = new StubClient(chatRequestRef, completionRequestRef)
      invoker               = OpenRouter.chatCompletion[IO](
                                client = stubClient,
                                config = OpenRouter.Config(model = "google/gemini-2.0-flash-001")
                              )
      history               = NEC.one(Message.User(List(ContentPart.Text("Hello, Gemini!"))))
      _                    <- invoker.generate(history)
      capturedRequest      <- chatRequestRef.get
    yield
      assert(capturedRequest.isDefined)
      val request = capturedRequest.get
      assertEquals(request.model, "google/gemini-2.0-flash-001")
      assertEquals(request.messages.length, 1)
      val message = request.messages.head
      assertEquals(message.role, "user")
      // For Gemini, single text content should be a simple string
      assertEquals(message.content, Some(Json.fromString("Hello, Gemini!")))
  }

  test("GeminiMessageHandler should convert multimodal content to array") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatCompletionRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      stubClient            = new StubClient(chatRequestRef, completionRequestRef)
      invoker               = OpenRouter.chatCompletion[IO](
                                client = stubClient,
                                config = OpenRouter.Config(model = "google/gemini-2.0-flash-001")
                              )
      history               = NEC.one(
                                Message.User(
                                  List(
                                    ContentPart.Text("What's in this image?"),
                                    ContentPart.Image("base64encodedimage")
                                  )
                                )
                              )
      _                    <- invoker.generate(history)
      capturedRequest      <- chatRequestRef.get
    yield
      assert(capturedRequest.isDefined)
      val request   = capturedRequest.get
      assertEquals(request.messages.length, 1)
      val message   = request.messages.head
      assertEquals(message.role, "user")
      assertEquals(message.content.flatMap(_.asString), Some("What's in this image?"))
      val images    = message.images.get
      assertEquals(images.length, 1)
      val imagePart = images.head
      assertEquals(imagePart.imageUrl.url, "data:image/png;base64,base64encodedimage")
  }

  test("GeminiMessageHandler should format tool results without name field") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatCompletionRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      stubClient            = new StubClient(chatRequestRef, completionRequestRef)
      invoker               = OpenRouter.chatCompletion[IO](
                                client = stubClient,
                                config = OpenRouter.Config(model = "google/gemini-2.0-flash-001")
                              )
      history               = NEC
                                .fromSeq(
                                  List(
                                    Message.User(List(ContentPart.Text("Calculate 2+3"))),
                                    Message.Tool(
                                      FunctionCall(
                                        name = "calculator",
                                        args = Struct(Map("a" -> Value.number(2.0), "b" -> Value.number(3.0))),
                                        callId = "call-123".some
                                      )
                                    ),
                                    Message.ToolResult(
                                      FunctionResponse(
                                        name = "calculator",
                                        response = Struct(Map("result" -> Value.number(5.0))),
                                        functionCallId = "call-123".some
                                      )
                                    )
                                  )
                                )
                                .get
      _                    <- invoker.generate(history)
      capturedRequest      <- chatRequestRef.get
    yield
      assert(capturedRequest.isDefined)
      val request = capturedRequest.get
      assertEquals(request.messages.length, 3)

      // Check tool result message (index 2)
      val toolResultMessage = request.messages(2)
      assertEquals(toolResultMessage.role, "tool")
      assertEquals(toolResultMessage.name, Some("calculator"))
      assertEquals(toolResultMessage.toolCallId, "call-123".some)
      val contentObj        = toolResultMessage.content.flatMap(_.asObject)
      assert(contentObj.isDefined)
      assertEquals(contentObj.get("result").get.asNumber.get.toDouble, 5.0)
  }

  // Tests for Default message handling
  test("DefaultMessageHandler should convert single text content to string") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatCompletionRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      stubClient            = new StubClient(chatRequestRef, completionRequestRef)
      invoker               = OpenRouter.chatCompletion[IO](
                                client = stubClient,
                                config = OpenRouter.Config(model = "gpt-4o")
                              )
      history               = NEC.one(Message.User(List(ContentPart.Text("Hello, GPT!"))))
      _                    <- invoker.generate(history)
      capturedRequest      <- chatRequestRef.get
    yield
      assert(capturedRequest.isDefined)
      val request = capturedRequest.get
      assertEquals(request.model, "gpt-4o")
      assertEquals(request.messages.length, 1)
      val message = request.messages.head
      assertEquals(message.role, "user")
      assertEquals(message.content.flatMap(_.asString), Some("Hello, GPT!"))
  }

  test("DefaultMessageHandler should format tool results with name field") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatCompletionRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      stubClient            = new StubClient(chatRequestRef, completionRequestRef)
      invoker               = OpenRouter.chatCompletion[IO](
                                client = stubClient,
                                config = OpenRouter.Config(model = "gpt-4o")
                              )
      history               = NEC
                                .fromSeq(
                                  List(
                                    Message.User(List(ContentPart.Text("Calculate 2+3"))),
                                    Message.Tool(
                                      FunctionCall(
                                        name = "calculator",
                                        args = Struct(Map("a" -> Value.number(2.0), "b" -> Value.number(3.0))),
                                        callId = "call-123".some
                                      )
                                    ),
                                    Message.ToolResult(
                                      FunctionResponse(
                                        name = "calculator",
                                        response = Struct(Map("result" -> Value.number(5.0))),
                                        functionCallId = "call-123".some
                                      )
                                    )
                                  )
                                )
                                .get
      _                    <- invoker.generate(history)
      capturedRequest      <- chatRequestRef.get
    yield
      assert(capturedRequest.isDefined)
      val request = capturedRequest.get
      assertEquals(request.messages.length, 3)

      // Check tool result message (index 2)
      val toolResultMessage = request.messages(2)
      assertEquals(toolResultMessage.role, "tool")
      assertEquals(toolResultMessage.name, Some("calculator")) // Default should have name field
      assert(toolResultMessage.toolCallId.isDefined)
      assertEquals(toolResultMessage.toolCallId.get, "call-123")
      // Content should be a JSON object for default models
      val contentObject = toolResultMessage.content.get.asObject.get
      assertEquals(contentObject("result").get.asNumber.get.toDouble, 5.0)
  }

  test("Message handlers should handle multiple text parts correctly") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatCompletionRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      stubClient            = new StubClient(chatRequestRef, completionRequestRef)
      invoker               = OpenRouter.chatCompletion[IO](
                                client = stubClient,
                                config = OpenRouter.Config(model = "gpt-4o")
                              )
      history               = NEC.one(
                                Message.User(
                                  List(
                                    ContentPart.Text("First part"),
                                    ContentPart.Text("Second part")
                                  )
                                )
                              )
      _                    <- invoker.generate(history)
      capturedRequest      <- chatRequestRef.get
    yield
      assert(capturedRequest.isDefined)
      val request = capturedRequest.get
      assertEquals(request.messages.length, 1)
      val message = request.messages.head
      assertEquals(message.role, "user")
      assertEquals(message.content.flatMap(_.asString), Some("First part Second part"))
      assertEquals(message.images, None)
  }

  // Unit tests for message handlers in isolation
  test("MessageHandler.userContent should handle single text content as array") {
    val content = List(ContentPart.Text("Hello!"))
    val result  = MessageHandler.userContent(content)
    val array   = result.asArray.get
    assertEquals(array.length, 1)
    assertEquals(array(0).asObject.get("type").get.asString.get, "text")
    assertEquals(array(0).asObject.get("text").get.asString.get, "Hello!")
  }

  test("MessageHandler.userContent should handle multimodal content") {
    val content = List(
      ContentPart.Text("What's in this image?"),
      ContentPart.Image("base64encodedimage")
    )
    val result  = MessageHandler.userContent(content)
    val array   = result.asArray.get
    assertEquals(array.length, 2)
    assertEquals(array(0).asObject.get("type").get.asString.get, "text")
    assertEquals(array(1).asObject.get("type").get.asString.get, "image_url")
  }

  test("MessageHandler.default.convertToolResultMessage should include name field") {
    val content = FunctionResponse(
      name = "calculator",
      response = Struct(Map("result" -> Value.number(5.0))),
      functionCallId = "call-123".some
    )
    val result  = MessageHandler.default.convertToolResultMessage(content)
    assertEquals(result.role, "tool")
    assertEquals(result.name, Some("calculator"))
    assertEquals(result.toolCallId, "call-123".some)
    assertEquals(result.content.get.asObject.get("result").get.asNumber.get.toDouble, 5.0)
  }

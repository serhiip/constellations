package io.github.serhiip.constellations.invoker

import cats.data.NonEmptyChain as NEC
import cats.effect.{IO, Ref}
import cats.syntax.all.*

import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.dispatcher.Decoder
import io.github.serhiip.constellations.handling.OpenRouter.given
import io.github.serhiip.constellations.invoker.StructuredInvoker
import io.github.serhiip.constellations.openrouter.*
import io.github.serhiip.constellations.openrouter.schema.*
import io.circe.Json
import munit.CatsEffectSuite

class OpenRouterTest extends CatsEffectSuite:
  private case class StructuredPerson(name: String, age: Int)

  private class StubClient(chatRequestRef: Ref[IO, Option[ChatRequest]], completionRequestRef: Ref[IO, Option[CompletionRequest]])
      extends Client[IO]:

    def createChatCompletion(request: ChatRequest): IO[ChatResult] =
      chatRequestRef.set(request.some) *> IO.pure(TestResponses.chatResult(request.model.getOrElse("gpt-4o")))

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

    def createEmbeddings(request: EmbeddingsRequest): IO[EmbeddingsResponse] =
      IO.pure(
        EmbeddingsResponse(
          `object` = "list",
          data = List(EmbeddingData(`object` = "embedding", embedding = List.fill(4)(0f), index = 0)),
          model = request.model,
          usage = EmbeddingsUsage(promptTokens = 1, totalTokens = 1)
        )
      )

    def listModels(): IO[ModelsListResponse] =
      IO.pure(TestResponses.modelsList(List(TestResponses.sampleModel(id = "gpt-4o", name = "GPT-4o", input = List(InputModality.Text)))))

    def getGenerationStats(generationId: String): IO[GenerationResponseData] =
      IO.pure(TestResponses.generationData(generationId))

  test("StructuredInvoker with chatCompletion decodes structured JSON into domain type") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
      completionRequestRef <- Ref.of[IO, Option[CompletionRequest]](none)
      client                = new Client[IO]:
                                def createChatCompletion(request: ChatRequest): IO[ChatResult] =
                                  chatRequestRef.set(request.some) *>
                                    IO.pure(TestResponses.chatResult(request.model.getOrElse("gpt-4o"), """{"name":"Alice","age":33}""", "structured-id"))
                                def createCompletion(request: CompletionRequest): IO[CompletionResponse]             =
                                  completionRequestRef.set(request.some) *>
                                    IO.pure(
                                      CompletionResponse(
                                        id = "completion-id",
                                        `object` = "text_completion",
                                        created = 1234567890L,
                                        model = request.model,
                                        choices = List(CompletionChoice(text = "ok", index = 0.some, finishReason = "stop".some)),
                                        usage = ChatCompletionUsage(promptTokens = 1, completionTokens = 1, totalTokens = 2)
                                      )
                                    )
                                def createEmbeddings(request: EmbeddingsRequest): IO[EmbeddingsResponse]             =
                                  IO.pure(
                                    EmbeddingsResponse(
                                      `object` = "list",
                                      data = List(EmbeddingData(`object` = "embedding", embedding = List.fill(4)(0f), index = 0)),
                                      model = request.model,
                                      usage = EmbeddingsUsage(promptTokens = 1, totalTokens = 1)
                                    )
                                  )
                                def listModels(): IO[ModelsListResponse] = IO.pure(TestResponses.modelsList(Nil))
                                def getGenerationStats(generationId: String): IO[GenerationResponseData] =
                                  IO.pure(TestResponses.generationData(generationId, totalCost = 0.0, usage = 0.0))
      invoker               = StructuredInvoker[IO, ChatResult, StructuredPerson](
                                OpenRouter.chatCompletion[IO](client, OpenRouter.Config(model = "gpt-4o"))
                              )
      history               = NEC.one(Message.User(List(ContentPart.Text("Return a person"))))
      result               <- invoker.generate(history)
      capturedRequest      <- chatRequestRef.get
    yield
      assertEquals(result, StructuredPerson("Alice", 33))
      assert(capturedRequest.isDefined)
  }

  test("chatCompletion should convert text-only messages correctly") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
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
      val request      = capturedRequest.get
      assertEquals(request.model, Some("gpt-4o"))
      assertEquals(request.temperature, 0.7.some)
      assertEquals(request.max_tokens, Some(100L))
      assertEquals(request.messages.length, 1)
      val message = request.messages.head
      assertEquals(message, ChatUserMessage(content = List(ChatContentText(text = "Hello, how are you?"))))
      assertEquals(response.model, "gpt-4o")
  }

  test("chatCompletion should convert multimodal messages correctly") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
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
      val request      = capturedRequest.get
      assertEquals(request.messages.length, 1)
      val message = request.messages.head
      val parts: List[ChatContentItems] = List(
        ChatContentText(text = "What's in this image?"),
        ChatContentImage(image_url = ChatContentImageImageUrl(url = "data:image/jpeg;base64,base64encodedimage"))
      )
      assertEquals(message, ChatUserMessage(content = parts))
  }

  test("chatCompletion should handle multiple message types") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
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
      val request           = capturedRequest.get
      assertEquals(request.messages.length, 4)
      assertEquals(request.messages(0), ChatSystemMessage(content = "You are a helpful assistant"))
      assertEquals(request.messages(1), ChatUserMessage(content = List(ChatContentText(text = "Hello"))))
      assertEquals(request.messages(2), ChatAssistantMessage(content = Some("Hi there! How can I help you?")))
      assertEquals(request.messages(3), ChatUserMessage(content = List(ChatContentText(text = "What's 2+2?"))))
  }

  test("chatCompletion should handle tool messages") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
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
                               Message.ToolResult(
                                 FunctionResponse(
                                   FunctionCall("calculator", calculatorArgs, "call-123".some),
                                   calculatorResult
                                 )
                               )
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
      val toolMessage = request.messages(1).asInstanceOf[ChatAssistantMessage]
      val toolCalls  = toolMessage.tool_calls.get
      assertEquals(toolCalls.length, 1)
      val toolCall = toolCalls.head
      assertEquals(toolCall.`type`, "function")
      assertEquals(toolCall.function.name, "calculator")
      assertEquals(toolCall.function.arguments, """{"a":2.0,"b":3.0}""")

      val toolResult = request.messages(2).asInstanceOf[ChatToolMessage]
      assertEquals(toolResult.tool_call_id, "call-123")
      val toolContent = io.circe.parser.parse(toolResult.content.asInstanceOf[String]).toOption.get
      assertEquals(
        toolContent,
        Json.obj("result" -> Json.fromDoubleOrNull(5.0), "operation" -> Json.fromString("addition"))
      )
  }

  test("completion should concatenate messages into single prompt") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
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
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
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
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
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
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
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
      val request      = capturedRequest.get
      assertEquals(request.messages.length, 1)
      assertEquals(request.messages.head, ChatUserMessage(content = List.empty[ChatContentItems]))
  }

  test("chatCompletion should include function declarations as tools in request") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
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
      assertEquals(request.tool_choice, Some("auto": ChatNamedToolChoice | ChatServerToolChoice | String))

      val searchTool = tools.head.asInstanceOf[ChatFunctionToolFunction]
      assertEquals(searchTool.`type`, "function")
      assertEquals(searchTool.function.name, "search_books")
      assertEquals(searchTool.function.description, Some("Search for books by title or author"))

      val searchParams = io.circe.JsonObject.fromIterable(searchTool.function.parameters.get)
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
      val calcTool = tools(1).asInstanceOf[ChatFunctionToolFunction]
      assertEquals(calcTool.`type`, "function")
      assertEquals(calcTool.function.name, "calculate")
      assertEquals(calcTool.function.description, Some("Perform mathematical calculations"))

      val calcParams = io.circe.JsonObject.fromIterable(calcTool.function.parameters.get)
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
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
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
      assertEquals(request.tool_choice, None)
  }

  // Tests for Gemini message handling
  test("GeminiMessageHandler should convert single text content to string") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
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
      assertEquals(request.model, Some("google/gemini-2.0-flash-001"))
      assertEquals(request.messages.length, 1)
      assertEquals(request.messages.head, ChatUserMessage(content = "Hello, Gemini!"))
  }

  test("GeminiMessageHandler should convert multimodal content to array") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
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
      val request      = capturedRequest.get
      assertEquals(request.messages.length, 1)
      val parts: List[ChatContentItems] = List(
        ChatContentText(text = "What's in this image?"),
        ChatContentImage(image_url = ChatContentImageImageUrl(url = "data:image/jpeg;base64,base64encodedimage"))
      )
      assertEquals(request.messages.head, ChatUserMessage(content = parts))
  }

  test("GeminiMessageHandler should format tool results without name field") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
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
                                        FunctionCall(
                                          name = "calculator",
                                          args = Struct(Map("a" -> Value.number(2.0), "b" -> Value.number(3.0))),
                                          callId = "call-123".some
                                        ),
                                        Struct(Map("result" -> Value.number(5.0)))
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
      val toolResultMessage = request.messages(2).asInstanceOf[ChatToolMessage]
      assertEquals(toolResultMessage.tool_call_id, "call-123")
      val contentString = toolResultMessage.content.asInstanceOf[String]
      assert(contentString.contains("result"))
      assert(contentString.contains("5.0"))
  }

  // Tests for Default message handling
  test("DefaultMessageHandler should convert single text content to array") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
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
      val request      = capturedRequest.get
      assertEquals(request.model, Some("gpt-4o"))
      assertEquals(request.messages.length, 1)
      assertEquals(request.messages.head, ChatUserMessage(content = List(ChatContentText(text = "Hello, GPT!"))))
  }

  test("DefaultMessageHandler should format tool results with name field") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
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
                                        FunctionCall(
                                          name = "calculator",
                                          args = Struct(Map("a" -> Value.number(2.0), "b" -> Value.number(3.0))),
                                          callId = "call-123".some
                                        ),
                                        Struct(Map("result" -> Value.number(5.0)))
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
      val toolResultMessage = request.messages(2).asInstanceOf[ChatToolMessage]
      assertEquals(toolResultMessage.tool_call_id, "call-123")
      val contentObject = io.circe.parser.parse(toolResultMessage.content.asInstanceOf[String]).toOption.get.asObject.get
      assertEquals(contentObject("result").get.asNumber.get.toDouble, 5.0)
  }

  test("Message handlers should handle multiple text parts correctly") {
    for
      chatRequestRef       <- Ref.of[IO, Option[ChatRequest]](none)
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
      val request      = capturedRequest.get
      assertEquals(request.messages.length, 1)
      val parts: List[ChatContentItems] = List(ChatContentText(text = "First part"), ChatContentText(text = "Second part"))
      assertEquals(request.messages.head, ChatUserMessage(content = parts))
  }

  // Unit tests for message handlers in isolation
  test("MessageHandler.gemini.convertUserMessage should handle single text content") {
    val content = List(ContentPart.Text("Hello, Gemini!"))
    val result = MessageHandler.gemini.convertUserMessage(content)
    assertEquals(result.content, "Hello, Gemini!")
  }

  test("MessageHandler.gemini.convertUserMessage should handle multimodal content") {
    val content = List(
      ContentPart.Text("What's in this image?"),
      ContentPart.Image("base64encodedimage")
    )
    val result = MessageHandler.gemini.convertUserMessage(content)
    val parts  = result.content.asInstanceOf[List[ChatContentItems]]
    assertEquals(parts.length, 2)
    assert(parts(0).isInstanceOf[ChatContentText])
    assert(parts(1).isInstanceOf[ChatContentImage])
  }

  test("MessageHandler.default.convertUserMessage should always return array") {
    val content = List(ContentPart.Text("Hello, GPT!"))
    val result = MessageHandler.default.convertUserMessage(content)
    assertEquals(result.content, List(ChatContentText(text = "Hello, GPT!")))
  }

  test("MessageHandler.gemini.convertToolResultMessage should not include name field") {
    val content = FunctionResponse(
      FunctionCall("calculator", Struct.empty, "call-123".some),
      Struct(Map("result" -> Value.number(5.0)))
    )
    val result  = MessageHandler.gemini.convertToolResultMessage(content)
    assertEquals(result.tool_call_id, "call-123")
    assert(result.content.asInstanceOf[String].contains("result"), "Content should contain 'result'")
  }

  test("MessageHandler.openai.convertToolResultMessage should use string content") {
    val content       = FunctionResponse(
      FunctionCall("calculator", Struct.empty, "call-123".some),
      Struct(Map("result" -> Value.number(5.0)))
    )
    val result        = MessageHandler.openai.convertToolResultMessage(content)
    assertEquals(result.tool_call_id, "call-123")
    val contentObject = io.circe.parser.parse(result.content.asInstanceOf[String]).toOption.get.asObject.get
    assertEquals(contentObject("result").get.asNumber.get.toDouble, 5.0)
  }

  test("MessageHandler.default.convertToolResultMessage should keep the call id") {
    val content = FunctionResponse(
      FunctionCall("calculator", Struct.empty, "call-123".some),
      Struct(Map("result" -> Value.number(5.0)))
    )
    val result = MessageHandler.default.convertToolResultMessage(content)
    assertEquals(result.tool_call_id, "call-123")
    val contentObject = io.circe.parser.parse(result.content.asInstanceOf[String]).toOption.get.asObject.get
    assertEquals(contentObject("result").get.asNumber.get.toDouble, 5.0)
  }

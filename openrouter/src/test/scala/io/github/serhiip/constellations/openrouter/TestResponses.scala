package io.github.serhiip.constellations.openrouter

import io.github.serhiip.constellations.openrouter.schema.chat.choice.ChatChoice
import io.github.serhiip.constellations.openrouter.schema.chat.finish.reason.enum_.ChatFinishReasonEnum
import io.github.serhiip.constellations.openrouter.schema.chat.messages.ChatAssistantMessage
import io.github.serhiip.constellations.openrouter.schema.chat.result.ChatResult
import io.github.serhiip.constellations.openrouter.schema.chat.usage.ChatUsage
import io.github.serhiip.constellations.openrouter.schema.generation.response.{Data, DataDataRegion}
import io.github.serhiip.constellations.openrouter.schema.input.modality.InputModality
import io.github.serhiip.constellations.openrouter.schema.model.Model
import io.github.serhiip.constellations.openrouter.schema.model.architecture.ModelArchitecture
import io.github.serhiip.constellations.openrouter.schema.model.group.ModelGroup
import io.github.serhiip.constellations.openrouter.schema.model.links.ModelLinks
import io.github.serhiip.constellations.openrouter.schema.models.list.response.{Links, ModelsListResponse}
import io.github.serhiip.constellations.openrouter.schema.output.modality.OutputModality
import io.github.serhiip.constellations.openrouter.schema.parameter.Parameter
import io.github.serhiip.constellations.openrouter.schema.public.pricing.PublicPricing
import io.github.serhiip.constellations.openrouter.schema.top.provider.info.TopProviderInfo

object TestResponses:
  def chatResult(model: String, content: String = "Mock response", id: String = "test-id"): ChatResult =
    ChatResult(
      id = id,
      created = 1234567890L,
      model = model,
      systemFingerprint = None,
      choices = List(
        ChatChoice(
          finishReason = Some(ChatFinishReasonEnum.Stop),
          index = 0L,
          message = ChatAssistantMessage(content = Some(content))
        )
      ),
      usage = Some(ChatUsage(promptTokens = 10L, completionTokens = 5L, totalTokens = 15L))
    )

  def generationData(id: String, totalCost: Double = 0.05, usage: Double = 0.05): Data =
    Data(
      apiType = None,
      appId = None,
      cacheDiscount = None,
      cancelled = Some(false),
      createdAt = "2024-01-01T00:00:00Z",
      dataRegion = DataDataRegion.Global,
      externalUser = None,
      finishReason = Some("stop"),
      generationTime = Some(800),
      httpReferer = None,
      id = id,
      isByok = false,
      latency = Some(1000),
      model = "gpt-4o",
      moderationLatency = None,
      nativeFinishReason = Some("stop"),
      nativeTokensCached = Some(0L),
      nativeTokensCompletion = Some(50L),
      nativeTokensCompletionImages = None,
      nativeTokensPrompt = Some(100L),
      nativeTokensReasoning = Some(0L),
      numFetches = None,
      numInputAudioPrompt = None,
      numMediaCompletion = None,
      numMediaPrompt = None,
      numSearchResults = None,
      origin = "api",
      presetId = None,
      providerName = Some("OpenAI"),
      providerResponses = None,
      router = None,
      serviceTier = None,
      streamed = Some(false),
      tokensCompletion = Some(50L),
      tokensPrompt = Some(100L),
      totalCost = totalCost,
      upstreamId = Some("upstream-123"),
      upstreamInferenceCost = Some(totalCost),
      usage = usage,
      userAgent = None,
      webSearchEngine = None,
      workspaceId = None
    )

  def sampleModel(
      id: String = "anthropic/claude-3.7-sonnet",
      name: String = "Claude 3.7 Sonnet",
      input: List[InputModality] = List(InputModality.Text, InputModality.Image)
  ): Model =
    Model(
      architecture = ModelArchitecture(
        inputModalities = input,
        modality = Some("text"),
        outputModalities = List(OutputModality.Text),
        tokenizer = Some(ModelGroup.GPT)
      ),
      canonicalSlug = id,
      contextLength = Some(128000L),
      created = 1741818122L,
      defaultParameters = None,
      description = Some("State-of-the-art general model"),
      endpoints = Nil,
      id = id,
      links = ModelLinks(details = s"https://openrouter.ai/$id"),
      name = name,
      perRequestLimits = None,
      pricing = PublicPricing(
        prompt = "0.0000007",
        completion = "0.0000007",
        image = Some("0"),
        request = Some("0"),
        webSearch = Some("0"),
        internalReasoning = Some("0"),
        inputCacheRead = Some("0"),
        inputCacheWrite = Some("0")
      ),
      supportedParameters = List(Parameter.Temperature, Parameter.TopP),
      supportedVoices = None,
      topProvider = TopProviderInfo(isModerated = true, contextLength = Some(128000L), maxCompletionTokens = Some(16384L))
    )

  def modelsList(models: List[Model] = List(sampleModel())): ModelsListResponse =
    ModelsListResponse(data = models, links = Links(next = None), totalCount = models.size.toLong)

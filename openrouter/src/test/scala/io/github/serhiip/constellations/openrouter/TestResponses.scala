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
      system_fingerprint = None,
      choices = List(
        ChatChoice(
          finish_reason = Some(ChatFinishReasonEnum.Stop),
          index = 0L,
          message = ChatAssistantMessage(content = Some(content))
        )
      ),
      usage = Some(ChatUsage(prompt_tokens = 10L, completion_tokens = 5L, total_tokens = 15L))
    )

  def generationData(id: String, totalCost: Double = 0.05, usage: Double = 0.05): Data =
    Data(
      api_type = None,
      app_id = None,
      cache_discount = None,
      cancelled = Some(false),
      created_at = "2024-01-01T00:00:00Z",
      data_region = DataDataRegion.Global,
      external_user = None,
      finish_reason = Some("stop"),
      generation_time = Some(800),
      http_referer = None,
      id = id,
      is_byok = false,
      latency = Some(1000),
      model = "gpt-4o",
      moderation_latency = None,
      native_finish_reason = Some("stop"),
      native_tokens_cached = Some(0L),
      native_tokens_completion = Some(50L),
      native_tokens_completion_images = None,
      native_tokens_prompt = Some(100L),
      native_tokens_reasoning = Some(0L),
      num_fetches = None,
      num_input_audio_prompt = None,
      num_media_completion = None,
      num_media_prompt = None,
      num_search_results = None,
      origin = "api",
      preset_id = None,
      provider_name = Some("OpenAI"),
      provider_responses = None,
      router = None,
      service_tier = None,
      streamed = Some(false),
      tokens_completion = Some(50L),
      tokens_prompt = Some(100L),
      total_cost = totalCost,
      upstream_id = Some("upstream-123"),
      upstream_inference_cost = Some(totalCost),
      usage = usage,
      user_agent = None,
      web_search_engine = None,
      workspace_id = None
    )

  def sampleModel(
      id: String = "anthropic/claude-3.7-sonnet",
      name: String = "Claude 3.7 Sonnet",
      input: List[InputModality] = List(InputModality.Text, InputModality.Image)
  ): Model =
    Model(
      architecture = ModelArchitecture(
        input_modalities = input,
        modality = Some("text"),
        output_modalities = List(OutputModality.Text),
        tokenizer = Some(ModelGroup.GPT)
      ),
      canonical_slug = id,
      context_length = Some(128000L),
      created = 1741818122L,
      default_parameters = None,
      description = Some("State-of-the-art general model"),
      endpoints = Nil,
      id = id,
      links = ModelLinks(details = s"https://openrouter.ai/$id"),
      name = name,
      per_request_limits = None,
      pricing = PublicPricing(
        prompt = "0.0000007",
        completion = "0.0000007",
        image = Some("0"),
        request = Some("0"),
        web_search = Some("0"),
        internal_reasoning = Some("0"),
        input_cache_read = Some("0"),
        input_cache_write = Some("0")
      ),
      supported_parameters = List(Parameter.Temperature, Parameter.TopP),
      supported_voices = None,
      top_provider = TopProviderInfo(is_moderated = true, context_length = Some(128000L), max_completion_tokens = Some(16384L))
    )

  def modelsList(models: List[Model] = List(sampleModel())): ModelsListResponse =
    ModelsListResponse(data = models, links = Links(next = None), total_count = models.size.toLong)

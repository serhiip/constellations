package io.github.serhiip.constellations.openrouter

import cats.{Applicative, Monad}
import cats.syntax.applicative.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*

import org.typelevel.otel4s.Attribute

import io.github.serhiip.constellations.openrouter.schema.GenerationResponseData

trait GenerationStatsHandler[F[_]]:
  def onGenerationStats(stats: GenerationResponseData): F[Unit]

object GenerationStatsHandler:
  def apply[F[_]](f: GenerationResponseData => F[Unit]): GenerationStatsHandler[F] = new:
    def onGenerationStats(stats: GenerationResponseData): F[Unit] = f(stats)

  def noop[F[_]: Applicative]: GenerationStatsHandler[F] =
    GenerationStatsHandler(_ => ().pure[F])

  def combine[F[_]: Applicative](handlers: GenerationStatsHandler[F]*): GenerationStatsHandler[F] =
    GenerationStatsHandler(stats => handlers.toList.traverse_(_.onGenerationStats(stats)))

  def metrics[F[_]: Monad](meters: Client.Meters[F]): GenerationStatsHandler[F] =
    GenerationStatsHandler { stats =>
      val attributes = generationStatsAttributes(stats)
      for
        _ <- meters.totalCostCounter.add(stats.total_cost, attributes*)
        _ <- meters.usageCounter.add(stats.usage, attributes*)
        _ <- stats.cache_discount.traverse_(d => meters.cacheDiscountCounter.add(d, attributes*))
        _ <- stats.upstream_inference_cost.traverse_(cost => meters.upstreamInferenceCostCounter.add(cost, attributes*))
        _ <- stats.latency.traverse_(latency => meters.latencyCounter.add(latency.toLong, attributes*))
        _ <- stats.moderation_latency.traverse_(latency => meters.moderationLatencyCounter.add(latency.toLong, attributes*))
        _ <- stats.generation_time.traverse_(time => meters.generationTimeCounter.add(time.toLong, attributes*))
        _ <- stats.tokens_prompt.traverse_(tokens => meters.tokensPromptCounter.add(tokens, attributes*))
        _ <- stats.tokens_completion.traverse_(tokens => meters.tokensCompletionCounter.add(tokens, attributes*))
        _ <- stats.native_tokens_prompt.traverse_(tokens => meters.nativeTokensPromptCounter.add(tokens, attributes*))
        _ <- stats.native_tokens_completion.traverse_(tokens => meters.nativeTokensCompletionCounter.add(tokens, attributes*))
        _ <- stats.native_tokens_reasoning.traverse_(tokens => meters.nativeTokensReasoningCounter.add(tokens, attributes*))
        _ <- stats.native_tokens_cached.traverse_(tokens => meters.nativeTokensCachedCounter.add(tokens, attributes*))
        _ <- stats.num_media_prompt.traverse_(count => meters.numMediaPromptCounter.add(count, attributes*))
        _ <- stats.num_media_completion.traverse_(count => meters.numMediaCompletionCounter.add(count, attributes*))
        _ <- stats.num_search_results.traverse_(count => meters.numSearchResultsCounter.add(count, attributes*))
      yield ()
    }

  private[openrouter] def generationStatsAttributes(stats: GenerationResponseData): List[Attribute[?]] =
    List(
      Attribute("operation", "get-generation-stats"),
      Attribute("model", stats.model),
      Attribute("origin", stats.origin),
      Attribute("is_byok", stats.is_byok.toString)
    ) ++ List(
      stats.streamed.map(value => Attribute("streamed", value.toString)),
      stats.cancelled.map(value => Attribute("cancelled", value.toString)),
      stats.provider_name.map(value => Attribute("provider_name", value)),
      stats.finish_reason.map(value => Attribute("finish_reason", value)),
      stats.native_finish_reason.map(value => Attribute("native_finish_reason", value)),
      stats.external_user.map(user => Attribute("external_user", user)),
      stats.upstream_id.map(id => Attribute("upstream_id", id))
    ).flatten

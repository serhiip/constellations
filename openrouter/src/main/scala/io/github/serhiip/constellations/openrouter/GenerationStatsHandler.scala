package io.github.serhiip.constellations.openrouter

import cats.{Applicative, Monad}
import cats.syntax.applicative.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*

import org.typelevel.otel4s.Attribute

import io.github.serhiip.constellations.openrouter.schema.generation.response.Data

trait GenerationStatsHandler[F[_]]:
  def onGenerationStats(stats: Data): F[Unit]

object GenerationStatsHandler:
  def apply[F[_]](f: Data => F[Unit]): GenerationStatsHandler[F] = new:
    def onGenerationStats(stats: Data): F[Unit] = f(stats)

  def noop[F[_]: Applicative]: GenerationStatsHandler[F] =
    GenerationStatsHandler(_ => ().pure[F])

  def combine[F[_]: Applicative](handlers: GenerationStatsHandler[F]*): GenerationStatsHandler[F] =
    GenerationStatsHandler(stats => handlers.toList.traverse_(_.onGenerationStats(stats)))

  def metrics[F[_]: Monad](meters: Client.Meters[F]): GenerationStatsHandler[F] =
    GenerationStatsHandler { stats =>
      val attributes = generationStatsAttributes(stats)
      for
        _ <- meters.totalCostCounter.add(stats.totalCost, attributes*)
        _ <- meters.usageCounter.add(stats.usage, attributes*)
        _ <- stats.cacheDiscount.traverse_(d => meters.cacheDiscountCounter.add(d, attributes*))
        _ <- stats.upstreamInferenceCost.traverse_(cost => meters.upstreamInferenceCostCounter.add(cost, attributes*))
        _ <- stats.latency.traverse_(latency => meters.latencyCounter.add(latency.toLong, attributes*))
        _ <- stats.moderationLatency.traverse_(latency => meters.moderationLatencyCounter.add(latency.toLong, attributes*))
        _ <- stats.generationTime.traverse_(time => meters.generationTimeCounter.add(time.toLong, attributes*))
        _ <- stats.tokensPrompt.traverse_(tokens => meters.tokensPromptCounter.add(tokens, attributes*))
        _ <- stats.tokensCompletion.traverse_(tokens => meters.tokensCompletionCounter.add(tokens, attributes*))
        _ <- stats.nativeTokensPrompt.traverse_(tokens => meters.nativeTokensPromptCounter.add(tokens, attributes*))
        _ <- stats.nativeTokensCompletion.traverse_(tokens => meters.nativeTokensCompletionCounter.add(tokens, attributes*))
        _ <- stats.nativeTokensReasoning.traverse_(tokens => meters.nativeTokensReasoningCounter.add(tokens, attributes*))
        _ <- stats.nativeTokensCached.traverse_(tokens => meters.nativeTokensCachedCounter.add(tokens, attributes*))
        _ <- stats.numMediaPrompt.traverse_(count => meters.numMediaPromptCounter.add(count, attributes*))
        _ <- stats.numMediaCompletion.traverse_(count => meters.numMediaCompletionCounter.add(count, attributes*))
        _ <- stats.numSearchResults.traverse_(count => meters.numSearchResultsCounter.add(count, attributes*))
      yield ()
    }

  private[openrouter] def generationStatsAttributes(stats: Data): List[Attribute[?]] =
    List(
      Attribute("operation", "get-generation-stats"),
      Attribute("model", stats.model),
      Attribute("origin", stats.origin),
      Attribute("is_byok", stats.isByok.toString)
    ) ++ List(
      stats.streamed.map(value => Attribute("streamed", value.toString)),
      stats.cancelled.map(value => Attribute("cancelled", value.toString)),
      stats.providerName.map(value => Attribute("provider_name", value)),
      stats.finishReason.map(value => Attribute("finish_reason", value)),
      stats.nativeFinishReason.map(value => Attribute("native_finish_reason", value)),
      stats.externalUser.map(user => Attribute("external_user", user)),
      stats.upstreamId.map(id => Attribute("upstream_id", id))
    ).flatten

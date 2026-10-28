package io.github.serhiip.constellations.openrouter

import cats.{Applicative, Monad}
import cats.syntax.applicative.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*

import org.typelevel.otel4s.Attribute

trait GenerationStatsHandler[F[_]]:
  def onGenerationStats(stats: GenerationStats): F[Unit]

object GenerationStatsHandler:
  def apply[F[_]](f: GenerationStats => F[Unit]): GenerationStatsHandler[F] = new:
    def onGenerationStats(stats: GenerationStats): F[Unit] = f(stats)

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
        _ <- meters.upstreamInferenceCostCounter.add(stats.upstreamInferenceCost, attributes*)
        _ <- meters.latencyCounter.add(stats.latency.toLong, attributes*)
        _ <- stats.moderationLatency.traverse_(l => meters.moderationLatencyCounter.add(l.toLong, attributes*))
        _ <- meters.generationTimeCounter.add(stats.generationTime.toLong, attributes*)
        _ <- meters.tokensPromptCounter.add(stats.tokensPrompt.toLong, attributes*)
        _ <- meters.tokensCompletionCounter.add(stats.tokensCompletion.toLong, attributes*)
        _ <- meters.nativeTokensPromptCounter.add(stats.nativeTokensPrompt.toLong, attributes*)
        _ <- meters.nativeTokensCompletionCounter.add(stats.nativeTokensCompletion.toLong, attributes*)
        _ <- meters.nativeTokensReasoningCounter.add(stats.nativeTokensReasoning.toLong, attributes*)
        _ <- meters.nativeTokensCachedCounter.add(stats.nativeTokensCached.toLong, attributes*)
        _ <- stats.numMediaPrompt.traverse_(c => meters.numMediaPromptCounter.add(c.toLong, attributes*))
        _ <- stats.numMediaCompletion.traverse_(c => meters.numMediaCompletionCounter.add(c.toLong, attributes*))
        _ <- stats.numSearchResults.traverse_(c => meters.numSearchResultsCounter.add(c.toLong, attributes*))
      yield ()
    }

  private[openrouter] def generationStatsAttributes(stats: GenerationStats): List[Attribute[?]] =
    List(
      Attribute("operation", "get-generation-stats"),
      Attribute("model", stats.model),
      Attribute("origin", stats.origin),
      Attribute("is_byok", stats.isByok.toString),
      Attribute("streamed", stats.streamed.toString),
      Attribute("cancelled", stats.cancelled.toString),
      Attribute("provider_name", stats.providerName),
      Attribute("finish_reason", stats.finishReason),
      Attribute("native_finish_reason", stats.nativeFinishReason)
    ) ++ stats.externalUser.map(user => Attribute("external_user", user)).toList ++
      stats.upstreamId.map(id => Attribute("upstream_id", id)).toList

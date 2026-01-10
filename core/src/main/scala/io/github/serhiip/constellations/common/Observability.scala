package io.github.serhiip.constellations.common

import cats.effect.Resource
import cats.MonadThrow
import cats.syntax.all.*

import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Counter
import org.typelevel.otel4s.trace.{Span, SpanContext, SpanOps, Tracer}
import org.typelevel.otel4s.Attribute

private[constellations] object Observability:
  private val namePrefix = "constellations"

  private inline def spanName(parts: String*) = parts.mkString("-")

  extension [F[_]](tracer: Tracer[F])
    inline def span(parts: String*)(attributes: Attribute[?]*): SpanOps[F] = tracer.span(spanName(namePrefix +: parts*), attributes*)
    inline def span(parts: String*): SpanOps[F]                            = span(parts*)()

  private def populateContext[F[_]](logger: StructuredLogger[F], ctx: SpanContext): StructuredLogger[F] =
    logger.addContext("trace" -> ctx.traceIdHex, "span" -> ctx.spanIdHex, "sampled" -> ctx.isSampled)

  extension [F[_]: StructuredLogger](spanOps: SpanOps[F])
    inline def logged[A](action: StructuredLogger[F] => F[A]): F[A]                      = spanOps.use(_.logged(action))
    inline def loggedR[A](action: StructuredLogger[F] => Resource[F, A]): Resource[F, A] = spanOps.resource.flatMap(_.span.loggedR(action))

  extension [F[_]: StructuredLogger](span: Span[F])
    inline def logged[A](action: StructuredLogger[F] => F[A]): F[A]                      = action(populateContext(StructuredLogger[F], span.context))
    inline def loggedR[A](action: StructuredLogger[F] => Resource[F, A]): Resource[F, A] = action(
      populateContext(StructuredLogger[F], span.context)
    )

  object Metrics:
    inline def name(name: String): String = s"$namePrefix/$name"

  extension [F[_]: MonadThrow, A](fa: F[A])
    def withOperationCounters(success: Counter[F, Long], error: Counter[F, Long]): F[A] =
      fa.onError(_ => error.inc()).flatTap(_ => success.inc())

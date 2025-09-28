package io.github.serhiip.constellations.google

import cats.~>
import cats.effect.{Async, Resource}

import com.google.genai.{Client as GClient}
import com.google.genai.types.{Content, GenerateContentConfig, GenerateContentResponse}
import scala.jdk.CollectionConverters.*
import cats.data.NonEmptyChain as NEC

trait Client[F[_]]:
  def generate(
      model: String,
      contents: NEC[Content],
      config: Option[GenerateContentConfig] = None
  ): F[GenerateContentResponse]

object Client:
  final case class Config(project: String, location: String, vertexAI: Boolean = true)

  def resource[F[_]: Async](config: Config): Resource[F, Client[F]] =
    Resource
      .fromAutoCloseable(Async[F].delay {
        GClient.builder().project(config.project).location(config.location).vertexAI(config.vertexAI).build()
      })
      .map(createClient[F])

  private def createClient[F[_]: Async](underlying: GClient): Client[F] =
    new Client[F]:
      def generate(model: String, contents: NEC[Content], config: Option[GenerateContentConfig]) =
        val effectiveCfg = config.getOrElse(GenerateContentConfig.builder().build())
        Async[F].fromCompletableFuture(
          Async[F].delay(
            underlying.async.models.generateContent(model, contents.toNonEmptyList.toList.asJava, effectiveCfg)
          )
        )

  def mapK[F[_], G[_]](client: Client[F])(f: F ~> G): Client[G] = new Client[G]:
    def generate(model: String, contents: NEC[Content], config: Option[GenerateContentConfig]) =
      f(client.generate(model, contents, config))

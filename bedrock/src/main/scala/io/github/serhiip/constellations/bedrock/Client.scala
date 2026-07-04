package io.github.serhiip.constellations.bedrock

import cats.~>
import cats.data.Reader
import cats.effect.{Async, Resource}
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.auth.token.credentials.{SdkToken, StaticTokenProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.{ConverseRequest, ConverseResponse}

trait Client[F[_]]:
  def converse(request: ConverseRequest): F[ConverseResponse]

object Client:
  final case class Config(region: Region, apiKey: Option[String] = None)

  def apply[F[_]: Async]: Reader[Config, Resource[F, Client[F]]] =
    Reader: config =>
      Resource.fromAutoCloseable(Async[F].delay(buildUnderlying(config))).map(core[F])

  def core[F[_]: Async](underlying: BedrockRuntimeAsyncClient): Client[F] =
    CoreClient[F](underlying)

  def mapK[F[_], G[_]](client: Client[F])(f: F ~> G): Client[G] = new:
    def converse(request: ConverseRequest): G[ConverseResponse] = f(client.converse(request))

  private def buildUnderlying(config: Config): BedrockRuntimeAsyncClient =
    val builder = BedrockRuntimeAsyncClient.builder().region(config.region)
    config.apiKey.foreach: key =>
      val token: SdkToken = new { def token(): String = key }
      builder
        .credentialsProvider(AnonymousCredentialsProvider.create())
        .tokenProvider(StaticTokenProvider.create(token))
    builder.build()

  private final class CoreClient[F[_]: Async](underlying: BedrockRuntimeAsyncClient) extends Client[F]:
    def converse(request: ConverseRequest): F[ConverseResponse] =
      Async[F].fromCompletableFuture(Async[F].delay(underlying.converse(request)))

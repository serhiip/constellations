package io.github.serhiip.constellations.gcprag

import cats.{Applicative, Functor, MonadThrow, ~>}
import cats.effect.{Async, Resource}
import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all.*
import scala.concurrent.duration.{MILLISECONDS, SECONDS}
import scala.jdk.CollectionConverters.*
import scala.util.chaining.*

import org.typelevel.log4cats.{LoggerFactory, StructuredLogger}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{BucketBoundaries, Counter, Histogram, Meter}
import org.typelevel.otel4s.trace.Tracer

import io.github.serhiip.constellations.common.Observability
import io.github.serhiip.constellations.common.Observability.*

import com.google.api.gax.longrunning.OperationFuture
import com.google.cloud.aiplatform.v1.{
  CreateRagCorpusRequest,
  DeleteRagCorpusRequest,
  DeleteRagFileRequest,
  GetRagCorpusRequest,
  GetRagFileRequest,
  GcsSource,
  ImportRagFilesConfig,
  ImportRagFilesRequest,
  ListRagCorporaRequest,
  ListRagFilesRequest,
  RagCorpus,
  RagEmbeddingModelConfig,
  RagFile,
  RagFileChunkingConfig,
  RagFileTransformationConfig,
  RagQuery,
  RagRetrievalConfig,
  RagVectorDbConfig,
  RetrieveContextsRequest,
  UpdateRagCorpusRequest as GUpdateRagCorpusRequest,
  VertexRagDataServiceClient,
  VertexRagDataServiceSettings,
  VertexRagServiceClient,
  VertexRagServiceSettings
}
import com.google.longrunning.Operation

trait RagClient[F[_]]:
  def createCorpus(config: CorpusConfig): F[StartedLro[F, Corpus]]
  def getCorpus(name: String): F[Corpus]
  def listCorpora: F[List[Corpus]]
  def updateCorpus(request: UpdateCorpusRequest): F[StartedLro[F, Corpus]]
  def deleteCorpus(name: String): F[StartedLro[F, Unit]]

  def importFiles(corpusName: String, source: GcsImportSource): F[StartedLro[F, ImportResult]]
  def listFiles(corpusName: String): F[List[RagFileInfo]]
  def getFile(name: String): F[RagFileInfo]
  def deleteFile(name: String): F[StartedLro[F, Unit]]

  def retrieveContexts(corpusName: String, query: String, config: RetrievalConfig): F[List[RetrievedContext]]

  def getLro(handle: LroHandle): F[LroStatus]

object RagClient:

  final case class Config(project: String, location: String):
    def parent: String   = s"projects/$project/locations/$location"
    def endpoint: String = s"$location-aiplatform.googleapis.com:443"

  enum Error extends RuntimeException:
    case ApiFailure(operation: String, cause: Throwable)
    case InvalidConfig(message: String)
    case ImportFailed(
        importedCount: Long,
        failedCount: Long,
        skippedCount: Long,
        partialFailuresGcsPath: Option[String],
        partialFailuresBigQueryTable: Option[String]
    )

    override def getMessage(): String = this match
      case ApiFailure(operation, cause)                     => s"GCP RAG Engine $operation failed: ${Option(cause.getMessage).getOrElse(cause.toString)}"
      case InvalidConfig(message)                           => message
      case ImportFailed(imported, failed, skipped, gcs, bq) =>
        val sink = gcs.orElse(bq).fold("")(path => s"; partial failures: $path")
        s"GCP RAG Engine import-files completed with failures: imported=$imported failed=$failed skipped=$skipped$sink"

  def resource[F[_]: Async](config: Config): Resource[F, RagClient[F]] =
    for
      dataClient <- Resource.fromAutoCloseable(
                      Async[F].blocking(
                        VertexRagDataServiceClient.create(VertexRagDataServiceSettings.newBuilder().setEndpoint(config.endpoint).build())
                      )
                    )
      ragClient  <- Resource.fromAutoCloseable(
                      Async[F].blocking(
                        VertexRagServiceClient.create(VertexRagServiceSettings.newBuilder().setEndpoint(config.endpoint).build())
                      )
                    )
    yield create(config, dataClient, ragClient)

  def create[F[_]: Async](config: Config, dataClient: VertexRagDataServiceClient, ragClient: VertexRagServiceClient): RagClient[F] =
    new JavaRagClient(config, dataClient, ragClient)

  def apply[F[_]: MonadCancelThrow: Tracer: LoggerFactory: Meter: Applicative](delegate: RagClient[F]): F[RagClient[F]] =
    (Meters.create[F], LoggerFactory[F].create).mapN: (meters, logger) =>
      given StructuredLogger[F] = logger
      observed(delegate, meters)

  def metered[F[_]: MonadCancelThrow](delegate: RagClient[F], meters: Meters[F]): RagClient[F] = new:
    private def counted[A](operation: String)(fa: F[A]): F[A] =
      fa.withOperationCounters(meters.operationCounter, meters.errorCounter, Attribute("operation", operation))

    private def withCountedAwait[A](operation: String)(started: F[StartedLro[F, A]]): F[StartedLro[F, A]] =
      meters.operationStartDuration
        .recordDuration(MILLISECONDS, Attribute("operation", operation))
        .surround(
          started.map(s =>
            StartedLro(
              s.handle,
              meters.operationDuration
                .recordDuration(SECONDS, Attribute("operation", operation))
                .surround(counted(operation)(s.await))
            )
          )
        )

    def createCorpus(config: CorpusConfig): F[StartedLro[F, Corpus]]         = withCountedAwait("create-corpus")(delegate.createCorpus(config))
    def getCorpus(name: String): F[Corpus]                                   = counted("get-corpus")(delegate.getCorpus(name))
    def listCorpora: F[List[Corpus]]                                         = counted("list-corpora")(delegate.listCorpora)
    def updateCorpus(request: UpdateCorpusRequest): F[StartedLro[F, Corpus]] =
      withCountedAwait("update-corpus")(delegate.updateCorpus(request))
    def deleteCorpus(name: String): F[StartedLro[F, Unit]]                   = withCountedAwait("delete-corpus")(delegate.deleteCorpus(name))

    def importFiles(corpusName: String, source: GcsImportSource): F[StartedLro[F, ImportResult]] =
      withCountedAwait("import-files"):
        delegate
          .importFiles(corpusName, source)
          .map: s =>
            StartedLro(
              s.handle,
              s.await.attempt.flatMap {
                case Right(result)                 => meters.importedFilesCounter.add(result.importedCount).as(result)
                case Left(err: Error.ImportFailed) => meters.failedImportFilesCounter.add(err.failedCount) *> err.raiseError
                case Left(err)                     => err.raiseError
              }
            )
    def listFiles(corpusName: String): F[List[RagFileInfo]]                                      = counted("list-files")(delegate.listFiles(corpusName))
    def getFile(name: String): F[RagFileInfo]                                                    = counted("get-file")(delegate.getFile(name))
    def deleteFile(name: String): F[StartedLro[F, Unit]]                                         = withCountedAwait("delete-file")(delegate.deleteFile(name))

    def retrieveContexts(corpusName: String, query: String, config: RetrievalConfig): F[List[RetrievedContext]] =
      counted("retrieve-contexts")(delegate.retrieveContexts(corpusName, query, config))

    def getLro(handle: LroHandle): F[LroStatus] = counted("get-lro")(delegate.getLro(handle))

  def traced[F[_]: MonadThrow: Tracer: StructuredLogger](delegate: RagClient[F]): RagClient[F] = new:
    private val delegateName = Option(delegate.getClass.getCanonicalName()).getOrElse(delegate.getClass.getName)

    private def withTracedAwait[A](
        operation: String,
        startLog: StructuredLogger[F] => F[Unit],
        attrs: List[Attribute[?]],
        onSuccess: (StructuredLogger[F], A) => F[Unit]
    )(started: F[StartedLro[F, A]]): F[StartedLro[F, A]] =
      Tracer[F]
        .span("rag-client", operation, "start")
        .logged: logger =>
          for
            _    <- startLog(logger)
            span <- Tracer[F].currentSpanOrNoop
            _    <- span.addAttributes(attrs*)
            s    <- started
            _    <- span.addAttributes(
                      Attribute("lro.name", s.handle.name),
                      Attribute("lro.kind", s.handle.kind.toString)
                    )
            _    <- logger.trace(s"Started LRO ${s.handle.name} (${s.handle.kind})")
          yield StartedLro(
            s.handle,
            Tracer[F].span("rag-client", operation, "await").logged(logger => (s.await.flatTap(onSuccess(logger, _))))
          )

    def createCorpus(config: CorpusConfig): F[StartedLro[F, Corpus]] =
      withTracedAwait[Corpus](
        "create-corpus",
        _.trace(s"Creating RAG corpus '${config.displayName}' using $delegateName"),
        List(Attribute("display_name", config.displayName)),
        (logger, result) => logger.trace(s"Created RAG corpus ${result.name}")
      )(delegate.createCorpus(config))

    def getCorpus(name: String): F[Corpus] =
      Tracer[F]
        .span("rag-client", "get-corpus")
        .logged: logger =>
          for
            _      <- logger.trace(s"Getting RAG corpus $name")
            span   <- Tracer[F].currentSpanOrNoop
            _      <- span.addAttribute(Attribute("corpus_name", name))
            result <- delegate.getCorpus(name)
          yield result

    def listCorpora: F[List[Corpus]] =
      Tracer[F]
        .span("rag-client", "list-corpora")
        .logged: logger =>
          for
            _      <- logger.trace("Listing RAG corpora")
            result <- delegate.listCorpora
            _      <- logger.trace(s"Listed ${result.size} RAG corpora")
          yield result

    def updateCorpus(request: UpdateCorpusRequest): F[StartedLro[F, Corpus]] =
      withTracedAwait[Corpus](
        "update-corpus",
        _.trace(s"Updating RAG corpus ${request.name}"),
        List(Attribute("corpus_name", request.name)),
        (logger, result) => logger.trace(s"Updated RAG corpus ${result.name}")
      )(delegate.updateCorpus(request))

    def deleteCorpus(name: String): F[StartedLro[F, Unit]] =
      withTracedAwait[Unit](
        "delete-corpus",
        _.trace(s"Deleting RAG corpus $name"),
        List(Attribute("corpus_name", name)),
        (logger, _) => logger.trace(s"Deleted RAG corpus $name")
      )(delegate.deleteCorpus(name))

    def importFiles(corpusName: String, source: GcsImportSource): F[StartedLro[F, ImportResult]] =
      withTracedAwait[ImportResult](
        "import-files",
        _.trace(s"Importing ${source.uris.size} URI(s) into $corpusName"),
        List(Attribute("corpus_name", corpusName), Attribute("uri_count", source.uris.size.toLong)),
        (logger, result) =>
          for
            span <- Tracer[F].currentSpanOrNoop
            _    <- span.addAttributes(Attribute("imported_count", result.importedCount), Attribute("skipped_count", result.skippedCount))
            _    <-
              logger.trace(
                s"Import finished for $corpusName: imported=${result.importedCount} skipped=${result.skippedCount}; corpus has ${result.files.size} file(s)"
              )
          yield ()
      ):
        delegate
          .importFiles(corpusName, source)
          .map: s =>
            StartedLro(
              s.handle,
              s.await.onError {
                case err: Error.ImportFailed =>
                  StructuredLogger[F].error(err)(
                    s"RAG import failed for $corpusName: imported=${err.importedCount} failed=${err.failedCount} skipped=${err.skippedCount}"
                  )
                case err                     => StructuredLogger[F].error(err)(s"RAG import failed for $corpusName")
              }
            )

    def listFiles(corpusName: String): F[List[RagFileInfo]] =
      Tracer[F]
        .span("rag-client", "list-files")
        .logged: logger =>
          for
            _      <- logger.trace(s"Listing files in $corpusName")
            span   <- Tracer[F].currentSpanOrNoop
            _      <- span.addAttribute(Attribute("corpus_name", corpusName))
            result <- delegate.listFiles(corpusName)
            _      <- logger.trace(s"Listed ${result.size} file(s) in $corpusName")
          yield result

    def getFile(name: String): F[RagFileInfo] =
      Tracer[F]
        .span("rag-client", "get-file")
        .logged: logger =>
          for
            _      <- logger.trace(s"Getting RAG file $name")
            span   <- Tracer[F].currentSpanOrNoop
            _      <- span.addAttribute(Attribute("file_name", name))
            result <- delegate.getFile(name)
          yield result

    def deleteFile(name: String): F[StartedLro[F, Unit]] =
      withTracedAwait[Unit](
        "delete-file",
        _.trace(s"Deleting RAG file $name"),
        List(Attribute("file_name", name)),
        (logger, _) => logger.trace(s"Deleted RAG file $name")
      )(delegate.deleteFile(name))

    def retrieveContexts(corpusName: String, query: String, config: RetrievalConfig): F[List[RetrievedContext]] =
      Tracer[F]
        .span("rag-client", "retrieve-contexts")
        .logged: logger =>
          for
            _      <- logger.trace(s"Retrieving contexts from $corpusName (query length=${query.length})")
            span   <- Tracer[F].currentSpanOrNoop
            _      <- span.addAttributes(
                        Attribute("corpus_name", corpusName),
                        Attribute("query_length", query.length.toLong)
                      )
            _      <- config.topK.traverse_(k => span.addAttribute(Attribute("top_k", k.toLong)))
            result <- delegate.retrieveContexts(corpusName, query, config)
            _      <- logger.trace(s"Retrieved ${result.size} context(s) from $corpusName")
          yield result

    def getLro(handle: LroHandle): F[LroStatus] =
      Tracer[F]
        .span("rag-client", "get-lro")
        .logged: logger =>
          for
            _      <- logger.trace(s"Getting LRO status for ${handle.name} (${handle.kind})")
            span   <- Tracer[F].currentSpanOrNoop
            _      <- span.addAttribute(Attribute("lro.name", handle.name))
            _      <- span.addAttribute(Attribute("lro.kind", handle.kind.toString))
            result <- delegate.getLro(handle)
            _      <- logger.trace(s"LRO ${handle.name} status: $result")
          yield result

  def observed[F[_]: MonadCancelThrow: Tracer: StructuredLogger](delegate: RagClient[F], meters: Meters[F]): RagClient[F] =
    traced(metered(delegate, meters))

  final case class Meters[F[_]](
      operationCounter: Counter[F, Long],
      errorCounter: Counter[F, Long],
      importedFilesCounter: Counter[F, Long],
      failedImportFilesCounter: Counter[F, Long],
      operationDuration: Histogram[F, Double],
      operationStartDuration: Histogram[F, Double]
  )

  object Meters:
    def create[F[_]: Meter: Applicative]: F[Meters[F]] =
      val metric = Observability.Metrics.component("rag_client")
      (
        Meter[F].counter[Long](metric("operation")("count")).create,
        Meter[F].counter[Long](metric("error")("count")).create,
        Meter[F].counter[Long](metric("imported_files")("count")).create,
        Meter[F].counter[Long](metric("failed_import_files")("count")).create,
        Meter[F]
          .histogram[Double](metric("operation")("duration"))
          .withUnit("s")
          .withDescription("Latency of RagClient LRO awaits")
          .withExplicitBucketBoundaries(BucketBoundaries(5, 10, 15, 30, 45, 60, 90, 120, 180))
          .create,
        Meter[F]
          .histogram[Double](metric("operation_start")("duration"))
          .withUnit("ms")
          .withDescription("Latency of RagClient LRO starts")
          .create
      ).mapN(Meters(_, _, _, _, _, _))

  def mapK[F[_], G[_]: Functor](client: RagClient[F])(f: F ~> G): RagClient[G] = new:
    private def mapStarted[A](started: F[StartedLro[F, A]]): G[StartedLro[G, A]] =
      f(started).map(s => StartedLro(s.handle, f(s.await)))

    def createCorpus(config: CorpusConfig): G[StartedLro[G, Corpus]]         = mapStarted(client.createCorpus(config))
    def getCorpus(name: String): G[Corpus]                                   = f(client.getCorpus(name))
    def listCorpora: G[List[Corpus]]                                         = f(client.listCorpora)
    def updateCorpus(request: UpdateCorpusRequest): G[StartedLro[G, Corpus]] = mapStarted(client.updateCorpus(request))
    def deleteCorpus(name: String): G[StartedLro[G, Unit]]                   = mapStarted(client.deleteCorpus(name))

    def importFiles(corpusName: String, source: GcsImportSource): G[StartedLro[G, ImportResult]] = mapStarted(
      client.importFiles(corpusName, source)
    )

    def listFiles(corpusName: String): G[List[RagFileInfo]] = f(client.listFiles(corpusName))
    def getFile(name: String): G[RagFileInfo]               = f(client.getFile(name))
    def deleteFile(name: String): G[StartedLro[G, Unit]]    = mapStarted(client.deleteFile(name))

    def retrieveContexts(corpusName: String, query: String, config: RetrievalConfig): G[List[RetrievedContext]] =
      f(client.retrieveContexts(corpusName, query, config))
    def getLro(handle: LroHandle): G[LroStatus]                                                                 = f(client.getLro(handle))

  private final class JavaRagClient[F[_]: Async](config: Config, dataClient: VertexRagDataServiceClient, ragClient: VertexRagServiceClient)
      extends RagClient[F]:

    def createCorpus(corpusConfig: CorpusConfig): F[StartedLro[F, Corpus]] =
      val endpoint        =
        if corpusConfig.embeddingModelEndpoint.startsWith("projects/") then corpusConfig.embeddingModelEndpoint
        else s"${config.parent}/${corpusConfig.embeddingModelEndpoint.stripPrefix("/")}"
      val embeddingConfig = RagEmbeddingModelConfig
        .newBuilder()
        .setVertexPredictionEndpoint(RagEmbeddingModelConfig.VertexPredictionEndpoint.newBuilder().setEndpoint(endpoint).build())
        .build()
      val vectorDbBuilder = RagVectorDbConfig.newBuilder().setRagEmbeddingModelConfig(embeddingConfig)
      val vectorDb        = corpusConfig.vectorDb match
        case VectorDb.RagManaged                          => vectorDbBuilder.setRagManagedDb(RagVectorDbConfig.RagManagedDb.newBuilder().build()).build()
        case VectorDb.VertexVectorSearch(index, endpoint) =>
          vectorDbBuilder
            .setVertexVectorSearch(RagVectorDbConfig.VertexVectorSearch.newBuilder().setIndex(index).setIndexEndpoint(endpoint).build())
            .build()
      val corpusBuilder   = RagCorpus
        .newBuilder()
        .setDisplayName(corpusConfig.displayName)
        .setVectorDbConfig(vectorDb)
        .tap(b => corpusConfig.description.foreach(b.setDescription))

      val request = CreateRagCorpusRequest.newBuilder().setParent(config.parent).setRagCorpus(corpusBuilder.build()).build()
      startLro(LroKind.CreateCorpus, dataClient.createRagCorpusAsync(request))(corpus => toCorpus(corpus).pure[F])
        .map(withAdaptError("create-corpus"))

    def getCorpus(name: String): F[Corpus] =
      Async[F]
        .blocking(toCorpus(dataClient.getRagCorpus(GetRagCorpusRequest.newBuilder().setName(name).build())))
        .adaptError(Error.ApiFailure("get-corpus", _))

    def listCorpora: F[List[Corpus]] =
      Async[F]
        .blocking(
          dataClient
            .listRagCorpora(ListRagCorporaRequest.newBuilder().setParent(config.parent).build())
            .iterateAll()
            .asScala
            .toList
            .map(toCorpus)
        )
        .adaptError(Error.ApiFailure("list-corpora", _))

    def updateCorpus(request: UpdateCorpusRequest): F[StartedLro[F, Corpus]] =
      val op = for
        existing <- Async[F].blocking(dataClient.getRagCorpus(request.name))
        updated   = existing.toBuilder
                      .tap(b => request.displayName.foreach(b.setDisplayName))
                      .tap(b => request.description.foreach(b.setDescription))
                      .build()
        started  <- startLro(
                      LroKind.UpdateCorpus,
                      dataClient.updateRagCorpusAsync(GUpdateRagCorpusRequest.newBuilder().setRagCorpus(updated).build())
                    )(corpus => toCorpus(corpus).pure[F])
      yield withAdaptError("update-corpus")(started)
      op.adaptError(Error.ApiFailure("update-corpus", _))

    def deleteCorpus(name: String): F[StartedLro[F, Unit]] =
      startLroUnit(LroKind.DeleteCorpus, dataClient.deleteRagCorpusAsync(DeleteRagCorpusRequest.newBuilder().setName(name).build()))
        .map(withAdaptError("delete-corpus"))

    def importFiles(corpusName: String, source: GcsImportSource): F[StartedLro[F, ImportResult]] =
      Error.InvalidConfig("GCS import source requires at least one URI").raiseError.whenA(source.uris.isEmpty) >> {
        val gcs                 = GcsSource.newBuilder().addAllUris(source.uris.asJava).build()
        val importConfigBuilder = ImportRagFilesConfig
          .newBuilder()
          .setGcsSource(gcs)
          .tap: b =>
            source.chunking.foreach: chunking =>
              val fixed = RagFileChunkingConfig.FixedLengthChunking
                .newBuilder()
                .setChunkSize(chunking.chunkSize)
                .setChunkOverlap(chunking.chunkOverlap)
                .build()

              val chunkingConfig = RagFileChunkingConfig.newBuilder().setFixedLengthChunking(fixed).build()
              val transformation = RagFileTransformationConfig.newBuilder().setRagFileChunkingConfig(chunkingConfig).build()
              b.setRagFileTransformationConfig(transformation)

        val request = ImportRagFilesRequest.newBuilder().setParent(corpusName).setImportRagFilesConfig(importConfigBuilder.build()).build()
        startLro(LroKind.ImportFiles, dataClient.importRagFilesAsync(request)) { response =>
          val imported = response.getImportedRagFilesCount
          val failed   = response.getFailedRagFilesCount
          val skipped  = response.getSkippedRagFilesCount
          val gcsPath  = Option.when(response.hasPartialFailuresGcsPath)(response.getPartialFailuresGcsPath)
          val bqTable  = Option.when(response.hasPartialFailuresBigqueryTable)(response.getPartialFailuresBigqueryTable)
          for
            _     <- Error
                       .ImportFailed(imported, failed, skipped, gcsPath, bqTable)
                       .raiseError[F, Unit]
                       .whenA(failed > 0)
            files <- Async[F].blocking(
                       dataClient.listRagFiles(ListRagFilesRequest.newBuilder().setParent(corpusName).build()).iterateAll().asScala.toList
                     )
          yield ImportResult(
            importedCount = imported,
            failedCount = failed,
            skippedCount = skipped,
            files = files.map(toFile),
            partialFailuresGcsPath = gcsPath,
            partialFailuresBigQueryTable = bqTable
          )
        }.map: started =>
          StartedLro(
            started.handle,
            started.await.adaptError {
              case err: Error => err
              case err        => Error.ApiFailure("import-files", err)
            }
          )
      }

    def listFiles(corpusName: String): F[List[RagFileInfo]] =
      Async[F]
        .blocking(
          dataClient.listRagFiles(ListRagFilesRequest.newBuilder().setParent(corpusName).build()).iterateAll().asScala.toList.map(toFile)
        )
        .adaptError(Error.ApiFailure("list-files", _))

    def getFile(name: String): F[RagFileInfo] =
      Async[F]
        .blocking(toFile(dataClient.getRagFile(GetRagFileRequest.newBuilder().setName(name).build())))
        .adaptError(Error.ApiFailure("get-file", _))

    def deleteFile(name: String): F[StartedLro[F, Unit]] =
      startLroUnit(LroKind.DeleteFile, dataClient.deleteRagFileAsync(DeleteRagFileRequest.newBuilder().setName(name).build()))
        .map(withAdaptError("delete-file"))

    def retrieveContexts(corpusName: String, query: String, retrieval: RetrievalConfig): F[List[RetrievedContext]] =
      val op = Async[F].blocking:
        val retrievalBuilder = RagRetrievalConfig
          .newBuilder()
          .tap: b =>
            retrieval.topK.foreach(b.setTopK)
            retrieval.vectorDistanceThreshold.foreach { threshold =>
              b.setFilter(RagRetrievalConfig.Filter.newBuilder().setVectorDistanceThreshold(threshold).build())
            }

        val ragQuery = RagQuery.newBuilder().setText(query).setRagRetrievalConfig(retrievalBuilder.build()).build()
        val store    = RetrieveContextsRequest.VertexRagStore
          .newBuilder()
          .addRagResources(RetrieveContextsRequest.VertexRagStore.RagResource.newBuilder().setRagCorpus(corpusName).build())
          .build()
        val request  = RetrieveContextsRequest.newBuilder().setParent(config.parent).setQuery(ragQuery).setVertexRagStore(store).build()
        ragClient
          .retrieveContexts(request)
          .getContexts
          .getContextsList
          .asScala
          .toList
          .map: ctx =>
            RetrievedContext(
              text = ctx.getText,
              sourceUri = Option(ctx.getSourceUri).filter(_.nonEmpty),
              sourceDisplayName = Option(ctx.getSourceDisplayName).filter(_.nonEmpty),
              score = Option.when(ctx.hasScore)(ctx.getScore)
            )

      op.adaptError(Error.ApiFailure("retrieve-contexts", _))

    def getLro(handle: LroHandle): F[LroStatus] =
      Async[F]
        .blocking(toLroStatus(dataClient.getOperationsClient.getOperation(handle.name)))
        .adaptError(Error.ApiFailure("get-lro", _))

    private def startLro[A, M, B](kind: LroKind, start: => OperationFuture[A, M])(finish: A => F[B]): F[StartedLro[F, B]] =
      for
        future <- Async[F].delay(start)
        name   <- Async[F].blocking(future.getName)
      yield StartedLro(LroHandle(name, kind), future.liftTo.flatMap(finish))

    private def startLroUnit[A, M](kind: LroKind, start: => OperationFuture[A, M]): F[StartedLro[F, Unit]] =
      startLro(kind, start)(_ => ().pure[F])

    private def withAdaptError[A](operation: String)(started: StartedLro[F, A]): StartedLro[F, A] =
      StartedLro(started.handle, started.await.adaptError(Error.ApiFailure(operation, _)))

    private def toLroStatus(operation: Operation): LroStatus =
      if !operation.getDone then LroStatus.Running
      else if operation.hasError then
        val message = Option(operation.getError.getMessage).filter(_.nonEmpty).getOrElse(operation.getError.toString)
        LroStatus.Failed(message)
      else LroStatus.Succeeded

    private def toCorpus(corpus: RagCorpus): Corpus =
      Corpus(name = corpus.getName, displayName = corpus.getDisplayName, description = Option(corpus.getDescription).filter(_.nonEmpty))

    private def toFile(file: RagFile): RagFileInfo =
      RagFileInfo(name = file.getName, displayName = file.getDisplayName, description = Option(file.getDescription).filter(_.nonEmpty))

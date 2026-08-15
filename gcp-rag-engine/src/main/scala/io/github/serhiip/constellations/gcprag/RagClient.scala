package io.github.serhiip.constellations.gcprag

import cats.{Applicative, Functor, MonadThrow, ~>}
import cats.data.NonEmptyList as NEL
import cats.effect.{Async, Resource, Sync}
import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all.*
import cats.effect.syntax.all.*
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
import com.google.api.gax.rpc.{ApiException, StatusCode}
import com.google.cloud.aiplatform.v1.{
  RagQuery,
  RagRetrievalConfig,
  RetrieveContextsRequest,
  VertexRagServiceClient,
  VertexRagServiceSettings
}
import com.google.cloud.aiplatform.v1beta1.{
  BatchCreateRagDataSchemasRequest,
  BatchCreateRagMetadataRequest,
  BigQueryDestination,
  CreateRagCorpusRequest,
  CreateRagDataSchemaRequest,
  CreateRagMetadataRequest,
  DeleteRagCorpusRequest,
  DeleteRagFileRequest,
  FileStatus,
  GetRagCorpusRequest,
  GetRagFileRequest,
  GcsDestination,
  GcsSource,
  ImportRagFilesConfig,
  ImportRagFilesRequest,
  ImportRagFilesResponse,
  ListRagCorporaRequest,
  ListRagDataSchemasRequest,
  ListRagFilesRequest,
  ListRagMetadataRequest,
  MetadataValue as GMetadataValue,
  RagCorpus,
  RagDataSchema,
  RagEmbeddingModelConfig,
  RagFile,
  RagFileChunkingConfig,
  RagFileTransformationConfig,
  RagMetadata,
  RagMetadataSchemaDetails,
  RagVectorDbConfig,
  UpdateRagCorpusRequest as GUpdateRagCorpusRequest,
  UserSpecifiedMetadata,
  VertexRagDataServiceClient,
  VertexRagDataServiceSettings
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
  def createDataSchema(corpusName: String, schema: DataSchema): F[Unit]
  def setFileMetadata(fileName: String, entries: NEL[MetadataEntry]): F[Unit]
  def retrieveContexts(corpusName: String, query: String, config: RetrievalConfig): F[List[RetrievedContext]]
  def getLro(handle: LroHandle): F[LroStatus]
  def getImportResult(
      handle: LroHandle,
      corpusName: String,
      sinkGcs: Option[String] = None,
      sinkBq: Option[String] = None
  ): F[ImportResult]

object RagClient:

  final case class Config(project: String, location: String):
    def parent: String   = s"projects/$project/locations/$location"
    def endpoint: String = s"$location-aiplatform.googleapis.com:443"

  enum Error extends RuntimeException:
    case ApiFailure(operation: String, cause: Throwable)
    case InvalidConfig(message: String)
    case LroRunning
    case LroFailed(message: String)
    case ImportFailed(
        importedCount: Long,
        failedCount: Long,
        skippedCount: Long,
        partialFailuresGcsPath: Option[String],
        partialFailuresBigQueryTable: Option[String]
    )
    case ImportOperationFailed(corpusName: String, uris: NEL[String], lroName: String, cause: Throwable)

    override def getMessage(): String = this match
      case ApiFailure(operation, cause)                     => s"GCP RAG Engine $operation failed: ${Option(cause.getMessage).getOrElse(cause.toString)}"
      case InvalidConfig(message)                           => message
      case LroRunning                                       => "GCP RAG Engine LRO is still running"
      case LroFailed(message)                               => s"GCP RAG Engine LRO failed: $message"
      case ImportFailed(imported, failed, skipped, gcs, bq) =>
        val sink = gcs.orElse(bq).fold("")(path => s"; partial failures: $path")
        s"GCP RAG Engine import-files completed with failures: imported=$imported failed=$failed skipped=$skipped$sink"
      case ImportOperationFailed(corpus, uris, lro, cause)  =>
        val reason = Option(cause.getMessage).getOrElse(cause.toString)
        s"GCP RAG Engine import-files LRO $lro failed for corpus $corpus over ${uris.size} uri(s) [${uris.toList.mkString(", ")}]: $reason"

  def resource[F[_]: Async](config: Config): Resource[F, RagClient[F]] =
    for
      dataClient <- Resource.fromAutoCloseable(
                      Sync[F].blocking(
                        VertexRagDataServiceClient.create(VertexRagDataServiceSettings.newBuilder().setEndpoint(config.endpoint).build())
                      )
                    )
      ragClient  <- Resource.fromAutoCloseable(
                      Sync[F].blocking(
                        VertexRagServiceClient.create(VertexRagServiceSettings.newBuilder().setEndpoint(config.endpoint).build())
                      )
                    )
      ledger     <- ImportResultLedger[F]().toResource
    yield create(config, dataClient, ragClient, ledger)

  def create[F[_]: Async](config: Config, dataClient: VertexRagDataServiceClient, ragClient: VertexRagServiceClient): RagClient[F] =
    create(config, dataClient, ragClient, ImportResultLedger.core(_ => "".pure[F]))

  def create[F[_]: Async](
      config: Config,
      dataClient: VertexRagDataServiceClient,
      ragClient: VertexRagServiceClient,
      ledger: ImportResultLedger[F]
  ): RagClient[F] =
    JavaRagClient(config, dataClient, ragClient, ledger)

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

    def listFiles(corpusName: String): F[List[RagFileInfo]] = counted("list-files")(delegate.listFiles(corpusName))
    def getFile(name: String): F[RagFileInfo]               = counted("get-file")(delegate.getFile(name))
    def deleteFile(name: String): F[StartedLro[F, Unit]]    = withCountedAwait("delete-file")(delegate.deleteFile(name))

    def createDataSchema(corpusName: String, schema: DataSchema): F[Unit]       =
      counted("create-data-schema")(delegate.createDataSchema(corpusName, schema))
    def setFileMetadata(fileName: String, entries: NEL[MetadataEntry]): F[Unit] =
      counted("set-file-metadata")(delegate.setFileMetadata(fileName, entries))

    def retrieveContexts(corpusName: String, query: String, config: RetrievalConfig): F[List[RetrievedContext]] =
      counted("retrieve-contexts")(delegate.retrieveContexts(corpusName, query, config))

    def getLro(handle: LroHandle): F[LroStatus] = counted("get-lro")(delegate.getLro(handle))
    def getImportResult(
        handle: LroHandle,
        corpusName: String,
        sinkGcs: Option[String] = None,
        sinkBq: Option[String] = None
    ): F[ImportResult] =
      counted("get-import-result")(delegate.getImportResult(handle, corpusName, sinkGcs, sinkBq))

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
            _    <- span.addAttributes(Attribute("lro.name", s.handle.name), Attribute("lro.kind", s.handle.kind.toString))
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

    def createDataSchema(corpusName: String, schema: DataSchema): F[Unit] =
      Tracer[F]
        .span("rag-client", "create-data-schema")
        .logged: logger =>
          for
            _    <- logger.trace(s"Creating data schema '${schema.key}' on $corpusName")
            span <- Tracer[F].currentSpanOrNoop
            _    <- span.addAttributes(Attribute("corpus_name", corpusName), Attribute("schema_key", schema.key))
            _    <- delegate.createDataSchema(corpusName, schema)
          yield ()

    def setFileMetadata(fileName: String, entries: NEL[MetadataEntry]): F[Unit] =
      Tracer[F]
        .span("rag-client", "set-file-metadata")
        .logged: logger =>
          for
            _    <- logger.trace(s"Setting ${entries.size} metadata entr(y/ies) on $fileName")
            span <- Tracer[F].currentSpanOrNoop
            _    <- span.addAttributes(Attribute("file_name", fileName), Attribute("entry_count", entries.size.toLong))
            _    <- delegate.setFileMetadata(fileName, entries)
          yield ()

    def retrieveContexts(corpusName: String, query: String, config: RetrievalConfig): F[List[RetrievedContext]] =
      Tracer[F]
        .span("rag-client", "retrieve-contexts")
        .logged: logger =>
          for
            _      <- logger.trace(s"Retrieving contexts from $corpusName (query length=${query.length})")
            span   <- Tracer[F].currentSpanOrNoop
            _      <- span.addAttributes(
                        Attribute("corpus_name", corpusName),
                        Attribute("query_length", query.length.toLong),
                        Attribute("has_metadata_filter", config.metadataFilter.isDefined),
                        Attribute("rag_file_ids_count", config.ragFileIds.size.toLong)
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

    def getImportResult(
        handle: LroHandle,
        corpusName: String,
        sinkGcs: Option[String] = None,
        sinkBq: Option[String] = None
    ): F[ImportResult] =
      Tracer[F]
        .span("rag-client", "get-import-result")
        .logged: logger =>
          for
            _      <- logger.trace(s"Getting import result for ${handle.name} on $corpusName")
            span   <- Tracer[F].currentSpanOrNoop
            _      <- span.addAttributes(
                        Attribute("lro.name", handle.name),
                        Attribute("lro.kind", handle.kind.toString),
                        Attribute("corpus_name", corpusName)
                      )
            result <- delegate.getImportResult(handle, corpusName, sinkGcs, sinkBq)
            _      <-
              logger.trace(
                s"Import result for ${handle.name}: imported=${result.importedCount} failed=${result.failedCount} skipped=${result.skippedCount}"
              )
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

    def listFiles(corpusName: String): G[List[RagFileInfo]]                     = f(client.listFiles(corpusName))
    def getFile(name: String): G[RagFileInfo]                                   = f(client.getFile(name))
    def deleteFile(name: String): G[StartedLro[G, Unit]]                        = mapStarted(client.deleteFile(name))
    def createDataSchema(corpusName: String, schema: DataSchema): G[Unit]       = f(client.createDataSchema(corpusName, schema))
    def setFileMetadata(fileName: String, entries: NEL[MetadataEntry]): G[Unit] =
      f(client.setFileMetadata(fileName, entries))

    def retrieveContexts(corpusName: String, query: String, config: RetrievalConfig): G[List[RetrievedContext]] =
      f(client.retrieveContexts(corpusName, query, config))
    def getLro(handle: LroHandle): G[LroStatus]                                                                 = f(client.getLro(handle))
    def getImportResult(
        handle: LroHandle,
        corpusName: String,
        sinkGcs: Option[String] = None,
        sinkBq: Option[String] = None
    ): G[ImportResult] =
      f(client.getImportResult(handle, corpusName, sinkGcs, sinkBq))

  private final class JavaRagClient[F[_]: Async](
      config: Config,
      dataClient: VertexRagDataServiceClient,
      ragClient: VertexRagServiceClient,
      ledger: ImportResultLedger[F]
  ) extends RagClient[F]:

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
        case VectorDb.RagManaged(retrieval)               =>
          val managed = RagVectorDbConfig.RagManagedDb.newBuilder()
          retrieval match
            case RagManagedRetrieval.Knn                       =>
              managed.setKnn(RagVectorDbConfig.RagManagedDb.KNN.newBuilder().build())
            case RagManagedRetrieval.Ann(treeDepth, leafCount) =>
              val ann = RagVectorDbConfig.RagManagedDb.ANN
                .newBuilder()
                .tap(b => treeDepth.foreach(b.setTreeDepth))
                .tap(b => leafCount.foreach(b.setLeafCount))
              managed.setAnn(ann.build())
          vectorDbBuilder.setRagManagedDb(managed.build()).build()
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
      Sync[F]
        .blocking(toCorpus(dataClient.getRagCorpus(GetRagCorpusRequest.newBuilder().setName(name).build())))
        .adaptError(Error.ApiFailure("get-corpus", _))

    def listCorpora: F[List[Corpus]] =
      Sync[F]
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
        existing <- Sync[F].blocking(dataClient.getRagCorpus(request.name))
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
      val metadata          = source.metadata.getOrElse(ImportFileMetadata())
      val (sinkGcs, sinkBq) = source.resultSink match
        case ImportResultSink.Gcs(path)      => (path.some, none[String])
        case ImportResultSink.BigQuery(path) => (none[String], path.some)

      Error
        .InvalidConfig("metadata.entries requires ImportResultSink.Gcs so imported fileIds can be read from the sink")
        .raiseError
        .whenA(metadata.entries.isDefined && sinkGcs.isEmpty) >> {
        val gcs                 = GcsSource.newBuilder().addAllUris(source.uris.toList.asJava).build()
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
            source.resultSink match
              case ImportResultSink.Gcs(outputUriPrefix) =>
                b.setImportResultGcsSink(GcsDestination.newBuilder().setOutputUriPrefix(outputUriPrefix).build())
              case ImportResultSink.BigQuery(outputUri)  =>
                b.setImportResultBigquerySink(BigQueryDestination.newBuilder().setOutputUri(outputUri).build())

        val request = ImportRagFilesRequest.newBuilder().setParent(corpusName).setImportRagFilesConfig(importConfigBuilder.build()).build()

        for
          _       <- NEL.fromList(metadata.schemas).traverse_(createDataSchemas(corpusName, _))
          started <- startLro(LroKind.ImportFiles, dataClient.importRagFilesAsync(request)) { response =>
                       finishImport(corpusName, response, sinkGcs, sinkBq).flatTap { _ =>
                         (metadata.entries, sinkGcs).tupled.traverse_ { (entries, path) =>
                           stampImportedMetadata(corpusName, path, entries)
                         }
                       }
                     }
        yield StartedLro(
          started.handle,
          started.await.adaptError {
            case err: Error => err
            case err        => Error.ImportOperationFailed(corpusName, source.uris, started.handle.name, err)
          }
        )
      }

    def createDataSchema(corpusName: String, schema: DataSchema): F[Unit] =
      createDataSchemas(corpusName, NEL.one(schema))

    def setFileMetadata(fileName: String, entries: NEL[MetadataEntry]): F[Unit] =
      val op =
        for
          existing            <- listMetadataKeys(fileName)
          (toUpdate, toCreate) = entries.toList.partition(entry => existing.contains(entry.key))
          _                   <- batchCreateFileMetadata(fileName, toCreate)
          _                   <- toUpdate.traverse_(updateFileMetadata(fileName, _))
        yield ()
      op.adaptError(Error.ApiFailure("set-file-metadata", _))

    def listFiles(corpusName: String): F[List[RagFileInfo]] =
      Sync[F]
        .blocking(
          dataClient.listRagFiles(ListRagFilesRequest.newBuilder().setParent(corpusName).build()).iterateAll().asScala.toList.map(toFile)
        )
        .adaptError(Error.ApiFailure("list-files", _))

    def getFile(name: String): F[RagFileInfo] =
      Sync[F]
        .blocking(toFile(dataClient.getRagFile(GetRagFileRequest.newBuilder().setName(name).build())))
        .adaptError(Error.ApiFailure("get-file", _))

    def deleteFile(name: String): F[StartedLro[F, Unit]] =
      startLroUnit(LroKind.DeleteFile, dataClient.deleteRagFileAsync(DeleteRagFileRequest.newBuilder().setName(name).build()))
        .map(withAdaptError("delete-file"))

    def retrieveContexts(corpusName: String, query: String, retrieval: RetrievalConfig): F[List[RetrievedContext]] =
      val op = Sync[F].blocking:
        val filter =
          Option.when(retrieval.vectorDistanceThreshold.isDefined || retrieval.metadataFilter.isDefined):
            RagRetrievalConfig.Filter
              .newBuilder()
              .tap: b =>
                retrieval.vectorDistanceThreshold.foreach(b.setVectorDistanceThreshold)
                retrieval.metadataFilter.foreach(b.setMetadataFilter)
              .build()

        val retrievalBuilder = RagRetrievalConfig
          .newBuilder()
          .tap: b =>
            retrieval.topK.foreach(b.setTopK)
            filter.foreach(b.setFilter)

        val resource = RetrieveContextsRequest.VertexRagStore.RagResource
          .newBuilder()
          .setRagCorpus(corpusName)
          .tap: b =>
            if retrieval.ragFileIds.nonEmpty then
              b.addAllRagFileIds(retrieval.ragFileIds.asJava)
              ()
          .build()

        val ragQuery = RagQuery.newBuilder().setText(query).setRagRetrievalConfig(retrievalBuilder.build()).build()
        val store    = RetrieveContextsRequest.VertexRagStore.newBuilder().addRagResources(resource).build()
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
      Sync[F]
        .blocking(toLroStatus(dataClient.getOperationsClient.getOperation(handle.name)))
        .adaptError(Error.ApiFailure("get-lro", _))

    def getImportResult(
        handle: LroHandle,
        corpusName: String,
        sinkGcs: Option[String] = None,
        sinkBq: Option[String] = None
    ): F[ImportResult] =
      val op =
        for
          _         <- Error.InvalidConfig("getImportResult requires LroKind.ImportFiles").raiseError.unlessA(handle.kind == LroKind.ImportFiles)
          operation <- Sync[F].blocking(dataClient.getOperationsClient.getOperation(handle.name))
          _         <- Error.LroRunning.raiseError.unlessA(operation.getDone)
          _         <- Error
                         .LroFailed(Option(operation.getError.getMessage).filter(_.nonEmpty).getOrElse(operation.getError.toString))
                         .raiseError
                         .whenA(operation.hasError)
          response  <- Sync[F].blocking(operation.getResponse.unpack(classOf[ImportRagFilesResponse]))
          result    <- finishImport(corpusName, response, sinkGcs, sinkBq)
        yield result
      op.adaptError {
        case err: Error => err
        case err        => Error.ApiFailure("get-import-result", err)
      }

    private val MaxBatchCreate = 500

    private def createDataSchemas(corpusName: String, schemas: NEL[DataSchema]): F[Unit] =
      val op =
        for
          existing  <- listSchemaKeys(corpusName)
          remaining  = schemas.filterNot(schema => existing.contains(schema.key))
          _         <- remaining.grouped(MaxBatchCreate).toList.flatMap(NEL.fromList).traverse_(batchCreateDataSchemas(corpusName, _))
        yield ()
      op.adaptError(Error.ApiFailure("create-data-schema", _))

    private def listSchemaKeys(corpusName: String): F[Set[String]] =
      Sync[F].blocking(
        dataClient
          .listRagDataSchemas(ListRagDataSchemasRequest.newBuilder().setParent(corpusName).build())
          .iterateAll()
          .asScala
          .map(schema => resourceKey(schema.getName, schema.getKey))
          .toSet
      )

    private def batchCreateDataSchemas(corpusName: String, schemas: NEL[DataSchema]): F[Unit] =
      val batch = BatchCreateRagDataSchemasRequest
        .newBuilder()
        .setParent(corpusName)
        .addAllRequests(schemas.map(toCreateSchemaRequest(corpusName, _)).toList.asJava)
        .build()
      startLro(LroKind.CreateDataSchema, dataClient.batchCreateRagDataSchemasAsync(batch))(_ => ().pure[F])
        .flatMap(_.await)
        .recoverWith {
          case err if alreadyExists(err) => ().pure[F]
        }

    private def toCreateSchemaRequest(corpusName: String, schema: DataSchema): CreateRagDataSchemaRequest =
      val details = RagMetadataSchemaDetails
        .newBuilder()
        .setType(toSchemaDataType(schema.dataType))
        .setGranularity(RagMetadataSchemaDetails.Granularity.GRANULARITY_FILE_LEVEL)
        .setSearchStrategy(
          RagMetadataSchemaDetails.SearchStrategy
            .newBuilder()
            .setSearchStrategyType(toSearchStrategy(schema.search))
            .build()
        )
        .build()
      val body    = RagDataSchema.newBuilder().setKey(schema.key).setSchemaDetails(details).build()
      CreateRagDataSchemaRequest
        .newBuilder()
        .setParent(corpusName)
        .setRagDataSchema(body)
        .setRagDataSchemaId(schema.key)
        .build()

    private def listMetadataKeys(fileName: String): F[Set[String]] =
      Sync[F].blocking(
        dataClient
          .listRagMetadata(ListRagMetadataRequest.newBuilder().setParent(fileName).build())
          .iterateAll()
          .asScala
          .map { metadata =>
            val protoKey = Option.when(metadata.hasUserSpecifiedMetadata)(metadata.getUserSpecifiedMetadata.getKey).getOrElse("")
            resourceKey(metadata.getName, protoKey)
          }
          .toSet
      )

    private def batchCreateFileMetadata(fileName: String, entries: List[MetadataEntry]): F[Unit] =
      if entries.isEmpty then ().pure[F]
      else
        entries.grouped(MaxBatchCreate).toList.traverse_ { chunk =>
          val batch = BatchCreateRagMetadataRequest
            .newBuilder()
            .setParent(fileName)
            .addAllRequests(chunk.map(toCreateMetadataRequest(fileName, _)).asJava)
            .build()
          startLro(LroKind.CreateFileMetadata, dataClient.batchCreateRagMetadataAsync(batch))(_ => ().pure[F])
            .flatMap(_.await)
            .recoverWith {
              case err if alreadyExists(err) => chunk.traverse_(updateFileMetadata(fileName, _))
            }
        }

    private def toCreateMetadataRequest(fileName: String, entry: MetadataEntry): CreateRagMetadataRequest =
      CreateRagMetadataRequest
        .newBuilder()
        .setParent(fileName)
        .setRagMetadata(toRagMetadata(entry))
        .setRagMetadataId(entry.key)
        .build()

    private def updateFileMetadata(fileName: String, entry: MetadataEntry): F[Unit] =
      Sync[F].blocking(dataClient.updateRagMetadata(toRagMetadata(entry).toBuilder.setName(s"$fileName/ragMetadata/${entry.key}").build())).void

    private def toRagMetadata(entry: MetadataEntry): RagMetadata =
      RagMetadata
        .newBuilder()
        .setUserSpecifiedMetadata(UserSpecifiedMetadata.newBuilder().setKey(entry.key).setValue(toMetadataValue(entry.value)).build())
        .build()

    private def resourceKey(name: String, protoKey: String): String =
      Option(protoKey).filter(_.nonEmpty).getOrElse(name.split('/').lastOption.getOrElse(name))

    private def alreadyExists(err: Throwable): Boolean =
      Iterator
        .iterate(err)(_.getCause)
        .takeWhile(_ != null)
        .take(8)
        .exists {
          case api: ApiException => api.getStatusCode.getCode == StatusCode.Code.ALREADY_EXISTS
          case _                 => false
        }

    private def finishImport(
        corpusName: String,
        response: ImportRagFilesResponse,
        sinkGcs: Option[String],
        sinkBq: Option[String]
    ): F[ImportResult] =
      val imported = response.getImportedRagFilesCount
      val failed   = response.getFailedRagFilesCount
      val skipped  = response.getSkippedRagFilesCount
      val gcsPath  = Option.when(response.hasPartialFailuresGcsPath)(response.getPartialFailuresGcsPath).orElse(sinkGcs)
      val bqTable  = Option.when(response.hasPartialFailuresBigqueryTable)(response.getPartialFailuresBigqueryTable).orElse(sinkBq)
      for
        _     <- Error.ImportFailed(imported, failed, skipped, gcsPath, bqTable).raiseError[F, Unit].whenA(failed > 0)
        files <- Sync[F].blocking(
                   dataClient
                     .listRagFiles(ListRagFilesRequest.newBuilder().setParent(corpusName).build())
                     .iterateAll()
                     .asScala
                     .toList
                     .map(toFile)
                 )
      yield ImportResult(
        importedCount = imported,
        failedCount = failed,
        skippedCount = skipped,
        files = files,
        partialFailuresGcsPath = gcsPath,
        partialFailuresBigQueryTable = bqTable
      )

    private def startLro[A, M, B](
        kind: LroKind,
        start: => OperationFuture[A, M]
    )(finish: A => F[B]): F[StartedLro[F, B]] = // TODO: separate LRO descriptor from result
      for
        future <- Async[F].delay(start)
        name   <- Sync[F].blocking(future.getName)
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

    private def toSchemaDataType(dataType: MetadataType): RagMetadataSchemaDetails.DataType = dataType match
      case MetadataType.String  => RagMetadataSchemaDetails.DataType.STRING
      case MetadataType.Integer => RagMetadataSchemaDetails.DataType.INTEGER
      case MetadataType.Float   => RagMetadataSchemaDetails.DataType.FLOAT
      case MetadataType.Boolean => RagMetadataSchemaDetails.DataType.BOOLEAN

    private def toSearchStrategy(search: MetadataSearch): RagMetadataSchemaDetails.SearchStrategy.SearchStrategyType =
      search match
        case MetadataSearch.Exact => RagMetadataSchemaDetails.SearchStrategy.SearchStrategyType.EXACT_SEARCH
        case MetadataSearch.None  => RagMetadataSchemaDetails.SearchStrategy.SearchStrategyType.NO_SEARCH

    private def toMetadataValue(value: MetadataValue): GMetadataValue =
      val builder = GMetadataValue.newBuilder()
      value match
        case MetadataValue.Str(v)     => builder.setStrValue(v)
        case MetadataValue.Int64(v)   => builder.setIntValue(v)
        case MetadataValue.Float32(v) => builder.setFloatValue(v)
        case MetadataValue.Bool(v)    => builder.setBoolValue(v)
      builder.build()

    private def stampImportedMetadata(corpusName: String, sinkPath: String, entries: NEL[MetadataEntry]): F[Unit] =
      ledger
        .read(sinkPath)
        .flatMap { outcomes =>
          outcomes
            .collect {
              case outcome if outcome.status == "OK" =>
                outcome.fileId.map(ImportResultLedger.ragFileName(corpusName, _))
            }
            .flatten
            .traverse_(fileName => setFileMetadata(fileName, entries))
        }
        .adaptError(Error.ApiFailure("import-result-sink", _))

    private def toFile(file: RagFile): RagFileInfo =
      val status     = Option.when(file.hasFileStatus)(file.getFileStatus)
      val sourceUris =
        Option
          .when(file.hasGcsSource)(file.getGcsSource.getUrisList.asScala.toList)
          .getOrElse(Nil)
      RagFileInfo(
        name = file.getName,
        displayName = file.getDisplayName,
        description = Option(file.getDescription).filter(_.nonEmpty),
        state = status.map(_.getState).fold(FileState.Unspecified) {
          case FileStatus.State.ACTIVE                                            => FileState.Active
          case FileStatus.State.ERROR                                             => FileState.Failed
          case FileStatus.State.STATE_UNSPECIFIED | FileStatus.State.UNRECOGNIZED => FileState.Unspecified
        },
        errorStatus = status.map(_.getErrorStatus).filter(_.nonEmpty),
        userMetadata = Option(file.getUserMetadata).filter(_.nonEmpty),
        sourceUris = sourceUris
      )

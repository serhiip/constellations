package io.github.serhiip.constellations.gcprag

import cats.data.NonEmptyList as NEL
import cats.effect.IO
import java.util.concurrent.{ExecutionException, Executor, TimeUnit}
import scala.jdk.CollectionConverters.*
import munit.CatsEffectSuite
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, anyLong, eq as eqTo}
import org.mockito.Mockito.{doAnswer, doReturn, mock, times, verify, when}
import org.mockito.invocation.InvocationOnMock

import com.google.api.gax.longrunning.OperationFuture
import com.google.cloud.aiplatform.v1.{RagContexts, RetrieveContextsRequest, RetrieveContextsResponse, VertexRagServiceClient}
import com.google.cloud.aiplatform.v1beta1.{
  CreateRagCorpusOperationMetadata,
  CreateRagCorpusRequest,
  CreateRagDataSchemaRequest,
  CreateRagMetadataRequest,
  DeleteOperationMetadata,
  DeleteRagCorpusRequest,
  DeleteRagFileRequest,
  FileStatus,
  GetRagCorpusRequest,
  GetRagFileRequest,
  ImportRagFilesOperationMetadata,
  ImportRagFilesRequest,
  ImportRagFilesResponse,
  ListRagCorporaRequest,
  ListRagFilesRequest,
  RagCorpus,
  RagDataSchema,
  RagFile,
  RagMetadata,
  RagMetadataSchemaDetails,
  UpdateRagCorpusOperationMetadata,
  UpdateRagCorpusRequest as GUpdateRagCorpusRequest,
  VertexRagDataServiceClient
}
import com.google.longrunning.{Operation, OperationsClient}
import com.google.protobuf.{Any as ProtoAny, Empty}
import com.google.rpc.Status

final class RagClientTest extends CatsEffectSuite:

  private val config      = RagClient.Config(project = "demo", location = "us-east4")
  private val corpusName  = s"${config.parent}/ragCorpora/123"
  private val fileName    = s"$corpusName/ragFiles/f1"
  private val opName      = s"${config.parent}/operations/op-1"
  private val sinkPath    = "gs://bucket/results/run.ndjson"
  private val defaultSink = ImportResultSink.Gcs(sinkPath)

  test("config builds regional parent and endpoint") {
    assertEquals(config.parent, "projects/demo/locations/us-east4")
    assertEquals(config.endpoint, "us-east4-aiplatform.googleapis.com:443")
  }

  test("getCorpus maps proto fields and wraps failures") {
    val data   = mockDataClient()
    val rag    = mockRagClient()
    val client = RagClient.create[IO](config, data, rag)

    when(data.getRagCorpus(any(classOf[GetRagCorpusRequest])))
      .thenReturn(RagCorpus.newBuilder().setName(corpusName).setDisplayName("docs").setDescription("d").build())

    for
      corpus <- client.getCorpus(corpusName)
      _      <- IO(assertEquals(corpus, Corpus(corpusName, "docs", Some("d"))))
      _      <- IO {
                  when(data.getRagCorpus(any(classOf[GetRagCorpusRequest])))
                    .thenThrow(RuntimeException("boom"))
                }
      err    <- client.getCorpus(corpusName).attempt
    yield err match
      case Left(RagClient.Error.ApiFailure("get-corpus", cause)) => assertEquals(cause.getMessage, "boom")
      case other                                                 => fail(s"expected ApiFailure(get-corpus), got $other")
  }

  test("createCorpus builds request and returns mapped corpus") {
    val data    = mockDataClient()
    val rag     = mockRagClient()
    val client  = RagClient.create[IO](config, data, rag)
    val created = RagCorpus.newBuilder().setName(corpusName).setDisplayName("docs").setDescription("about").build()

    doReturn(completed[RagCorpus, CreateRagCorpusOperationMetadata](created, opName))
      .when(data)
      .createRagCorpusAsync(any(classOf[CreateRagCorpusRequest]))

    for
      started <- client.createCorpus(CorpusConfig(displayName = "docs", description = Some("about")))
      _       <- IO {
                   assertEquals(started.handle, LroHandle(opName, LroKind.CreateCorpus))
                 }
      corpus  <- started.await
      _       <- IO(assertEquals(corpus, Corpus(corpusName, "docs", Some("about"))))
    yield
      val captor  = ArgumentCaptor.forClass(classOf[CreateRagCorpusRequest])
      verify(data).createRagCorpusAsync(captor.capture())
      val request = captor.getValue
      assertEquals(request.getParent, config.parent)
      assertEquals(request.getRagCorpus.getDisplayName, "docs")
      assertEquals(request.getRagCorpus.getDescription, "about")
      assert(request.getRagCorpus.getVectorDbConfig.hasRagManagedDb)
      assert(request.getRagCorpus.getVectorDbConfig.getRagManagedDb.hasKnn)
      assert(
        request.getRagCorpus.getVectorDbConfig.getRagEmbeddingModelConfig.getVertexPredictionEndpoint.getEndpoint
          .endsWith("publishers/google/models/text-embedding-005")
      )
  }

  test("createCorpus sets RagManaged ANN retrieval when configured") {
    val data    = mockDataClient()
    val rag     = mockRagClient()
    val client  = RagClient.create[IO](config, data, rag)
    val created = RagCorpus.newBuilder().setName(corpusName).setDisplayName("docs").build()

    doReturn(completed[RagCorpus, CreateRagCorpusOperationMetadata](created, opName))
      .when(data)
      .createRagCorpusAsync(any(classOf[CreateRagCorpusRequest]))

    for
      started <- client.createCorpus(
                   CorpusConfig(
                     displayName = "docs",
                     vectorDb = VectorDb.RagManaged(RagManagedRetrieval.Ann(treeDepth = Some(2), leafCount = Some(500)))
                   )
                 )
      _       <- started.await
    yield
      val captor  = ArgumentCaptor.forClass(classOf[CreateRagCorpusRequest])
      verify(data).createRagCorpusAsync(captor.capture())
      val managed = captor.getValue.getRagCorpus.getVectorDbConfig.getRagManagedDb
      assert(managed.hasAnn)
      assertEquals(managed.getAnn.getTreeDepth, 2)
      assertEquals(managed.getAnn.getLeafCount, 500)
  }

  test("createCorpus sets Vertex Vector Search when configured") {
    val data     = mockDataClient()
    val rag      = mockRagClient()
    val client   = RagClient.create[IO](config, data, rag)
    val created  = RagCorpus.newBuilder().setName(corpusName).setDisplayName("docs").build()
    val index    = "projects/123/locations/us-east4/indexes/456"
    val endpoint = "projects/123/locations/us-east4/indexEndpoints/789"

    doReturn(completed[RagCorpus, CreateRagCorpusOperationMetadata](created, opName))
      .when(data)
      .createRagCorpusAsync(any(classOf[CreateRagCorpusRequest]))

    for
      started <- client.createCorpus(
                   CorpusConfig(
                     displayName = "docs",
                     vectorDb = VectorDb.VertexVectorSearch(index = index, indexEndpoint = endpoint)
                   )
                 )
      _       <- started.await
    yield
      val captor   = ArgumentCaptor.forClass(classOf[CreateRagCorpusRequest])
      verify(data).createRagCorpusAsync(captor.capture())
      val vectorDb = captor.getValue.getRagCorpus.getVectorDbConfig
      assert(vectorDb.hasVertexVectorSearch)
      assertEquals(vectorDb.getVertexVectorSearch.getIndex, index)
      assertEquals(vectorDb.getVertexVectorSearch.getIndexEndpoint, endpoint)
  }

  test("getFile and listFiles map RagFile protos") {
    val data   = mockDataClient()
    val rag    = mockRagClient()
    val client = RagClient.create[IO](config, data, rag)
    val file   = RagFile.newBuilder().setName(fileName).setDisplayName("f1.txt").setDescription("note").build()
    val page   = mock(classOf[VertexRagDataServiceClient.ListRagFilesPagedResponse])

    when(data.getRagFile(any(classOf[GetRagFileRequest]))).thenReturn(file)
    when(data.listRagFiles(any(classOf[ListRagFilesRequest]))).thenReturn(page)
    when(page.iterateAll()).thenReturn(java.util.List.of(file))

    for
      got  <- client.getFile(fileName)
      list <- client.listFiles(corpusName)
    yield
      assertEquals(got, RagFileInfo(fileName, "f1.txt", Some("note")))
      assertEquals(list, List(RagFileInfo(fileName, "f1.txt", Some("note"))))
  }

  test("getFile carries per-file state and error status") {
    val data   = mockDataClient()
    val rag    = mockRagClient()
    val client = RagClient.create[IO](config, data, rag)
    val status = FileStatus.newBuilder().setState(FileStatus.State.ERROR).setErrorStatus("unsupported pdf").build()
    val file   = RagFile.newBuilder().setName(fileName).setDisplayName("corrupt.pdf").setFileStatus(status).build()

    when(data.getRagFile(any(classOf[GetRagFileRequest]))).thenReturn(file)

    client.getFile(fileName).map { got =>
      assertEquals(got, RagFileInfo(fileName, "corrupt.pdf", None, FileState.Failed, Some("unsupported pdf")))
    }
  }

  test("importFiles awaits LRO then lists files") {
    val data   = mockDataClient()
    val rag    = mockRagClient()
    val client = RagClient.create[IO](config, data, rag)
    val file   = RagFile.newBuilder().setName(fileName).setDisplayName("f1.txt").build()
    val page   = mock(classOf[VertexRagDataServiceClient.ListRagFilesPagedResponse])

    doReturn(completed[ImportRagFilesResponse, ImportRagFilesOperationMetadata](ImportRagFilesResponse.getDefaultInstance, opName))
      .when(data)
      .importRagFilesAsync(any(classOf[ImportRagFilesRequest]))
    when(data.listRagFiles(any(classOf[ListRagFilesRequest]))).thenReturn(page)
    when(page.iterateAll()).thenReturn(java.util.List.of(file))

    for
      started  <- client.importFiles(
                    corpusName,
                    GcsImportSource(NEL.one("gs://bucket/doc.txt"), Some(ChunkingConfig(256, 32)), defaultSink)
                  )
      _        <- IO(assertEquals(started.handle, LroHandle(opName, LroKind.ImportFiles)))
      imported <- started.await
    yield
      assertEquals(
        imported,
        ImportResult(
          importedCount = 0,
          failedCount = 0,
          skippedCount = 0,
          files = List(RagFileInfo(fileName, "f1.txt", None)),
          partialFailuresGcsPath = Some(sinkPath)
        )
      )
      val captor  = ArgumentCaptor.forClass(classOf[ImportRagFilesRequest])
      verify(data).importRagFilesAsync(captor.capture())
      val request = captor.getValue
      assertEquals(request.getParent, corpusName)
      assertEquals(request.getImportRagFilesConfig.getGcsSource.getUrisList.toArray.toList, List("gs://bucket/doc.txt"))
      assertEquals(
        request.getImportRagFilesConfig.getRagFileTransformationConfig.getRagFileChunkingConfig.getFixedLengthChunking.getChunkSize,
        256
      )
  }

  test("importFiles configures the import result sink") {
    val data   = mockDataClient()
    val rag    = mockRagClient()
    val client = RagClient.create[IO](config, data, rag)
    val page   = mock(classOf[VertexRagDataServiceClient.ListRagFilesPagedResponse])

    doReturn(completed[ImportRagFilesResponse, ImportRagFilesOperationMetadata](ImportRagFilesResponse.getDefaultInstance, opName))
      .when(data)
      .importRagFilesAsync(any(classOf[ImportRagFilesRequest]))
    when(data.listRagFiles(any(classOf[ListRagFilesRequest]))).thenReturn(page)
    when(page.iterateAll()).thenReturn(java.util.List.of())

    val source = GcsImportSource(NEL.one("gs://bucket/doc.txt"), resultSink = ImportResultSink.Gcs("gs://bucket/results/"))

    for
      started <- client.importFiles(corpusName, source)
      _       <- started.await
    yield
      val captor = ArgumentCaptor.forClass(classOf[ImportRagFilesRequest])
      verify(data).importRagFilesAsync(captor.capture())
      assertEquals(captor.getValue.getImportRagFilesConfig.getImportResultGcsSink.getOutputUriPrefix, "gs://bucket/results/")
  }

  test("createDataSchema builds CreateRagDataSchemaRequest") {
    val data   = mockDataClient()
    val rag    = mockRagClient()
    val client = RagClient.create[IO](config, data, rag)

    when(data.createRagDataSchema(any(classOf[CreateRagDataSchemaRequest])))
      .thenReturn(RagDataSchema.getDefaultInstance)

    client.createDataSchema(corpusName, DataSchema("tenantid")).map { _ =>
      val captor  = ArgumentCaptor.forClass(classOf[CreateRagDataSchemaRequest])
      verify(data).createRagDataSchema(captor.capture())
      val request = captor.getValue
      assertEquals(request.getParent, corpusName)
      assertEquals(request.getRagDataSchemaId, "tenantid")
      assertEquals(request.getRagDataSchema.getKey, "tenantid")
      assertEquals(request.getRagDataSchema.getSchemaDetails.getType, RagMetadataSchemaDetails.DataType.STRING)
      assertEquals(
        request.getRagDataSchema.getSchemaDetails.getSearchStrategy.getSearchStrategyType,
        RagMetadataSchemaDetails.SearchStrategy.SearchStrategyType.EXACT_SEARCH
      )
    }
  }

  test("setFileMetadata creates RagMetadata entries") {
    val data   = mockDataClient()
    val rag    = mockRagClient()
    val client = RagClient.create[IO](config, data, rag)

    when(data.createRagMetadata(any(classOf[CreateRagMetadataRequest])))
      .thenReturn(RagMetadata.getDefaultInstance)

    client
      .setFileMetadata(fileName, NEL.one(MetadataEntry("tenantid", MetadataValue.Str("user-42"))))
      .map { _ =>
        val captor  = ArgumentCaptor.forClass(classOf[CreateRagMetadataRequest])
        verify(data).createRagMetadata(captor.capture())
        val request = captor.getValue
        assertEquals(request.getParent, fileName)
        assertEquals(request.getRagMetadataId, "tenantid")
        assertEquals(request.getRagMetadata.getUserSpecifiedMetadata.getKey, "tenantid")
        assertEquals(request.getRagMetadata.getUserSpecifiedMetadata.getValue.getStrValue, "user-42")
      }
  }

  test("importFiles attaches metadata only to OK sink rows with fileId") {
    val data     = mockDataClient()
    val rag      = mockRagClient()
    val fileId   = 42L
    val expected = ImportResultLedger.ragFileName(corpusName, fileId)
    val page     = mock(classOf[VertexRagDataServiceClient.ListRagFilesPagedResponse])
    val ndjson   =
      s"""{"OperationId":1,"CreateTimestamp":"t","Filename":"doc.txt","Status":"OK","FileId":$fileId}
         |{"OperationId":2,"CreateTimestamp":"t","Filename":"skip.txt","Status":"SKIPPED"}
         |{"OperationId":3,"CreateTimestamp":"t","Filename":"other.txt","Status":"OK"}
         |""".stripMargin
    val client   = RagClient.create[IO](config, data, rag, ImportResultLedger.core(_ => IO.pure(ndjson)))

    doReturn(completed[ImportRagFilesResponse, ImportRagFilesOperationMetadata](ImportRagFilesResponse.getDefaultInstance, opName))
      .when(data)
      .importRagFilesAsync(any(classOf[ImportRagFilesRequest]))
    when(data.listRagFiles(any(classOf[ListRagFilesRequest]))).thenReturn(page)
    when(page.iterateAll()).thenReturn(java.util.List.of())
    when(data.createRagDataSchema(any(classOf[CreateRagDataSchemaRequest]))).thenReturn(RagDataSchema.getDefaultInstance)
    when(data.createRagMetadata(any(classOf[CreateRagMetadataRequest]))).thenReturn(RagMetadata.getDefaultInstance)

    val source = GcsImportSource(
      uris = NEL.one("gs://bucket/docs/*.txt"),
      resultSink = defaultSink,
      metadata = Some(
        ImportFileMetadata(
          schemas = List(DataSchema("tenantid")),
          entries = Some(NEL.one(MetadataEntry("tenantid", MetadataValue.Str("user-42"))))
        )
      )
    )

    for
      started <- client.importFiles(corpusName, source)
      _       <- started.await
    yield
      verify(data).createRagDataSchema(any(classOf[CreateRagDataSchemaRequest]))
      val metaCaptor = ArgumentCaptor.forClass(classOf[CreateRagMetadataRequest])
      verify(data, times(1)).createRagMetadata(metaCaptor.capture())
      assertEquals(metaCaptor.getValue.getParent, expected)
      assertEquals(metaCaptor.getValue.getRagMetadataId, "tenantid")
      assertEquals(metaCaptor.getValue.getRagMetadata.getUserSpecifiedMetadata.getValue.getStrValue, "user-42")
  }

  test("importFiles rejects metadata.entries with a BigQuery result sink") {
    val data   = mockDataClient()
    val rag    = mockRagClient()
    val client = RagClient.create[IO](config, data, rag)
    val source = GcsImportSource(
      uris = NEL.one("gs://bucket/doc.txt"),
      resultSink = ImportResultSink.BigQuery("bq://p.d.t"),
      metadata = Some(ImportFileMetadata(entries = Some(NEL.one(MetadataEntry("tenantid", MetadataValue.Str("a"))))))
    )

    client.importFiles(corpusName, source).attempt.map { err =>
      assertEquals(
        err,
        Left(RagClient.Error.InvalidConfig("metadata.entries requires ImportResultSink.Gcs so imported fileIds can be read from the sink"))
      )
      verify(data, times(0)).importRagFilesAsync(any(classOf[ImportRagFilesRequest]))
    }
  }

  test("importFiles reports the corpus and uris when the LRO itself fails") {
    val data   = mockDataClient()
    val rag    = mockRagClient()
    val client = RagClient.create[IO](config, data, rag)
    val uris   = NEL.of("gs://bucket/doc.txt", "gs://bucket/corrupt.pdf")

    doReturn(failed[ImportRagFilesResponse, ImportRagFilesOperationMetadata](RuntimeException("internal error"), opName))
      .when(data)
      .importRagFilesAsync(any(classOf[ImportRagFilesRequest]))

    for
      started <- client.importFiles(corpusName, GcsImportSource(uris, resultSink = defaultSink))
      result  <- started.await.attempt
    yield result match
      case Left(RagClient.Error.ImportOperationFailed(corpus, failedUris, lro, cause)) =>
        assertEquals(corpus, corpusName)
        assertEquals(failedUris, uris)
        assertEquals(lro, opName)
        assertEquals(cause.getMessage, "internal error")
      case other                                                                       => fail(s"expected ImportOperationFailed, got $other")
  }

  test("deleteFile and deleteCorpus await LROs") {
    val data   = mockDataClient()
    val rag    = mockRagClient()
    val client = RagClient.create[IO](config, data, rag)

    doReturn(completed[Empty, DeleteOperationMetadata](Empty.getDefaultInstance, opName))
      .when(data)
      .deleteRagFileAsync(any(classOf[DeleteRagFileRequest]))
    doReturn(completed[Empty, DeleteOperationMetadata](Empty.getDefaultInstance, s"$opName-corpus"))
      .when(data)
      .deleteRagCorpusAsync(any(classOf[DeleteRagCorpusRequest]))

    for
      deleteFile   <- client.deleteFile(fileName)
      _            <- IO(assertEquals(deleteFile.handle.kind, LroKind.DeleteFile))
      _            <- deleteFile.await
      deleteCorpus <- client.deleteCorpus(corpusName)
      _            <- IO(assertEquals(deleteCorpus.handle.kind, LroKind.DeleteCorpus))
      _            <- deleteCorpus.await
    yield
      verify(data).deleteRagFileAsync(any(classOf[DeleteRagFileRequest]))
      verify(data).deleteRagCorpusAsync(any(classOf[DeleteRagCorpusRequest]))
  }

  test("updateCorpus loads existing corpus then updates") {
    val data     = mockDataClient()
    val rag      = mockRagClient()
    val client   = RagClient.create[IO](config, data, rag)
    val existing = RagCorpus.newBuilder().setName(corpusName).setDisplayName("old").setDescription("d").build()
    val updated  = existing.toBuilder.setDisplayName("new").build()

    when(data.getRagCorpus(eqTo(corpusName))).thenReturn(existing)
    doReturn(completed[RagCorpus, UpdateRagCorpusOperationMetadata](updated, opName))
      .when(data)
      .updateRagCorpusAsync(any(classOf[GUpdateRagCorpusRequest]))

    for
      started <- client.updateCorpus(UpdateCorpusRequest(name = corpusName, displayName = Some("new")))
      _       <- IO(assertEquals(started.handle, LroHandle(opName, LroKind.UpdateCorpus)))
      corpus  <- started.await
    yield
      assertEquals(corpus.displayName, "new")
      val captor = ArgumentCaptor.forClass(classOf[GUpdateRagCorpusRequest])
      verify(data).updateRagCorpusAsync(captor.capture())
      assertEquals(captor.getValue.getRagCorpus.getDisplayName, "new")
  }

  test("listCorpora maps paged corpora") {
    val data   = mockDataClient()
    val rag    = mockRagClient()
    val client = RagClient.create[IO](config, data, rag)
    val page   = mock(classOf[VertexRagDataServiceClient.ListRagCorporaPagedResponse])
    val corpus = RagCorpus.newBuilder().setName(corpusName).setDisplayName("docs").build()

    when(data.listRagCorpora(any(classOf[ListRagCorporaRequest]))).thenReturn(page)
    when(page.iterateAll()).thenReturn(java.util.List.of(corpus))

    client.listCorpora.map { result =>
      assertEquals(result, List(Corpus(corpusName, "docs", None)))
      val captor = ArgumentCaptor.forClass(classOf[ListRagCorporaRequest])
      verify(data).listRagCorpora(captor.capture())
      assertEquals(captor.getValue.getParent, config.parent)
    }
  }

  test("retrieveContexts builds store query and maps contexts") {
    val data     = mockDataClient()
    val rag      = mockRagClient()
    val client   = RagClient.create[IO](config, data, rag)
    val context  = RagContexts.Context
      .newBuilder()
      .setText("chunk")
      .setSourceUri("gs://b/a.txt")
      .setSourceDisplayName("a.txt")
      .setScore(0.42)
      .build()
    val response = RetrieveContextsResponse
      .newBuilder()
      .setContexts(RagContexts.newBuilder().addContexts(context).build())
      .build()

    when(rag.retrieveContexts(any(classOf[RetrieveContextsRequest]))).thenReturn(response)

    for contexts <- client.retrieveContexts(corpusName, "what is rag", RetrievalConfig(topK = Some(3), vectorDistanceThreshold = Some(0.5)))
    yield
      assertEquals(
        contexts,
        List(RetrievedContext("chunk", Some("gs://b/a.txt"), Some("a.txt"), Some(0.42)))
      )
      val captor  = ArgumentCaptor.forClass(classOf[RetrieveContextsRequest])
      verify(rag).retrieveContexts(captor.capture())
      val request = captor.getValue
      assertEquals(request.getParent, config.parent)
      assertEquals(request.getQuery.getText, "what is rag")
      assertEquals(request.getQuery.getRagRetrievalConfig.getTopK, 3)
      assertEquals(request.getQuery.getRagRetrievalConfig.getFilter.getVectorDistanceThreshold, 0.5)
      assertEquals(request.getVertexRagStore.getRagResources(0).getRagCorpus, corpusName)
  }

  test("retrieveContexts applies metadata filter and rag file ids") {
    val data      = mockDataClient()
    val rag       = mockRagClient()
    val client    = RagClient.create[IO](config, data, rag)
    val response  = RetrieveContextsResponse
      .newBuilder()
      .setContexts(RagContexts.newBuilder().build())
      .build()
    val retrieval = RetrievalConfig(
      metadataFilter = Some("""tenantid == "user-42""""),
      ragFileIds = List("file-1", "file-2")
    )

    when(rag.retrieveContexts(any(classOf[RetrieveContextsRequest]))).thenReturn(response)

    for _ <- client.retrieveContexts(corpusName, "scoped", retrieval)
    yield
      val captor   = ArgumentCaptor.forClass(classOf[RetrieveContextsRequest])
      verify(rag).retrieveContexts(captor.capture())
      val request  = captor.getValue
      val filter   = request.getQuery.getRagRetrievalConfig.getFilter
      val resource = request.getVertexRagStore.getRagResources(0)
      assertEquals(filter.getMetadataFilter, """tenantid == "user-42"""")
      assertEquals(resource.getRagCorpus, corpusName)
      assertEquals(resource.getRagFileIdsList.asScala.toList, List("file-1", "file-2"))
  }

  test("getLro maps running succeeded and failed operations") {
    val data      = mockDataClient()
    val rag       = mockRagClient()
    val ops       = mock(classOf[OperationsClient])
    val client    = RagClient.create[IO](config, data, rag)
    val handle    = LroHandle(opName, LroKind.ImportFiles)
    val running   = Operation.newBuilder().setName(opName).setDone(false).build()
    val succeeded = Operation.newBuilder().setName(opName).setDone(true).build()
    val failed    = Operation
      .newBuilder()
      .setName(opName)
      .setDone(true)
      .setError(Status.newBuilder().setMessage("import blew up").build())
      .build()

    when(data.getOperationsClient).thenReturn(ops)
    when(ops.getOperation(opName)).thenReturn(running, succeeded, failed)

    for
      status1 <- client.getLro(handle)
      status2 <- client.getLro(handle)
      status3 <- client.getLro(handle)
    yield
      assertEquals(status1, LroStatus.Running)
      assertEquals(status2, LroStatus.Succeeded)
      assertEquals(status3, LroStatus.Failed("import blew up"))
  }

  test("getImportResult resumes a completed import LRO") {
    val data     = mockDataClient()
    val rag      = mockRagClient()
    val ops      = mock(classOf[OperationsClient])
    val client   = RagClient.create[IO](config, data, rag)
    val handle   = LroHandle(opName, LroKind.ImportFiles)
    val file     = RagFile.newBuilder().setName(fileName).setDisplayName("f1.txt").build()
    val page     = mock(classOf[VertexRagDataServiceClient.ListRagFilesPagedResponse])
    val response = ImportRagFilesResponse
      .newBuilder()
      .setImportedRagFilesCount(1)
      .setFailedRagFilesCount(0)
      .setSkippedRagFilesCount(0)
      .setPartialFailuresGcsPath(sinkPath)
      .build()
    val done     = Operation.newBuilder().setName(opName).setDone(true).setResponse(ProtoAny.pack(response)).build()

    when(data.getOperationsClient).thenReturn(ops)
    when(ops.getOperation(opName)).thenReturn(done)
    when(data.listRagFiles(any(classOf[ListRagFilesRequest]))).thenReturn(page)
    when(page.iterateAll()).thenReturn(java.util.List.of(file))

    client.getImportResult(handle, corpusName).map { result =>
      assertEquals(
        result,
        ImportResult(
          importedCount = 1,
          failedCount = 0,
          skippedCount = 0,
          files = List(RagFileInfo(fileName, "f1.txt", None)),
          partialFailuresGcsPath = Some(sinkPath)
        )
      )
    }
  }

  test("getImportResult raises LroRunning while the operation is in progress") {
    val data    = mockDataClient()
    val rag     = mockRagClient()
    val ops     = mock(classOf[OperationsClient])
    val client  = RagClient.create[IO](config, data, rag)
    val handle  = LroHandle(opName, LroKind.ImportFiles)
    val running = Operation.newBuilder().setName(opName).setDone(false).build()

    when(data.getOperationsClient).thenReturn(ops)
    when(ops.getOperation(opName)).thenReturn(running)

    client.getImportResult(handle, corpusName).attempt.map(assertEquals(_, Left(RagClient.Error.LroRunning)))
  }

  test("getImportResult raises LroFailed when the operation itself failed") {
    val data   = mockDataClient()
    val rag    = mockRagClient()
    val ops    = mock(classOf[OperationsClient])
    val client = RagClient.create[IO](config, data, rag)
    val handle = LroHandle(opName, LroKind.ImportFiles)
    val failed = Operation
      .newBuilder()
      .setName(opName)
      .setDone(true)
      .setError(Status.newBuilder().setMessage("import blew up").build())
      .build()

    when(data.getOperationsClient).thenReturn(ops)
    when(ops.getOperation(opName)).thenReturn(failed)

    client.getImportResult(handle, corpusName).attempt.map(assertEquals(_, Left(RagClient.Error.LroFailed("import blew up"))))
  }

  test("getImportResult rejects non-import LRO kinds") {
    val data   = mockDataClient()
    val rag    = mockRagClient()
    val client = RagClient.create[IO](config, data, rag)
    val handle = LroHandle(opName, LroKind.CreateCorpus)

    client
      .getImportResult(handle, corpusName)
      .attempt
      .map(assertEquals(_, Left(RagClient.Error.InvalidConfig("getImportResult requires LroKind.ImportFiles"))))
  }

  test("getImportResult raises ImportFailed when the response reports failed files") {
    val data     = mockDataClient()
    val rag      = mockRagClient()
    val ops      = mock(classOf[OperationsClient])
    val client   = RagClient.create[IO](config, data, rag)
    val handle   = LroHandle(opName, LroKind.ImportFiles)
    val response = ImportRagFilesResponse
      .newBuilder()
      .setImportedRagFilesCount(1)
      .setFailedRagFilesCount(1)
      .setSkippedRagFilesCount(0)
      .setPartialFailuresGcsPath(sinkPath)
      .build()
    val done     = Operation.newBuilder().setName(opName).setDone(true).setResponse(ProtoAny.pack(response)).build()

    when(data.getOperationsClient).thenReturn(ops)
    when(ops.getOperation(opName)).thenReturn(done)

    client
      .getImportResult(handle, corpusName)
      .attempt
      .map(
        assertEquals(
          _,
          Left(
            RagClient.Error.ImportFailed(
              importedCount = 1,
              failedCount = 1,
              skippedCount = 0,
              partialFailuresGcsPath = Some(sinkPath),
              partialFailuresBigQueryTable = None
            )
          )
        )
      )
  }

  test("getImportResult falls back to sinkGcs when the response omits the path") {
    val data     = mockDataClient()
    val rag      = mockRagClient()
    val ops      = mock(classOf[OperationsClient])
    val client   = RagClient.create[IO](config, data, rag)
    val handle   = LroHandle(opName, LroKind.ImportFiles)
    val file     = RagFile.newBuilder().setName(fileName).setDisplayName("f1.txt").build()
    val page     = mock(classOf[VertexRagDataServiceClient.ListRagFilesPagedResponse])
    val response = ImportRagFilesResponse
      .newBuilder()
      .setImportedRagFilesCount(1)
      .setFailedRagFilesCount(0)
      .setSkippedRagFilesCount(0)
      .build()
    val done     = Operation.newBuilder().setName(opName).setDone(true).setResponse(ProtoAny.pack(response)).build()

    when(data.getOperationsClient).thenReturn(ops)
    when(ops.getOperation(opName)).thenReturn(done)
    when(data.listRagFiles(any(classOf[ListRagFilesRequest]))).thenReturn(page)
    when(page.iterateAll()).thenReturn(java.util.List.of(file))

    client.getImportResult(handle, corpusName, sinkGcs = Some(sinkPath)).map { result =>
      assertEquals(result.partialFailuresGcsPath, Some(sinkPath))
    }
  }

  test("getImportResult falls back to sinkBq when the response omits the path") {
    val data     = mockDataClient()
    val rag      = mockRagClient()
    val ops      = mock(classOf[OperationsClient])
    val client   = RagClient.create[IO](config, data, rag)
    val handle   = LroHandle(opName, LroKind.ImportFiles)
    val bqPath   = "bq://project.dataset.table"
    val file     = RagFile.newBuilder().setName(fileName).setDisplayName("f1.txt").build()
    val page     = mock(classOf[VertexRagDataServiceClient.ListRagFilesPagedResponse])
    val response = ImportRagFilesResponse
      .newBuilder()
      .setImportedRagFilesCount(1)
      .setFailedRagFilesCount(0)
      .setSkippedRagFilesCount(0)
      .build()
    val done     = Operation.newBuilder().setName(opName).setDone(true).setResponse(ProtoAny.pack(response)).build()

    when(data.getOperationsClient).thenReturn(ops)
    when(ops.getOperation(opName)).thenReturn(done)
    when(data.listRagFiles(any(classOf[ListRagFilesRequest]))).thenReturn(page)
    when(page.iterateAll()).thenReturn(java.util.List.of(file))

    client.getImportResult(handle, corpusName, sinkBq = Some(bqPath)).map { result =>
      assertEquals(result.partialFailuresBigQueryTable, Some(bqPath))
    }
  }

  private def mockDataClient(): VertexRagDataServiceClient =
    mock(classOf[VertexRagDataServiceClient])

  private def mockRagClient(): VertexRagServiceClient =
    mock(classOf[VertexRagServiceClient])

  private def failed[A, M](error: Throwable, name: String): OperationFuture[A, M] =
    val future = mock(classOf[OperationFuture[?, ?]]).asInstanceOf[OperationFuture[A, M]]
    when(future.isDone).thenReturn(true)
    when(future.getName).thenReturn(name)
    when(future.get()).thenThrow(ExecutionException(error))
    doAnswer((invocation: InvocationOnMock) =>
      invocation.getArgument(0, classOf[Runnable]).run()
      null
    ).when(future).addListener(any(classOf[Runnable]), any(classOf[Executor]))
    future

  private def completed[A, M](value: A, name: String): OperationFuture[A, M] =
    val future = mock(classOf[OperationFuture[?, ?]]).asInstanceOf[OperationFuture[A, M]]
    when(future.isDone).thenReturn(true)
    when(future.get()).thenReturn(value)
    when(future.getName).thenReturn(name)
    when(future.get(anyLong(), any(classOf[TimeUnit]))).thenReturn(value)
    doAnswer((invocation: InvocationOnMock) =>
      invocation.getArgument(0, classOf[Runnable]).run()
      null
    ).when(future).addListener(any(classOf[Runnable]), any(classOf[Executor]))
    future

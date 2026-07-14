package io.github.serhiip.constellations.gcprag

enum VectorDb:
  case RagManaged
  case VertexVectorSearch(index: String, indexEndpoint: String)

final case class CorpusConfig(
    displayName: String,
    description: Option[String] = None,
    embeddingModelEndpoint: String = "publishers/google/models/text-embedding-005",
    vectorDb: VectorDb = VectorDb.RagManaged
)
final case class Corpus(name: String, displayName: String, description: Option[String])
final case class RagFileInfo(name: String, displayName: String, description: Option[String])
final case class ChunkingConfig(chunkSize: Int = 512, chunkOverlap: Int = 100)
final case class GcsImportSource(uris: List[String], chunking: Option[ChunkingConfig] = None)
final case class ImportResult(
    importedCount: Long,
    failedCount: Long,
    skippedCount: Long,
    files: List[RagFileInfo],
    partialFailuresGcsPath: Option[String] = None,
    partialFailuresBigQueryTable: Option[String] = None
)
final case class RetrievalConfig(topK: Option[Int] = None, vectorDistanceThreshold: Option[Double] = None)
final case class RetrievedContext(text: String, sourceUri: Option[String], sourceDisplayName: Option[String], score: Option[Double])
final case class UpdateCorpusRequest(name: String, displayName: Option[String] = None, description: Option[String] = None)

enum LroKind:
  case CreateCorpus, UpdateCorpus, DeleteCorpus, ImportFiles, DeleteFile

final case class LroHandle(name: String, kind: LroKind)

final case class StartedLro[F[_], A](handle: LroHandle, await: F[A])

enum LroStatus:
  case Running
  case Succeeded
  case Failed(message: String)

---
sidebar_position: 3
---

# GCP RAG Engine

`constellations-gcp-rag-engine` integrates Google Cloud Vertex AI RAG Engine for corpus/file lifecycle management and text similarity search via `TextSimilarity`.

## Setup

```scala
libraryDependencies += "io.github.serhiip" %% "constellations-gcp-rag-engine" % "@VERSION@"
```

Use Application Default Credentials and grant `roles/aiplatform.user`.

## Corpus and file management

Long-running operations (`createCorpus`, `updateCorpus`, `deleteCorpus`, `importFiles`, `deleteFile`) return `StartedLro[F, A]`: a persistable `LroHandle` plus an `await` effect that completes when Vertex finishes. Poll status later with `getLro`.

```scala
import cats.effect.IO
import io.github.serhiip.constellations.gcprag.*

RagClient.resource[IO](RagClient.Config(project = "my-project", location = "us-east4")).use { rag =>
  for
    create   <- rag.createCorpus(CorpusConfig(displayName = "docs")) // VectorDb.RagManaged by default
    // persist create.handle.name to resume status checks across restarts (kind is optional app metadata)
    corpus   <- create.await
    importOp <- rag.importFiles(
                  corpus.name,
                  GcsImportSource(uris = List("gs://my-bucket/docs/*.txt"), chunking = Some(ChunkingConfig()))
                )
    status   <- rag.getLro(importOp.handle) // uses handle.name; Running | Succeeded | Failed(message)
    imported <- importOp.await
    files    <- rag.listFiles(corpus.name)
  yield (corpus, status, imported.importedCount, imported.files, files)
}
```

To use an existing Vertex AI Vector Search index (must already exist and be deployed to an endpoint):

```scala
rag.createCorpus(
  CorpusConfig(
    displayName = "docs",
    vectorDb = VectorDb.VertexVectorSearch(
      index = "projects/123/locations/us-east4/indexes/456",
      indexEndpoint = "projects/123/locations/us-east4/indexEndpoints/789"
    )
  )
)
```

`importFiles` await yields an `ImportResult` with Vertex counts (`importedCount`, `failedCount`, `skippedCount`) plus the post-import corpus file list. If `failedCount > 0`, it raises `RagClient.Error.ImportFailed`.

## Similarity search

RAG Engine accepts text queries (not raw embeddings). Use `RagEngine.similarity` for a plain `TextSimilarity`:

```scala
given ContextDecoder[IO, String] = ContextDecoder(ctx => IO.pure(ctx.text))
val sim = RagEngine.similarity[IO, String](rag, corpus.name, RetrievalConfig())
sim.findClosest("What is RAG?", k = 3)
```

## Observability

Wrap the client after construction (requires `Tracer`, `LoggerFactory`, and `Meter`). Compose with `Similarity.observed` for traced/metered search:

```scala
import io.github.serhiip.constellations.Similarity

RagClient.resource[IO](config).evalMap(RagClient.apply[IO]).use { rag =>
  for
    sim  <- Similarity.observed(RagEngine.similarity[IO, String](rag, corpus.name, RetrievalConfig()))
    hits <- sim.findClosest("What is RAG?", k = 3)
  yield hits
}
```

- Spans: `constellations-rag-client-*` (including `*-start` / `*-await` for LROs, and `get-lro`), `constellations-similarity-find-closest`
- Metrics: `constellations/rag_client_operation_count`, `constellations/rag_client_error_count`, `constellations/rag_client_operation_duration`, `constellations/rag_client_operation_start_duration`, `constellations/rag_client_imported_files_count`, `constellations/rag_client_failed_import_files_count`, `constellations/similarity_find_closest_success_count`, `constellations/similarity_find_closest_error_count`, `constellations/similarity_find_closest_duration`

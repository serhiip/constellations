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

Long-running operations (`createCorpus`, `updateCorpus`, `deleteCorpus`, `importFiles`, `deleteFile`) return `StartedLro[F, A]`: a persistable `LroHandle` plus an `await` effect that completes when Vertex finishes. Poll status later with `getLro`. For an import-files LRO, resume the typed result across restarts with `getImportResult(handle, corpusName, sinkGcs = …, sinkBq = …)` — it returns `ImportResult` when done, or raises `RagClient.Error.LroRunning` / `LroFailed` while in progress or when the operation itself failed. Pass the original result-sink path as `sinkGcs` / `sinkBq` when the LRO response omits it.

```scala
import cats.data.NonEmptyList as NEL
import cats.effect.IO
import io.github.serhiip.constellations.gcprag.*

RagClient.resource[IO](RagClient.Config(project = "my-project", location = "us-east4")).use { rag =>
  for
    create   <- rag.createCorpus(CorpusConfig(displayName = "docs")) // VectorDb.RagManaged(Knn) by default
    // persist create.handle.name to resume status checks across restarts (kind is optional app metadata)
    corpus   <- create.await
    importOp <- rag.importFiles(
                  corpus.name,
                  GcsImportSource(
                    uris = NEL.one("gs://my-bucket/docs/*.txt"),
                    chunking = Some(ChunkingConfig()),
                    resultSink = ImportResultSink.Gcs("gs://my-bucket/import-results/run-1.ndjson"),
                    // optional: schemas + stamp entries on OK sink rows that carry a FileId
                    metadata = Some(
                      ImportFileMetadata(
                        schemas = List(DataSchema("tenantid")),
                        entries = Some(NEL.one(MetadataEntry("tenantid", MetadataValue.Str("user-42"))))
                      )
                    )
                  )
                )
    status   <- rag.getLro(importOp.handle) // uses handle.name; Running | Succeeded | Failed(message)
    imported <- importOp.await
    // after a restart: imported <- rag.getImportResult(importOp.handle, corpus.name, sinkGcs = Some("gs://…/run-1.ndjson"))
    files    <- rag.listFiles(corpus.name)
  yield (corpus, status, imported.importedCount, imported.files, files)
}
```

`VectorDb.RagManaged` takes a `RagManagedRetrieval` oneof — exact `Knn` (default) or approximate `Ann(treeDepth, leafCount)` for larger corpora:

```scala
rag.createCorpus(
  CorpusConfig(
    displayName = "docs",
    vectorDb = VectorDb.RagManaged(RagManagedRetrieval.Ann(treeDepth = Some(2), leafCount = Some(500)))
  )
)
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

`importFiles` await yields an `ImportResult` with Vertex counts (`importedCount`, `failedCount`, `skippedCount`) plus the post-import corpus file list. Imports fail in two different ways:

- The operation completes but some files could not be ingested (`failedCount > 0`), which raises `RagClient.Error.ImportFailed` carrying the counts.
- The operation itself fails, which raises `RagClient.Error.ImportOperationFailed` carrying the corpus, the requested URIs and the LRO name. Vertex often reports these as a bare `INTERNAL`, so a single unreadable file can take down the whole batch without naming itself.

Two things help narrow down the second case. `GcsImportSource.resultSink` is required — Vertex writes per-file outcomes to that GCS or BigQuery destination (each GCS path must not already exist) — and read `RagFileInfo.state` / `RagFileInfo.errorStatus` from `listFiles`, which report `FileState.Failed` plus Vertex's reason per file.

To surface those sink rows without losing partial success, pass the import LRO to `ErrorReporter.report`, which awaits it and returns `Ior[NonEmptyList[ImportFileOutcome], ImportResult]` (use `lro.handle` separately if you still need to poll):

```scala
import cats.data.Ior

for
  reporter <- ErrorReporter[IO]() // or ErrorReporter.gcs / ErrorReporter.core(ledger)
  lro      <- rag.importFiles(corpus.name, source)
  report   <- reporter.report(lro)
yield report match
  case Ior.Right(result)          => // all files imported
  case Ior.Both(failures, result) => // partial: result has counts; failures are non-OK sink rows
  case Ior.Left(failures)         => // LRO completed but nothing imported
```

On `RagClient.Error.ImportFailed` with a GCS sink path, `report` reads `partialFailuresGcsPath` and builds the `Ior` from the NDJSON ledger. Full success stays `Ior.Right`. Hard LRO failures (`ImportOperationFailed`) and decode problems still raise.

## File metadata (multi-tenant filters)

Vertex exposes searchable file metadata on the **`v1beta1` data API** (`CreateRagDataSchema` / `CreateRagMetadata`). This client uses that path for ingestion while retrieval stays on `v1` (`metadata_filter`).

1. Define corpus keys (`DataSchema`; keys must match `[a-z][a-z0-9-]{0,62}` — no underscores).
2. Attach values per RagFile (`MetadataEntry`), either via `GcsImportSource.metadata.entries` or `setFileMetadata` on an explicit file name.
3. Query with `RetrievalConfig.metadataFilter` (CEL), using those same keys (e.g. `tenantid == "user-42"`).

```scala
import cats.data.NonEmptyList as NEL

// schemas can also be created ahead of import
_ <- rag.createDataSchema(corpus.name, DataSchema("tenantid"))

_ <- rag.importFiles(
       corpus.name,
       GcsImportSource(
         uris = NEL.one("gs://my-bucket/tenants/user-42/docs/*.txt"),
         resultSink = ImportResultSink.Gcs("gs://my-bucket/import-results/user-42-run.ndjson"),
         metadata = Some(
           ImportFileMetadata(
             schemas = List(DataSchema("tenantid")), // idempotent if already created
             entries = Some(NEL.one(MetadataEntry("tenantid", MetadataValue.Str("user-42"))))
           )
         )
       )
     ).flatMap(_.await)

// or later, for an existing file:
_ <- rag.setFileMetadata(
       fileName,
       NEL.one(MetadataEntry("tenantid", MetadataValue.Str("user-42")))
     )
```

When `entries` are set, `resultSink` must be `ImportResultSink.Gcs`. After the LRO succeeds, the client reads that NDJSON ledger and calls `setFileMetadata` only for rows with `Status=OK` and a `FileId`, using the resource name `{corpus}/ragFiles/{fileId}`. That set is scoped to this import operation — not a corpus-wide “new since snapshot” diff. Schema creation treats `ALREADY_EXISTS` as success. Skipped/reimport rows without a usable `FileId` (or non-OK status) are not stamped.

## Similarity search

RAG Engine accepts text queries (not raw embeddings). Use `RagEngine.Similarity.simple` for a plain `TextSimilarity` with a fixed corpus and `RetrievalConfig`. For ambient config (e.g. Kleisli/`Ask`), use `RagEngine.Similarity.fromAsk` instead. Scoping filters live on `RetrievalConfig` and are fixed when the instance is created — `findClosest` itself is unchanged:

```scala
given ContextDecoder[IO, String] = ContextDecoder(ctx => IO.pure(ctx.text))
val sim = RagEngine.Similarity.simple[IO, String](
  rag,
  corpus.name,
  RetrievalConfig(
    metadataFilter = Some("""tenantid == "user-42""""), // CEL; keys must exist via CreateRagDataSchema + RagMetadata
    ragFileIds = List("ragFiles/abc", "ragFiles/def")   // optional allowlist of RagFile ids
  )
)
sim.findClosest("What is RAG?", k = 3)
```

## Observability

Wrap the client after construction (requires `Tracer`, `LoggerFactory`, and `Meter`). Compose with `Similarity.observed` for traced/metered search:

```scala
import io.github.serhiip.constellations.Similarity

RagClient.resource[IO](config).evalMap(RagClient.apply[IO]).use { rag =>
  for
    sim  <- Similarity.observed(RagEngine.Similarity.simple[IO, String](rag, corpus.name, RetrievalConfig()))
    hits <- sim.findClosest("What is RAG?", k = 3)
  yield hits
}
```

- Spans: `constellations-rag-client-*` (including `*-start` / `*-await` for LROs, `get-lro`, and `get-import-result`), `constellations-similarity-find-closest`
- Metrics: `constellations/rag_client_operation_count`, `constellations/rag_client_error_count`, `constellations/rag_client_operation_duration`, `constellations/rag_client_operation_start_duration`, `constellations/rag_client_imported_files_count`, `constellations/rag_client_failed_import_files_count`, `constellations/similarity_find_closest_success_count`, `constellations/similarity_find_closest_error_count`, `constellations/similarity_find_closest_duration`

## Considerations

These are Vertex RAG Engine / Vector Search constraints that affect how you model corpora and isolation. They are not Constellations-specific, but the client surfaces them directly.

### Vector database backends

`VectorDb` currently models two backends:

| Backend | Typical use | Key constraint |
| --- | --- | --- |
| `RagManaged` | Throwaway corpora, many corpora, simple setup | Shared Spanner-backed store for the project; retrieval strategy is KNN or ANN |
| `VertexVectorSearch` | Existing Vector Search 1.0 index you manage | One corpus ↔ one index; the index must be **empty** when the corpus is created |

You cannot attach multiple RAG corpora to the same Vector Search 1.0 index. Creating a second corpus against a non-empty index fails with `FAILED_PRECONDITION` ("Only empty index is supported to be integrated with Vertex RAG"). After a corpus owns an index, keep growing it with `importFiles` on that corpus — do not create another corpus on the same index.

Resource names for Vector Search must use the project **number** form (`projects/123/...`), not the project ID. Pass numeric `index` and `indexEndpoint` values into `VectorDb.VertexVectorSearch`.

Deleting a Vector Search–backed corpus does **not** purge embeddings from the shared index. Later retrievals against a new corpus on a reused index (or leftover vectors) can surface stale chunks from previous runs. Prefer a dedicated index per long-lived corpus, or treat index contents as durable across corpus deletes.

### RagManaged retrieval strategy

`RagManaged` defaults to exact `Knn`. Latency grows with the number of files in the corpus; Google documents KNN as suitable for smaller corpora (roughly under 10k files). For larger corpora use `RagManagedRetrieval.Ann(treeDepth, leafCount)`. ANN is approximate, needs tuning for your data size, and Vertex expects the ANN index to be rebuilt after significant imports (`rebuild_ann_index` on the import request — not yet exposed by this client).

The Basic / Scaled / Unprovisioned Spanner **tier** that backs RagManagedDb is a project-level `RagEngineConfig` setting, not a per-corpus field. This client does not manage that resource; configure it via `gcloud` / REST / console. Default Basic is aimed at experimentation and latency-insensitive workloads; use Scaled when you need production-grade performance.

### Multi-tenant isolation

Because one Vector Search 1.0 index maps to one corpus, and Vector Search index endpoints are slow to provision, multi-user isolation usually takes one of these shapes:

1. **Corpus per tenant on `RagManaged`** — strongest isolation that still stays cheap to provision. Each tenant's queries only search that corpus. Shared Spanner capacity is still project-wide.
2. **Shared corpus + query scoping (pool)** — one corpus (any backend), attach per-file metadata at import (`GcsImportSource.metadata` / `setFileMetadata`), then scope each `Similarity` with `RetrievalConfig.metadataFilter` (CEL) and/or `ragFileIds`. Create one scoped `TextSimilarity` per tenant/session; do not pass tenant identity through `findClosest`.
3. **Corpus (and empty index) per tenant on Vector Search** — physical isolation with dedicated indexes. Viable only for a small number of long-lived tenants because of provisioning time and standing index cost.

Option 2 needs metadata on the files you intend to filter — a `metadataFilter` alone does nothing if no `RagMetadata` was written. Import `entries` are stamped from the import result sink’s OK `FileId`s for that LRO. Treat filters as an authorization boundary only after you have verified they restrict results for your corpus and backend. Test with a deliberately skewed tenant layout before production.

### Shared-corpus filter performance

With a pooled corpus, small tenants can still pay for large ones:

- Filters are applied during Vector Search traversal (not a naive post-filter of top-k), but the **index structure is global**. Highly selective filters (a tiny tenant in a huge index) can hurt recall and latency, or fall back to brute force over the matching subset.
- On RagManaged with default KNN, search cost scales with corpus file count. A shared corpus means every tenant's query covers every tenant's files unless the filter is pushed down before distance computation — which Vertex does not document clearly for RAG Engine's `metadata_filter` / `rag_file_ids`.
- If the filter is applied after a global top-k, a small tenant can get **zero** contexts even when they hold relevant documents. Validate this with a large tenant + tiny tenant experiment before choosing the pool pattern under skewed sizes.

When tenant sizes vary widely, corpus-per-tenant on RagManaged is usually safer than shared-corpus-plus-filter.

### Import failures and diagnostics

Imports can fail as a completed LRO with `failedCount > 0` (`ImportFailed`) or as a dead LRO (`ImportOperationFailed`, often a bare `INTERNAL`). A single bad file in a batch can take down the whole LRO without naming itself.

`resultSink` is required on every import so Vertex writes per-file outcomes (success and failure) to GCS or BigQuery. The GCS sink path must be unique per import (Vertex returns `FAILED_PRECONDITION` if the object already exists). Pass the import LRO to `ErrorReporter.report` (`ErrorReporter.gcs` / `ErrorReporter.apply`) so `ImportFailed` + sink become an `Ior` (`Both` for partial success, `Left` when nothing imported). After a failure, also call `listFiles` and inspect `RagFileInfo.state` / `errorStatus` — that is corpus-scoped and reliable. Do not use retrieval results as proof of ingestion when the corpus shares a Vector Search index with earlier runs (stale vectors). `GcpRagImportFailureExample` also resolves each sink `FileId` via `getFile` to confirm `{corpus}/ragFiles/{fileId}` naming.

`GcpRagImportFailureExample` exercises batch vs per-file import against a deliberately corrupt document and prints whether the batch failed as a unit.

### Examples

- `GcpRagEngineSimilarityExample` — end-to-end create → import → retrieve → generate; optional Vector Search via env vars.
- `GcpRagImportFailureExample` — import failure / atomicity diagnostics with an import result sink.

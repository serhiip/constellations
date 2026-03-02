# Files

The Files abstraction provides a type-safe way to read and write files across different storage systems.

## Overview

Files provides:
- **File reading** - Read files as base64 for AI consumption
- **File writing** - Write streams to storage
- **Multiple backends** - Local filesystem, GCS, etc.
- **Observability** - Metered and traced variants

```scala
trait Files[F[_]]:
  def readFileAsBase64(path: Path): F[String]
  def writeStream(path: Path, content: fs2.Stream[F, Byte]): F[Unit]
  def resolve(path: String): F[Path]
```

## Coming Soon

Detailed examples for Files usage with different storage providers.

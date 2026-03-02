# Memory

Memory stores and retrieves conversation history and execution context.

## Overview

The Memory abstraction provides:
- **Conversation tracking** - Record all execution steps
- **Query capability** - Retrieve conversation history
- **Last step access** - Quick access to recent activity
- **Multiple implementations** - In-memory, database, etc.

```scala
trait Memory[F[_], Id]:
  def record(step: Executor.Step): F[Unit]
  def retrieve: F[Chain[Executor.Step]]
  def last: F[Option[(Id, Executor.Step)]]
  def mapK[G[_]](f: F ~> G): Memory[G, Id]
```

## Usage

Create an in-memory implementation:

```scala
import io.github.serhiip.constellations.Memory

val memory = Memory.inMemory[IO, UUID]
```

Record execution steps:

```scala
import io.github.serhiip.constellations.Executor.Step
import java.time.OffsetDateTime

for
  _ <- memory.record(Step.UserQuery("What's the weather?", OffsetDateTime.now()))
  _ <- memory.record(Step.Call(functionCall, OffsetDateTime.now()))
  history <- memory.retrieve
yield history
```

## Coming Soon

Detailed examples for custom Memory implementations and integration patterns.

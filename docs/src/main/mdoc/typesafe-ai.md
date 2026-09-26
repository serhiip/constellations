# TypeSafe AI Integration

Constellations provides a client for the [TypeSafe](https://docs.typesafe.ai/) System One API.
TypeSafe models such as Jev return typed judgments and probabilities rather than generated text.

## Overview

The TypeSafe AI module provides:

- **Typed System One evaluation** - named-tuple questions whose answers expose one `Boolean` per choice option, plus option unions and score level counts
- **Model listing** - list available models and aliases
- **Built-in observability** - metrics and tracing via an observed client wrapper
- **Retry logic** - automatic retries with exponential backoff for 429 and 529 responses

## Setup

Add the dependency:

```scala
libraryDependencies += "io.github.serhiip" %% "constellations-typesafe-ai" % "@VERSION@"
```

## Usage

Create a client (reads `TYPESAFE_API_KEY` from your environment for live calls):

```scala
import io.github.serhiip.constellations.typesafe.{*, given}
import cats.effect.IO

Client.resource[IO]("your-api-key").use { client =>
  // Use client here
}
```

## System One evaluation

Ask several independent questions over the same state in one call.
Question ids are Scala identifiers on a named tuple and are used verbatim as wire ids.

- State uses `into[State]` (`String` → text, any circe-encodable value → structured JSON, or `State.chat(...)` → a list of `ChatMessage` turns)
- Instructions use `into[Instructions]` (`String` → text, or any circe-encodable value → structured JSON)
- Both encode as tagged JSON objects (`type` plus the case fields)
- Choice options and noul `yes`/`no` criteria use named tuples (values convert via circe `Encoder`)
- Score levels use a regular tuple (2–10 values)
- Import package conversions with `import ...typesafe.given`

```scala
import cats.effect.IO
import io.circe.Encoder
import io.github.serhiip.constellations.typesafe.{*, given}

final case class TicketState(ticketMessage: String, refundPolicy: String) derives Encoder

val request = SystemOneRequest(
  state = TicketState(
    ticketMessage = "My flight was cancelled. Can I get a refund?",
    refundPolicy = "Cancelled flights are eligible for a full refund."
  ),
  questions = (
    refundRequested = Question.noul("Does `ticketMessage` request a refund?"),
    requestType = Question.choice(
      "What is the main request in `ticketMessage`?",
      (
        refund = "The customer wants money returned.",
        rebooking = "The customer wants a replacement flight.",
        information = "The customer is asking for information only."
      )
    ),
    frustration = Question.score(
      "How frustrated does the customer appear?",
      ("Calm and neutral.", "Concerned but civil.", "Very angry or using strong language.")
    )
  )
)

client.systemOne(request).flatMap { response =>
  for
    refund      <- IO.fromEither(response.answers.refundRequested)
    intent      <- IO.fromEither(response.answers.requestType)
    frustration <- IO.fromEither(response.answers.frustration)
    _           <- IO.println(s"noul=${refund.noul} choice=${intent.selected} score=${frustration.score} level=${frustration.level}")
  yield
    if intent.refund then "refund handler"
    else if intent.rebooking then "rebooking handler"
    else if intent.information then "information handler"
    else "human review"
}
```

Choice answers expose one `Boolean` per declared option (`intent.refund`, `intent.rebooking`, `intent.information`). The option
names come from the question's named tuple, so `intent.marketing` does not compile.

For matching rather than branching, `intent.choice` has type `Option["refund" | "rebooking" | "information"]` and
`frustration.level` has type `Option[0 | 1 | 2]`, which makes matches over option labels and score levels exhaustive. They are
`Option` because narrowing a wire value to its literal type happens at runtime; `selected` and `score` give the unnarrowed values.
`level` is the level the model considered most likely (the highest entry in `probabilities`), while `score` is the continuous
weighted average across levels.
Match on the union directly - passing it through a generic combinator such as `liftTo` widens it back to `String`.

Use `confidence` on Choice and Score answers to decide when to act automatically and when to escalate.

### Dynamic escape hatch

When question ids or option sets are only known at runtime, use `SystemOneRequest.dynamic` with `Question.Dynamic.choice` / `Question.Dynamic.score`, and read answers via `response.noul(id)` / `choice(id)` / `score(id)`.

## List models

```scala
client.listModels().flatMap { response =>
  IO.println(response.models.map(_.name).mkString(", "))
}
```

## Observability

For production wiring with tracing and metrics:

```scala
Client.resourceObserved[IO]("your-api-key").use { client =>
  // Traced systemOne / listModels calls
}
```

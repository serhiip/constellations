package io.github.serhiip.sbt.openapi

import java.io.File
import scala.concurrent.duration.*

enum OpenApiSource:
  case FromFile(file: File)
  case FromUrl(url: String, ttl: FiniteDuration = 24.hours)

final case class GeneratorOptions(
    definitionsPerFile: Int = 40,
    explicitCodecFieldThreshold: Int = 64,
    codecChunkSize: Int = 20,
    discriminatorTagHints: List[String] = List("type", "role", "kind", "status"),
    generateExampleTests: Boolean = true
)

final case class OpenApiSpec(
    name: String,
    source: OpenApiSource,
    packageName: String,
    typeOverrides: Map[String, String] = Map.empty,
    exampleExclusions: Set[String] = Set.empty,
    options: GeneratorOptions = GeneratorOptions()
)

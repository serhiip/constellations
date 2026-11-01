package io.github.serhiip.sbt.openapi

import io.circe.Json
import io.circe.parser

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

object SpecLoader {

  enum Format:
    case Json, Yaml

  def fetch(url: String, userAgent: String): String = {
    val client = HttpClient
      .newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(15))
      .build()
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(url))
      .timeout(Duration.ofSeconds(60))
      .header("User-Agent", userAgent)
      .GET()
      .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if response.statusCode() != 200 then sys.error(s"Failed to fetch OpenAPI spec from $url (HTTP ${response.statusCode()})")
    else response.body()
  }

  def parse(text: String, fileName: String): Either[String, Json] =
    detect(text, fileName) match {
      case Format.Json => parser.parse(text).left.map(err => s"Invalid OpenAPI JSON: ${err.getMessage}")
      case Format.Yaml => io.circe.yaml.parser.parse(text).left.map(err => s"Invalid OpenAPI YAML: ${err.getMessage}")
    }

  def validate(json: Json): Either[String, Json] = {
    val cursor = json.hcursor
    (cursor.get[String]("openapi"), cursor.get[String]("swagger")) match {
      case (Right(version), _) if version.startsWith("3.0.") || version.startsWith("3.1.") =>
        if cursor.downField("components").downField("schemas").succeeded then Right(json)
        else Left("OpenAPI spec has no components.schemas")
      case (Right(version), _) =>
        Left(s"Unsupported OpenAPI version $version; supported versions are 3.0 and 3.1")
      case (_, Right(_)) =>
        Left("Swagger 2.0 specs are not supported; convert the spec to OpenAPI 3")
      case _ =>
        Left("Spec is missing the openapi version field")
    }
  }

  def detect(text: String, fileName: String): Format = {
    val extension = fileName.drop(fileName.lastIndexOf('.') + 1).toLowerCase
    extension match {
      case "json"          => Format.Json
      case "yaml" | "yml"  => Format.Yaml
      case _               =>
        val head = text.dropWhile(_.isWhitespace).headOption
        if head.contains('{') || head.contains('[') then Format.Json else Format.Yaml
    }
  }
}

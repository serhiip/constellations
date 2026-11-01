package io.github.serhiip.sbt.openapi

import io.circe.syntax.*

import java.security.MessageDigest

object Generator {
  final case class Generated(files: ScalaEmitter.GeneratedFiles, definitionNames: Set[String])

  def generate(text: String, fileName: String, spec: OpenApiSpec, sourceLabel: String, pluginVersion: String): Either[List[String], Generated] =
    for {
      parsed <- SpecLoader.parse(text, fileName).left.map(List(_))
      valid  <- SpecLoader.validate(parsed).left.map(List(_))
      model  <- SchemaModel.analyze(
                  valid.noSpaces,
                  spec.typeOverrides,
                  spec.exampleExclusions,
                  spec.options.discriminatorTagHints
                ).left.map(_.map(_.toString))
    } yield Generated(
      ScalaEmitter.emit(
        ScalaEmitter.EmitRequest(
          model = model,
          packageName = spec.packageName,
          sourceLabel = sourceLabel,
          scalaName = Names.scalaName(spec.name),
          unionObject = s"${Names.scalaName(spec.name)}UnionCodecs",
          examplesResource = Names.examplesResource(spec.name),
          pluginVersion = pluginVersion,
          options = spec.options
        )
      ),
      model.definitions.map(_.name).toSet
    )

  def collisions(generated: Seq[(String, String, Set[String])]): List[String] =
    generated.groupBy(_._2).toList.flatMap { (pkg, group) =>
      group.combinations(2).toList.flatMap {
        case Seq((left, _, leftNames), (right, _, rightNames)) =>
          val shared = leftNames.intersect(rightNames)
          if shared.isEmpty then Nil
          else List(s"$left and $right both generate ${shared.toList.sorted.mkString(", ")} in package $pkg")
        case _ => Nil
      }
    }

  def stampKey(raw: String, spec: OpenApiSpec, sourceLabel: String, fingerprint: String): String = {
    val settings = List(
      spec.name,
      spec.packageName,
      sourceLabel,
      spec.typeOverrides.toSeq.sorted.map((key, value) => s"$key=$value").mkString("\n"),
      spec.exampleExclusions.toSeq.sorted.mkString("\n"),
      spec.options.toString,
      SchemaModel.GeneratorVersion,
      fingerprint
    ).mkString("\n")
    sha256(sha256(raw) + sha256(settings))
  }

  def sha256(text: String): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    digest.map("%02x".format(_)).mkString
  }
}

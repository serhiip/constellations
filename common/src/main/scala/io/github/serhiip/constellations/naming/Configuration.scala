package io.github.serhiip.constellations.naming

final case class Configuration(
    transformComponentNames: String => String = Predef.identity,
    transformMethodNames: String => String = Predef.identity,
    transformMemberNames: String => String = Predef.identity,
    transformConstructorNames: String => String = Predef.identity,
    discriminator: String = "_type"
):
  def withTransformComponentNames(f: String => String): Configuration   = copy(transformComponentNames = f)
  def withSnakeCaseComponentNames: Configuration                        = withTransformComponentNames(Renaming.snakeCase)
  def withTransformMethodNames(f: String => String): Configuration      = copy(transformMethodNames = f)
  def withSnakeCaseMethodNames: Configuration                           = withTransformMethodNames(Renaming.snakeCase)
  def withKebabCaseMethodNames: Configuration                           = withTransformMethodNames(Renaming.kebabCase)
  def withScreamingSnakeCaseMethodNames: Configuration                  = withTransformMethodNames(Renaming.screamingSnakeCase)
  def withTransformMemberNames(f: String => String): Configuration      = copy(transformMemberNames = f)
  def withSnakeCaseMemberNames: Configuration                           = withTransformMemberNames(Renaming.snakeCase)
  def withKebabCaseMemberNames: Configuration                           = withTransformMemberNames(Renaming.kebabCase)
  def withScreamingSnakeCaseMemberNames: Configuration                  = withTransformMemberNames(Renaming.screamingSnakeCase)
  def withTransformConstructorNames(f: String => String): Configuration = copy(transformConstructorNames = f)
  def withSnakeCaseConstructorNames: Configuration                      = withTransformConstructorNames(Renaming.snakeCase)
  def withDiscriminator(field: String): Configuration                   = copy(discriminator = field)

object Configuration:
  val default: Configuration   = Configuration()
  val snakeCase: Configuration = default.withSnakeCaseComponentNames.withSnakeCaseMethodNames.withSnakeCaseMemberNames

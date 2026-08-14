package io.github.serhiip.constellations

import cats.effect.IO
import cats.syntax.all.*
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.dispatcher.ValueEncoder
import io.github.serhiip.constellations.naming.Configuration
import munit.CatsEffectSuite

final case class AmbientPing(value: String) derives ValueEncoder

trait AmbientPingApi[F[_]]:
  def pingValue(): F[AmbientPing]

class ToolDispatcherAmbientConfigTest extends CatsEffectSuite:
  given Configuration = Configuration.default.withKebabCaseMemberNames.withKebabCaseMethodNames

  test("ambient Configuration is picked up by generate") {
    val api = new AmbientPingApi[IO]:
      def pingValue(): IO[AmbientPing] = AmbientPing("ok").pure[IO]
    val d   = ToolDispatcher.generate[IO](api)
    d.getFunctionDeclarations.map { decls =>
      assertEquals(decls.map(_.name), List("AmbientPingApi_ping-value"))
    }
  }

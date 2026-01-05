package io.github.serhiip.constellations.common

import fs2.Stream

final case class GeneratedImage[F[_]](
    mimeType: String,
    extension: String,
    bytes: Stream[F, Byte]
)

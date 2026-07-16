package io.github.serhiip.constellations

import cats.Functor
import cats.syntax.functor.*
import cats.~>

import io.github.serhiip.constellations.common.GeneratedImage

trait AssetsHandling[F[_], T]:
  def getImages(response: T): F[List[GeneratedImage[F]]]

object AssetsHandling:
  def apply[F[_], T](using h: AssetsHandling[F, T]): AssetsHandling[F, T] = h

  def mapK[F[_], G[_]: Functor, T](h: AssetsHandling[F, T])(fk: F ~> G): AssetsHandling[G, T] = new:
    def getImages(response: T): G[List[GeneratedImage[G]]] =
      fk(h.getImages(response)).map: images =>
        images.map: image =>
          GeneratedImage(image.mimeType, image.extension, image.bytes.translate(fk))

/*
 * Copyright (c) 2016 Azavea.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package geotrellis.vectortile.protobuf

import geotrellis.vector._
import geotrellis.vectortile.{Layer, Value, VectorTile}
import scala.collection.mutable.ListBuffer
import vector_tile.{vector_tile => vt}

// --- //

case class ProtobufTile(
  layers: Map[String, ProtobufLayer]
) extends VectorTile

object ProtobufTile {
  /** Create a ProtobufTile masked as its parent trait. */
  def apply(tile: vt.Tile): VectorTile = {
    val layers: Map[String, ProtobufLayer] = tile.layers.map({ l =>
      val pbl = ProtobufLayer(l)

      pbl.name -> pbl
    }).toMap

    new ProtobufTile(layers)
  }
}

/**
 * Wild, unbased assumption of VT Features: their `id` values can be ignored
 * at read time, and rewritten as anything at write time.
 */
case class ProtobufLayer(
  name: String,
  extent: Int,
  rawFeatures: Seq[vt.Tile.Feature]
) extends Layer {
  /* Unconsumed raw Features */
  private val (pointFs, lineFs, polyFs) = segregate(rawFeatures)

  /**
   * Polymorphically generate a [[Stream]] of parsed Geometries and
   * their metadata.
   */
  private def geomStream[G1 <: Geometry, G2 <: MultiGeometry](
    feats: ListBuffer[vt.Tile.Feature]
  )(implicit protobufGeom: ProtobufGeom[G1, G2]): Stream[(Either[G1, G2], Map[String, Value])] = {
    def loop(fs: ListBuffer[vt.Tile.Feature]): Stream[(Either[G1, G2], Map[String, Value])] = {
      if (fs.isEmpty) {
        Stream.empty[(Either[G1, G2], Map[String, Value])]
      } else {
        val geoms = fs.head.geometry
        val g = protobufGeom.fromCommands(Command.commands(geoms))

        (g, Map.empty[String, Value]) #:: loop(fs.tail)
      }
    }

    loop(feats)
  }

  /* Geometry Streams */
  lazy val pointStream = geomStream[Point, MultiPoint](pointFs)
  lazy val lineStream = geomStream[Line, MultiLine](lineFs)
  lazy val polyStream = geomStream[Polygon, MultiPolygon](polyFs)

  // TODO Likely faster with manual recursion in a fold-like pattern,
  // and it will squash the pattern match warnings.
  lazy val points: Stream[Feature[Point, Map[String, Value]]] = pointStream
    .filter(_._1.isLeft)
    .map({ case (Left(p), meta) => Feature(p, meta) })

  lazy val multiPoints: Stream[Feature[MultiPoint, Map[String, Value]]] = pointStream
    .filter(_._1.isRight)
    .map({ case (Right(p), meta) => Feature(p, meta) })

  lazy val lines: Stream[Feature[Line, Map[String, Value]]] = lineStream
    .filter(_._1.isLeft)
    .map({ case (Left(p), meta) => Feature(p, meta) })

  lazy val multiLines: Stream[Feature[MultiLine, Map[String, Value]]] = lineStream
    .filter(_._1.isRight)
    .map({ case (Right(p), meta) => Feature(p, meta) })

  lazy val polygons: Stream[Feature[Polygon, Map[String, Value]]] = polyStream
    .filter(_._1.isLeft)
    .map({ case (Left(p), meta) => Feature(p, meta) })

  lazy val multiPolygons: Stream[Feature[MultiPolygon, Map[String, Value]]] = polyStream
    .filter(_._1.isRight)
    .map({ case (Right(p), meta) => Feature(p, meta) })

  /**
   * Given a raw protobuf Layer, segregate its Features by their GeomType.
   * `UNKNOWN` geometry types are ignored.
   */
  private def segregate(
    features: Seq[vt.Tile.Feature]
  ): (ListBuffer[vt.Tile.Feature], ListBuffer[vt.Tile.Feature], ListBuffer[vt.Tile.Feature]) = {
    val points = new ListBuffer[vt.Tile.Feature]
    val lines = new ListBuffer[vt.Tile.Feature]
    val polys = new ListBuffer[vt.Tile.Feature]

    features.foreach { f =>
      f.getType match {
        case vt.Tile.GeomType.POINT => points.append(f)
        case vt.Tile.GeomType.LINESTRING => lines.append(f)
        case vt.Tile.GeomType.POLYGON => polys.append(f)
        case _ => Unit // `UNKNOWN` or `Unrecognized`.
      }
    }

    (points, lines, polys)
  }

  /**
   * Force all the internal raw Feature stores to fully parse their contents
   * into Geotrellis Features.
   */
  def force: Unit = {
    ???
  }
}

object ProtobufLayer {
  def apply(layer: vt.Tile.Layer): ProtobufLayer = ???
}

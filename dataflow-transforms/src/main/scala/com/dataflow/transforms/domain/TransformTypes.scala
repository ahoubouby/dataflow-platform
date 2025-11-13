package com.dataflow.transforms.domain

import com.dataflow.serialization.CborSerializable

/**
 * ADT for transform types.
 * Type-safe alternative to String-based type identification.
 */
sealed trait TransformType extends CborSerializable {
  def name: String
}

object TransformType {
  case object Filter extends TransformType {
    override def name: String = "filter"
  }

  case object Map extends TransformType {
    override def name: String = "map"
  }

  case object FlatMap extends TransformType {
    override def name: String = "flatMap"
  }

  case object Aggregate extends TransformType {
    override def name: String = "aggregate"
  }

  case object Enrich extends TransformType {
    override def name: String = "enrich"
  }

  /**
   * Parse transform type from string (for backwards compatibility).
   */
  def fromString(name: String): Option[TransformType] = name.toLowerCase match {
    case "filter" => Some(Filter)
    case "map" => Some(Map)
    case "flatmap" => Some(FlatMap)
    case "aggregate" => Some(Aggregate)
    case "enrich" => Some(Enrich)
    case _ => None
  }

  /**
   * All available transform types.
   */
  def all: Seq[TransformType] = Seq(Filter, Map, FlatMap, Aggregate, Enrich)
}

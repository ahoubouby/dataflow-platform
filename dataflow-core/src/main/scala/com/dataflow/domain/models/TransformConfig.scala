package com.dataflow.domain.models

import com.dataflow.serialization.CborSerializable

/**
 * Transform configuration (how to process data).
 */
final case class TransformConfig(
  transformType: String, // "filter", "map", "aggregate"
  config: Map[String, String]) extends CborSerializable

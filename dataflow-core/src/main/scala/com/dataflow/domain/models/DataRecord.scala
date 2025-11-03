package com.dataflow.domain.models

import com.dataflow.serialization.CborSerializable

/**
 * A single data record in the pipeline.
 */
final case class DataRecord(
  id: String,
  data: Map[String, String],
  metadata: Map[String, String] = Map.empty) extends CborSerializable

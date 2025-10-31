package com.dataflow.domain.models

import com.dataflow.serialization.CborSerializable

/**
 * Sink configuration (where data goes).
 */
final case class SinkConfig(
  sinkType: String, // "kafka", "file", "cassandra", "elasticsearch"
  connectionString: String,
  batchSize: Int) extends CborSerializable

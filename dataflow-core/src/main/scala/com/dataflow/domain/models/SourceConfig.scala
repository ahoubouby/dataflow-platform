package com.dataflow.domain.models

import com.dataflow.serialization.CborSerializable

/**
 * Source configuration (where data comes from).
 */
final case class SourceConfig(
  sourceType: String,       // "kafka", "file", "api", "database"
  connectionString: String, // Connection details
  batchSize: Int,           // Records per batch
  pollIntervalMs: Int,      // Milliseconds between polls
) extends CborSerializable

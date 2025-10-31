package com.dataflow.domain.models

import java.time.Instant

import com.dataflow.serialization.CborSerializable

/**
 * Checkpoint tracks processed offset for exactly-once semantics.
 */
final case class Checkpoint(
  offset: Long,
  timestamp: Instant,
  recordsProcessed: Long) extends CborSerializable

object Checkpoint {
  val initial: Checkpoint = Checkpoint(0, Instant.EPOCH, 0)
}

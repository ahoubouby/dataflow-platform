package com.dataflow.transforms.domain

import com.dataflow.domain.models.DataRecord
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

/**
 * Core abstraction for data transformations.
 *
 * All transformations are implemented as Pekko Streams Flow, which provides:
 * - Automatic backpressure handling
 * - Composability (transforms can be chained)
 * - Performance and efficiency
 *
 * Transforms can be:
 * - Stateless: Each record is transformed independently (Filter, Map, FlatMap)
 * - Stateful: Transform maintains state across records (Aggregate, Window)
 */
trait Transform {
  /**
   * The type of this transform (type-safe ADT).
   */
  def transformType: TransformType

  /**
   * The Pekko Streams Flow that performs the transformation.
   *
   * Input: Stream of DataRecord
   * Output: Stream of DataRecord (transformed)
   *
   * The Flow can:
   * - Filter records (0 or 1 output per input)
   * - Map records (1 output per input)
   * - FlatMap records (0..N outputs per input)
   * - Aggregate records (M inputs -> 1 output)
   */
  def flow: Flow[DataRecord, DataRecord, NotUsed]

  /**
   * Error handling strategy for this transform.
   */
  def errorHandler: ErrorHandlingStrategy = ErrorHandlingStrategy.Skip
}

/**
 * Defines how to handle errors during transformation.
 */
sealed trait ErrorHandlingStrategy

object ErrorHandlingStrategy {
  /**
   * Skip records that fail transformation (log error and continue).
   */
  case object Skip extends ErrorHandlingStrategy

  /**
   * Fail the entire pipeline on first error.
   */
  case object Fail extends ErrorHandlingStrategy

  /**
   * Send failed records to a dead letter queue and continue.
   */
  case object DeadLetter extends ErrorHandlingStrategy
}

/**
 * Marker trait for stateless transforms.
 * These transforms process each record independently.
 */
trait StatelessTransform extends Transform

/**
 * Marker trait for stateful transforms.
 * These transforms maintain state across multiple records.
 */
trait StatefulTransform extends Transform

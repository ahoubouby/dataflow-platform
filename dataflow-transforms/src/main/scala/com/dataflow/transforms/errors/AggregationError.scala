package com.dataflow.transforms.errors

/**
 * Aggregation errors with ADT pattern for type-safe error handling.
 */
sealed trait AggregationError {
  def cause: Throwable
  def message: String = cause.getMessage
}

case class ExtractionError(cause: Throwable) extends AggregationError {
  override def toString: String = s"ExtractionError: ${cause.getMessage}"
}

case class ComputationError(cause: Throwable) extends AggregationError {
  override def toString: String = s"ComputationError: ${cause.getMessage}"
}

object AggregationError {

  /**
   * Create error from throwable with type inference.
   */
  def extraction(throwable: Throwable):  AggregationError = ExtractionError(throwable)
  def computation(throwable: Throwable): AggregationError = ComputationError(throwable)

  /**
   * Extract message from error.
   */
  def getMessage(error: AggregationError): String = error match {
    case ExtractionError(cause)  => s"Failed to extract data: ${cause.getMessage}"
    case ComputationError(cause) => s"Failed to compute aggregation: ${cause.getMessage}"
  }
}

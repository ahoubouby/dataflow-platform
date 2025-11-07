package com.dataflow.transforms.filters

import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.errors.FilterError
import cats.implicits._


/**
 * Advanced filter operations for complex scenarios.
 */
object AdvancedFilters {

  /**
   * Combine multiple filters with AND logic.
   */
  def and(filters: FilterTransform*): DataRecord => Either[FilterError, Boolean] = {
    record =>
      filters.toList
        .traverse(filter => filter.evaluateFilter(record))
        .map(_.forall(identity))
  }

  /**
   * Combine multiple filters with OR logic.
   */
  def or(filters: FilterTransform*): DataRecord => Either[FilterError, Boolean] = {
    record =>
      filters.toList
        .traverse(filter => filter.evaluateFilter(record))
        .map(_.exists(identity))
  }

  /**
   * Negate a filter.
   */
  def not(filter: FilterTransform): DataRecord => Either[FilterError, Boolean] = {
    record =>
      filter.evaluateFilter(record).map(!_)
  }
}


package com.dataflow.transforms.filters.models

/**
 * Filter statistics for monitoring.
 */
case class FilterStats(
  totalRecords: Long,
  matchedRecords: Long,
  filteredRecords: Long,
  errorRecords: Long) {

  def matchRate: Double =
    if (totalRecords == 0) 0.0
    else matchedRecords.toDouble / totalRecords.toDouble

  def errorRate: Double =
    if (totalRecords == 0) 0.0
    else errorRecords.toDouble / totalRecords.toDouble

  def incrementTotal(): FilterStats = copy(totalRecords = totalRecords + 1)
  def incrementMatched(): FilterStats = copy(matchedRecords = matchedRecords + 1)
  def incrementFiltered(): FilterStats = copy(filteredRecords = filteredRecords + 1)
  def incrementErrors(): FilterStats = copy(errorRecords = errorRecords + 1)
}

object FilterStats {
  val empty: FilterStats = FilterStats(0, 0, 0, 0)

  /**
   * Combine stats using Monoid-like pattern.
   */
  def combine(a: FilterStats, b: FilterStats): FilterStats =
    FilterStats(
      totalRecords = a.totalRecords + b.totalRecords,
      matchedRecords = a.matchedRecords + b.matchedRecords,
      filteredRecords = a.filteredRecords + b.filteredRecords,
      errorRecords = a.errorRecords + b.errorRecords,
    )


}

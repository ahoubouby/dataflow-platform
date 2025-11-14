package com.dataflow.transforms

import kamon.Kamon
import kamon.metric.{Counter, Histogram}
import org.slf4j.{Logger, LoggerFactory}

/**
 * TransformMetricsReporter publishes transformation metrics to Kamon for monitoring.
 *
 * Metrics Published:
 * - Records transformed (input → output)
 * - Records filtered (dropped)
 * - Records failed (errors during transformation)
 * - Transformation latency
 * - Transform-specific metrics (aggregation counts, join matches, etc.)
 *
 * Usage:
 * {{{
 *   TransformMetricsReporter.recordTransform(pipelineId, transformType, inputCount, outputCount, latencyMs)
 *   TransformMetricsReporter.recordFiltered(pipelineId, transformType, count)
 *   TransformMetricsReporter.recordError(pipelineId, transformType, errorType)
 * }}}
 */
object TransformMetricsReporter {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  // ============================================
  // TRANSFORMATION METRICS
  // ============================================

  private val recordsTransformedCounter: Counter =
    Kamon.counter("dataflow.transform.records.transformed").withoutTags()

  private val recordsInputCounter: Counter =
    Kamon.counter("dataflow.transform.records.input").withoutTags()

  private val recordsOutputCounter: Counter =
    Kamon.counter("dataflow.transform.records.output").withoutTags()

  private val recordsFilteredCounter: Counter =
    Kamon.counter("dataflow.transform.records.filtered").withoutTags()

  private val transformLatencyHistogram: Histogram =
    Kamon.histogram("dataflow.transform.latency.ms").withoutTags()

  // ============================================
  // ERROR METRICS
  // ============================================

  private val errorsCounter: Counter =
    Kamon.counter("dataflow.transform.errors").withoutTags()

  private val validationErrorsCounter: Counter =
    Kamon.counter("dataflow.transform.validation.errors").withoutTags()

  private val transformationErrorsCounter: Counter =
    Kamon.counter("dataflow.transform.transformation.errors").withoutTags()

  // ============================================
  // TRANSFORM-SPECIFIC METRICS
  // ============================================

  // Filter Transform
  private val filterMatchesCounter: Counter =
    Kamon.counter("dataflow.transform.filter.matches").withoutTags()

  private val filterRejectionsCounter: Counter =
    Kamon.counter("dataflow.transform.filter.rejections").withoutTags()

  // Map Transform
  private val mapSuccessCounter: Counter =
    Kamon.counter("dataflow.transform.map.success").withoutTags()

  private val mapFailureCounter: Counter =
    Kamon.counter("dataflow.transform.map.failure").withoutTags()

  // Aggregate Transform
  private val aggregateGroupsCounter: Counter =
    Kamon.counter("dataflow.transform.aggregate.groups").withoutTags()

  private val aggregateRecordsPerGroupHistogram: Histogram =
    Kamon.histogram("dataflow.transform.aggregate.records.per.group").withoutTags()

  // Join Transform
  private val joinMatchesCounter: Counter =
    Kamon.counter("dataflow.transform.join.matches").withoutTags()

  private val joinMissesCounter: Counter =
    Kamon.counter("dataflow.transform.join.misses").withoutTags()

  // ============================================
  // RECORD TRANSFORMATION
  // ============================================

  /**
   * Record a transformation operation.
   *
   * @param pipelineId The pipeline ID
   * @param transformType The transform type (filter, map, aggregate, etc.)
   * @param inputCount Number of input records
   * @param outputCount Number of output records
   * @param latencyMs Time taken for transformation in milliseconds
   */
  def recordTransform(
    pipelineId: String,
    transformType: String,
    inputCount: Long,
    outputCount: Long,
    latencyMs: Long
  ): Unit = {
    log.trace(
      "msg=Record transform pipelineId={} type={} input={} output={} latencyMs={}",
      pipelineId,
      transformType,
      inputCount,
      outputCount,
      latencyMs
    )

    recordsTransformedCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("transform_type", transformType)
      .increment(outputCount)

    recordsInputCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("transform_type", transformType)
      .increment(inputCount)

    recordsOutputCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("transform_type", transformType)
      .increment(outputCount)

    transformLatencyHistogram
      .withTag("pipeline_id", pipelineId)
      .withTag("transform_type", transformType)
      .record(latencyMs)
  }

  /**
   * Record records that were filtered out.
   *
   * @param pipelineId The pipeline ID
   * @param transformType The transform type
   * @param count Number of records filtered
   */
  def recordFiltered(
    pipelineId: String,
    transformType: String,
    count: Long
  ): Unit = {
    log.debug("msg=Record filtered pipelineId={} type={} count={}", pipelineId, transformType, count)

    recordsFilteredCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("transform_type", transformType)
      .increment(count)
  }

  /**
   * Record a transformation error.
   *
   * @param pipelineId The pipeline ID
   * @param transformType The transform type
   * @param errorType The type of error
   */
  def recordError(
    pipelineId: String,
    transformType: String,
    errorType: String
  ): Unit = {
    log.warn("msg=Record transform error pipelineId={} type={} errorType={}", pipelineId, transformType, errorType)

    errorsCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("transform_type", transformType)
      .withTag("error_type", errorType)
      .increment()
  }

  /**
   * Record a validation error.
   *
   * @param pipelineId The pipeline ID
   * @param transformType The transform type
   * @param validationType The validation that failed
   */
  def recordValidationError(
    pipelineId: String,
    transformType: String,
    validationType: String
  ): Unit = {
    log.warn("msg=Record validation error pipelineId={} type={} validation={}", pipelineId, transformType, validationType)

    validationErrorsCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("transform_type", transformType)
      .withTag("validation_type", validationType)
      .increment()
  }

  // ============================================
  // FILTER TRANSFORM METRICS
  // ============================================

  /**
   * Record filter transform results.
   *
   * @param pipelineId The pipeline ID
   * @param matches Number of records that passed the filter
   * @param rejections Number of records that were filtered out
   */
  def recordFilterResults(
    pipelineId: String,
    matches: Long,
    rejections: Long
  ): Unit = {
    log.debug("msg=Record filter results pipelineId={} matches={} rejections={}", pipelineId, matches, rejections)

    filterMatchesCounter
      .withTag("pipeline_id", pipelineId)
      .increment(matches)

    filterRejectionsCounter
      .withTag("pipeline_id", pipelineId)
      .increment(rejections)
  }

  // ============================================
  // MAP TRANSFORM METRICS
  // ============================================

  /**
   * Record map transform results.
   *
   * @param pipelineId The pipeline ID
   * @param successCount Number of successful mappings
   * @param failureCount Number of failed mappings
   */
  def recordMapResults(
    pipelineId: String,
    successCount: Long,
    failureCount: Long
  ): Unit = {
    log.debug("msg=Record map results pipelineId={} success={} failure={}", pipelineId, successCount, failureCount)

    mapSuccessCounter
      .withTag("pipeline_id", pipelineId)
      .increment(successCount)

    if (failureCount > 0) {
      mapFailureCounter
        .withTag("pipeline_id", pipelineId)
        .increment(failureCount)
    }
  }

  // ============================================
  // AGGREGATE TRANSFORM METRICS
  // ============================================

  /**
   * Record aggregate transform results.
   *
   * @param pipelineId The pipeline ID
   * @param groupCount Number of groups created
   * @param avgRecordsPerGroup Average records per group
   */
  def recordAggregateResults(
    pipelineId: String,
    groupCount: Long,
    avgRecordsPerGroup: Double
  ): Unit = {
    log.debug("msg=Record aggregate results pipelineId={} groups={} avgPerGroup={}", pipelineId, groupCount, avgRecordsPerGroup)

    aggregateGroupsCounter
      .withTag("pipeline_id", pipelineId)
      .increment(groupCount)

    aggregateRecordsPerGroupHistogram
      .withTag("pipeline_id", pipelineId)
      .record(avgRecordsPerGroup.toLong)
  }

  // ============================================
  // JOIN TRANSFORM METRICS
  // ============================================

  /**
   * Record join transform results.
   *
   * @param pipelineId The pipeline ID
   * @param matches Number of successful joins
   * @param misses Number of records with no join match
   */
  def recordJoinResults(
    pipelineId: String,
    matches: Long,
    misses: Long
  ): Unit = {
    log.debug("msg=Record join results pipelineId={} matches={} misses={}", pipelineId, matches, misses)

    joinMatchesCounter
      .withTag("pipeline_id", pipelineId)
      .increment(matches)

    joinMissesCounter
      .withTag("pipeline_id", pipelineId)
      .increment(misses)
  }
}

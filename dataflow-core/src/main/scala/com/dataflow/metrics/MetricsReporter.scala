package com.dataflow.metrics

import com.dataflow.domain.models.PipelineMetrics
import com.dataflow.domain.state._
import kamon.Kamon
import kamon.metric.{Counter, Gauge, Histogram}
import org.slf4j.{Logger, LoggerFactory}

/**
 * MetricsReporter publishes pipeline metrics to Kamon for monitoring.
 *
 * Metrics Published:
 * - Pipeline state counters (running, stopped, paused, failed)
 * - Batch processing metrics (throughput, latency, errors)
 * - Record processing counters
 * - System health indicators
 *
 * Usage:
 * {{{
 *   MetricsReporter.recordStateTransition(pipelineId, EmptyState, ConfiguredState(...))
 *   MetricsReporter.recordBatchProcessed(pipelineId, successCount, failureCount, elapsedMs)
 *   MetricsReporter.updatePipelineMetrics(pipelineId, metrics)
 * }}}
 */
object MetricsReporter {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  // ============================================
  // PIPELINE STATE METRICS
  // ============================================

  private val pipelinesRunningGauge: Gauge =
    Kamon.gauge("dataflow.pipelines.running").withoutTags()

  private val pipelinesStoppedGauge: Gauge =
    Kamon.gauge("dataflow.pipelines.stopped").withoutTags()

  private val pipelinesPausedGauge: Gauge =
    Kamon.gauge("dataflow.pipelines.paused").withoutTags()

  private val pipelinesFailedGauge: Gauge =
    Kamon.gauge("dataflow.pipelines.failed").withoutTags()

  private val stateTransitionsCounter: Counter =
    Kamon.counter("dataflow.state.transitions").withoutTags()

  // ============================================
  // BATCH PROCESSING METRICS
  // ============================================

  private val batchesProcessedCounter: Counter =
    Kamon.counter("dataflow.batches.processed").withoutTags()

  private val batchesFailedCounter: Counter =
    Kamon.counter("dataflow.batches.failed").withoutTags()

  private val batchProcessingTimeHistogram: Histogram =
    Kamon.histogram("dataflow.batch.processing.time.ms").withoutTags()

  // ============================================
  // RECORD PROCESSING METRICS
  // ============================================

  private val recordsProcessedCounter: Counter =
    Kamon.counter("dataflow.records.processed").withoutTags()

  private val recordsFailedCounter: Counter =
    Kamon.counter("dataflow.records.failed").withoutTags()

  private val recordsThroughputGauge: Gauge =
    Kamon.gauge("dataflow.records.throughput.per.second").withoutTags()

  // ============================================
  // ERROR AND RETRY METRICS
  // ============================================

  private val retriesScheduledCounter: Counter =
    Kamon.counter("dataflow.retries.scheduled").withoutTags()

  private val batchTimeoutsCounter: Counter =
    Kamon.counter("dataflow.batches.timeouts").withoutTags()

  // ============================================
  // CHECKPOINT METRICS
  // ============================================

  private val checkpointUpdatesCounter: Counter =
    Kamon.counter("dataflow.checkpoint.updates").withoutTags()

  // Track state counts for gauges
  @volatile private var runningCount: Int  = 0
  @volatile private var stoppedCount: Int  = 0
  @volatile private var pausedCount: Int   = 0
  @volatile private var failedCount: Int   = 0

  /**
   * Initialize Kamon metrics system.
   * Should be called once at application startup.
   */
  def init(): Unit = {
    log.info("msg=Initializing Kamon metrics")
    Kamon.init()
    log.info("msg=Kamon metrics initialized")
  }

  /**
   * Stop Kamon and flush all metrics.
   * Should be called at application shutdown.
   */
  def shutdown(): Unit = {
    log.info("msg=Shutting down Kamon metrics")
    Kamon.stop()
    log.info("msg=Kamon metrics shut down")
  }

  /**
   * Record a state transition for a pipeline.
   *
   * @param pipelineId The pipeline ID
   * @param fromState The previous state
   * @param toState The new state
   */
  def recordStateTransition(
    pipelineId: String,
    fromState: State,
    toState: State,
  ): Unit = {
    log.debug("msg=Record state transition pipelineId={} from={} to={}", pipelineId, fromState.getClass.getSimpleName, toState.getClass.getSimpleName)

    // Increment state transition counter
    stateTransitionsCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("from_state", stateToString(fromState))
      .withTag("to_state", stateToString(toState))
      .increment()

    // Update state counts
    updateStateCounts(fromState, toState)
  }

  /**
   * Record successful batch processing.
   *
   * @param pipelineId The pipeline ID
   * @param successCount Number of records successfully processed
   * @param failureCount Number of records that failed
   * @param processingTimeMs Time taken to process the batch in milliseconds
   */
  def recordBatchProcessed(
    pipelineId: String,
    successCount: Int,
    failureCount: Int,
    processingTimeMs: Long,
  ): Unit = {
    log.debug("msg=Record batch processed pipelineId={} success={} failed={} timeMs={}", pipelineId, successCount, failureCount, processingTimeMs)

    // Increment batch counter
    batchesProcessedCounter
      .withTag("pipeline_id", pipelineId)
      .increment()

    // Record processing time
    batchProcessingTimeHistogram
      .withTag("pipeline_id", pipelineId)
      .record(processingTimeMs)

    // Increment record counters
    recordsProcessedCounter
      .withTag("pipeline_id", pipelineId)
      .increment(successCount.toLong)

    if (failureCount > 0) {
      recordsFailedCounter
        .withTag("pipeline_id", pipelineId)
        .increment(failureCount.toLong)
    }
  }

  /**
   * Record batch failure.
   *
   * @param pipelineId The pipeline ID
   * @param errorCode The error code
   */
  def recordBatchFailed(
    pipelineId: String,
    errorCode: String,
  ): Unit = {
    log.debug("msg=Record batch failed pipelineId={} errorCode={}", pipelineId, errorCode)

    batchesFailedCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("error_code", errorCode)
      .increment()
  }

  /**
   * Record a retry being scheduled.
   *
   * @param pipelineId The pipeline ID
   * @param errorCode The error code that triggered the retry
   * @param retryCount The current retry attempt number
   */
  def recordRetryScheduled(
    pipelineId: String,
    errorCode: String,
    retryCount: Int,
  ): Unit = {
    log.debug("msg=Record retry scheduled pipelineId={} errorCode={} retryCount={}", pipelineId, errorCode, retryCount)

    retriesScheduledCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("error_code", errorCode)
      .increment()
  }

  /**
   * Record a batch timeout.
   *
   * @param pipelineId The pipeline ID
   * @param batchId The batch ID that timed out
   */
  def recordBatchTimeout(
    pipelineId: String,
    batchId: String,
  ): Unit = {
    log.debug("msg=Record batch timeout pipelineId={} batchId={}", pipelineId, batchId)

    batchTimeoutsCounter
      .withTag("pipeline_id", pipelineId)
      .increment()
  }

  /**
   * Record a checkpoint update.
   *
   * @param pipelineId The pipeline ID
   * @param offset The new checkpoint offset
   */
  def recordCheckpointUpdate(
    pipelineId: String,
    offset: Long,
  ): Unit = {
    log.debug("msg=Record checkpoint update pipelineId={} offset={}", pipelineId, offset)

    checkpointUpdatesCounter
      .withTag("pipeline_id", pipelineId)
      .increment()
  }

  /**
   * Update pipeline metrics gauge with current metrics.
   *
   * @param pipelineId The pipeline ID
   * @param metrics The current pipeline metrics
   */
  def updatePipelineMetrics(
    pipelineId: String,
    metrics: PipelineMetrics,
  ): Unit = {
    log.debug("msg=Update pipeline metrics pipelineId={} throughput={}", pipelineId, metrics.throughputPerSecond)

    // Update throughput gauge
    recordsThroughputGauge
      .withTag("pipeline_id", pipelineId)
      .update(metrics.throughputPerSecond)
  }

  // ============================================
  // PRIVATE HELPERS
  // ============================================

  private def updateStateCounts(fromState: State, toState: State): Unit = {
    // Decrement old state count
    fromState match {
      case _: RunningState    => runningCount -= 1
      case _: StoppedState    => stoppedCount -= 1
      case _: PausedState     => pausedCount -= 1
      case _: FailedState     => failedCount -= 1
      case _: ConfiguredState => // No gauge for configured
      case EmptyState         => // No gauge for empty
    }

    // Increment new state count
    toState match {
      case _: RunningState    => runningCount += 1
      case _: StoppedState    => stoppedCount += 1
      case _: PausedState     => pausedCount += 1
      case _: FailedState     => failedCount += 1
      case _: ConfiguredState => // No gauge for configured
      case EmptyState         => // No gauge for empty
    }

    // Update gauges
    pipelinesRunningGauge.update(runningCount.toDouble)
    pipelinesStoppedGauge.update(stoppedCount.toDouble)
    pipelinesPausedGauge.update(pausedCount.toDouble)
    pipelinesFailedGauge.update(failedCount.toDouble)
  }

  private def stateToString(state: State): String = state match {
    case EmptyState         => "empty"
    case _: ConfiguredState => "configured"
    case _: RunningState    => "running"
    case _: PausedState     => "paused"
    case _: StoppedState    => "stopped"
    case _: FailedState     => "failed"
  }
}

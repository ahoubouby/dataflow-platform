package com.dataflow.sources

import kamon.Kamon
import kamon.metric.{Counter, Gauge, Histogram}
import org.slf4j.{Logger, LoggerFactory}

/**
 * SourceMetricsReporter publishes source connector metrics to Kamon for monitoring.
 *
 * Metrics Published:
 * - Records read counters
 * - Bytes read counters
 * - Batches sent counters
 * - Error counters
 * - Source-specific metrics (Kafka lag, API response time, DB query time)
 * - Source health indicators
 *
 * Usage:
 * {{{
 *   SourceMetricsReporter.recordRecordsRead(pipelineId, sourceType, count)
 *   SourceMetricsReporter.recordBatchSent(pipelineId, sourceType, recordCount)
 *   SourceMetricsReporter.recordError(pipelineId, sourceType, errorType)
 *   SourceMetricsReporter.updateKafkaLag(pipelineId, partition, lag)
 * }}}
 */
object SourceMetricsReporter {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  // ============================================
  // RECORD INGESTION METRICS
  // ============================================

  private val recordsReadCounter: Counter =
    Kamon.counter("dataflow.source.records.read").withoutTags()

  private val bytesReadCounter: Counter =
    Kamon.counter("dataflow.source.bytes.read").withoutTags()

  private val recordsSkippedCounter: Counter =
    Kamon.counter("dataflow.source.records.skipped").withoutTags()

  // ============================================
  // BATCH SENDING METRICS
  // ============================================

  private val batchesSentCounter: Counter =
    Kamon.counter("dataflow.source.batches.sent").withoutTags()

  private val batchSizeHistogram: Histogram =
    Kamon.histogram("dataflow.source.batch.size.records").withoutTags()

  private val batchLatencyHistogram: Histogram =
    Kamon.histogram("dataflow.source.batch.latency.ms").withoutTags()

  // ============================================
  // ERROR METRICS
  // ============================================

  private val errorsCounter: Counter =
    Kamon.counter("dataflow.source.errors").withoutTags()

  private val connectionErrorsCounter: Counter =
    Kamon.counter("dataflow.source.connection.errors").withoutTags()

  private val parseErrorsCounter: Counter =
    Kamon.counter("dataflow.source.parse.errors").withoutTags()

  // ============================================
  // SOURCE HEALTH METRICS
  // ============================================

  private val sourceHealthGauge: Gauge =
    Kamon.gauge("dataflow.source.healthy").withoutTags()

  private val sourceOffsetGauge: Gauge =
    Kamon.gauge("dataflow.source.offset").withoutTags()

  private val pollIntervalGauge: Gauge =
    Kamon.gauge("dataflow.source.poll.interval.ms").withoutTags()

  // ============================================
  // KAFKA-SPECIFIC METRICS
  // ============================================

  private val kafkaLagGauge: Gauge =
    Kamon.gauge("dataflow.source.kafka.lag").withoutTags()

  private val kafkaOffsetGauge: Gauge =
    Kamon.gauge("dataflow.source.kafka.offset").withoutTags()

  private val kafkaMessagesConsumedCounter: Counter =
    Kamon.counter("dataflow.source.kafka.messages.consumed").withoutTags()

  // ============================================
  // API-SPECIFIC METRICS
  // ============================================

  private val apiRequestCounter: Counter =
    Kamon.counter("dataflow.source.api.requests").withoutTags()

  private val apiResponseTimeHistogram: Histogram =
    Kamon.histogram("dataflow.source.api.response.time.ms").withoutTags()

  private val apiResponseStatusCounter: Counter =
    Kamon.counter("dataflow.source.api.response.status").withoutTags()

  private val apiPaginationPageGauge: Gauge =
    Kamon.gauge("dataflow.source.api.pagination.page").withoutTags()

  // ============================================
  // DATABASE-SPECIFIC METRICS
  // ============================================

  private val dbQueryCounter: Counter =
    Kamon.counter("dataflow.source.db.queries").withoutTags()

  private val dbQueryTimeHistogram: Histogram =
    Kamon.histogram("dataflow.source.db.query.time.ms").withoutTags()

  private val dbRowsReadCounter: Counter =
    Kamon.counter("dataflow.source.db.rows.read").withoutTags()

  private val dbConnectionPoolSizeGauge: Gauge =
    Kamon.gauge("dataflow.source.db.connection.pool.size").withoutTags()

  private val dbIncrementalWatermarkGauge: Gauge =
    Kamon.gauge("dataflow.source.db.incremental.watermark").withoutTags()

  // ============================================
  // FILE-SPECIFIC METRICS
  // ============================================

  private val fileLinesReadCounter: Counter =
    Kamon.counter("dataflow.source.file.lines.read").withoutTags()

  private val fileSizeBytesGauge: Gauge =
    Kamon.gauge("dataflow.source.file.size.bytes").withoutTags()

  private val fileReadProgressGauge: Gauge =
    Kamon.gauge("dataflow.source.file.read.progress").withoutTags()

  // ============================================
  // RECORD METRICS
  // ============================================

  /**
   * Record that records were read from the source.
   *
   * @param pipelineId The pipeline ID
   * @param sourceType The source type (file, kafka, api, database)
   * @param recordCount Number of records read
   */
  def recordRecordsRead(
    pipelineId: String,
    sourceType: String,
    recordCount: Long,
  ): Unit = {
    log.trace("msg=Record records read pipelineId={} sourceType={} count={}", pipelineId, sourceType, recordCount)

    recordsReadCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("source_type", sourceType)
      .increment(recordCount)
  }

  /**
   * Record bytes read from the source.
   *
   * @param pipelineId The pipeline ID
   * @param sourceType The source type
   * @param bytesCount Number of bytes read
   */
  def recordBytesRead(
    pipelineId: String,
    sourceType: String,
    bytesCount: Long,
  ): Unit = {
    log.trace("msg=Record bytes read pipelineId={} sourceType={} bytes={}", pipelineId, sourceType, bytesCount)

    bytesReadCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("source_type", sourceType)
      .increment(bytesCount)
  }

  /**
   * Record records that were skipped (e.g., due to filtering or resume offset).
   *
   * @param pipelineId The pipeline ID
   * @param sourceType The source type
   * @param recordCount Number of records skipped
   */
  def recordRecordsSkipped(
    pipelineId: String,
    sourceType: String,
    recordCount: Long,
  ): Unit = {
    log.debug("msg=Record records skipped pipelineId={} sourceType={} count={}", pipelineId, sourceType, recordCount)

    recordsSkippedCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("source_type", sourceType)
      .increment(recordCount)
  }

  // ============================================
  // BATCH METRICS
  // ============================================

  /**
   * Record that a batch was sent to the pipeline.
   *
   * @param pipelineId The pipeline ID
   * @param sourceType The source type
   * @param recordCount Number of records in the batch
   * @param latencyMs Time from first record read to batch sent
   */
  def recordBatchSent(
    pipelineId: String,
    sourceType: String,
    recordCount: Int,
    latencyMs: Long,
  ): Unit = {
    log.debug(
      "msg=Record batch sent pipelineId={} sourceType={} records={} latencyMs={}",
      pipelineId,
      sourceType,
      recordCount,
      latencyMs,
    )

    batchesSentCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("source_type", sourceType)
      .increment()

    batchSizeHistogram
      .withTag("pipeline_id", pipelineId)
      .withTag("source_type", sourceType)
      .record(recordCount.toLong)

    batchLatencyHistogram
      .withTag("pipeline_id", pipelineId)
      .withTag("source_type", sourceType)
      .record(latencyMs)
  }

  // ============================================
  // ERROR METRICS
  // ============================================

  /**
   * Record a source error.
   *
   * @param pipelineId The pipeline ID
   * @param sourceType The source type
   * @param errorType The type of error (connection, parse, timeout, etc.)
   */
  def recordError(
    pipelineId: String,
    sourceType: String,
    errorType: String,
  ): Unit = {
    log.warn("msg=Record source error pipelineId={} sourceType={} errorType={}", pipelineId, sourceType, errorType)

    errorsCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("source_type", sourceType)
      .withTag("error_type", errorType)
      .increment()
  }

  /**
   * Record a connection error.
   *
   * @param pipelineId The pipeline ID
   * @param sourceType The source type
   */
  def recordConnectionError(
    pipelineId: String,
    sourceType: String,
  ): Unit = {
    log.warn("msg=Record connection error pipelineId={} sourceType={}", pipelineId, sourceType)

    connectionErrorsCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("source_type", sourceType)
      .increment()
  }

  /**
   * Record a parse error.
   *
   * @param pipelineId The pipeline ID
   * @param sourceType The source type
   * @param format The data format (csv, json, etc.)
   */
  def recordParseError(
    pipelineId: String,
    sourceType: String,
    format: String,
  ): Unit = {
    log.warn("msg=Record parse error pipelineId={} sourceType={} format={}", pipelineId, sourceType, format)

    parseErrorsCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("source_type", sourceType)
      .withTag("format", format)
      .increment()
  }

  // ============================================
  // HEALTH METRICS
  // ============================================

  /**
   * Update source health status.
   *
   * @param pipelineId The pipeline ID
   * @param sourceType The source type
   * @param isHealthy Whether the source is healthy (1.0) or not (0.0)
   */
  def updateHealth(
    pipelineId: String,
    sourceType: String,
    isHealthy: Boolean,
  ): Unit = {
    log.trace("msg=Update source health pipelineId={} sourceType={} healthy={}", pipelineId, sourceType, isHealthy)

    sourceHealthGauge
      .withTag("pipeline_id", pipelineId)
      .withTag("source_type", sourceType)
      .update(if (isHealthy) 1.0 else 0.0)
  }

  /**
   * Update source offset.
   *
   * @param pipelineId The pipeline ID
   * @param sourceType The source type
   * @param offset The current offset
   */
  def updateOffset(
    pipelineId: String,
    sourceType: String,
    offset: Long,
  ): Unit = {
    log.trace("msg=Update source offset pipelineId={} sourceType={} offset={}", pipelineId, sourceType, offset)

    sourceOffsetGauge
      .withTag("pipeline_id", pipelineId)
      .withTag("source_type", sourceType)
      .update(offset.toDouble)
  }

  // ============================================
  // KAFKA-SPECIFIC METRICS
  // ============================================

  /**
   * Update Kafka consumer lag.
   *
   * @param pipelineId The pipeline ID
   * @param topic The Kafka topic
   * @param partition The partition number
   * @param lag The consumer lag (high watermark - current offset)
   */
  def updateKafkaLag(
    pipelineId: String,
    topic: String,
    partition: Int,
    lag: Long,
  ): Unit = {
    log.debug("msg=Update Kafka lag pipelineId={} topic={} partition={} lag={}", pipelineId, topic, partition, lag)

    kafkaLagGauge
      .withTag("pipeline_id", pipelineId)
      .withTag("topic", topic)
      .withTag("partition", partition.toString)
      .update(lag.toDouble)
  }

  /**
   * Update Kafka consumer offset.
   *
   * @param pipelineId The pipeline ID
   * @param topic The Kafka topic
   * @param partition The partition number
   * @param offset The current offset
   */
  def updateKafkaOffset(
    pipelineId: String,
    topic: String,
    partition: Int,
    offset: Long,
  ): Unit = {
    log.trace("msg=Update Kafka offset pipelineId={} topic={} partition={} offset={}", pipelineId, topic, partition, offset)

    kafkaOffsetGauge
      .withTag("pipeline_id", pipelineId)
      .withTag("topic", topic)
      .withTag("partition", partition.toString)
      .update(offset.toDouble)
  }

  /**
   * Record Kafka message consumption.
   *
   * @param pipelineId The pipeline ID
   * @param topic The Kafka topic
   * @param messageCount Number of messages consumed
   */
  def recordKafkaMessagesConsumed(
    pipelineId: String,
    topic: String,
    messageCount: Long,
  ): Unit = {
    log.trace("msg=Record Kafka messages consumed pipelineId={} topic={} count={}", pipelineId, topic, messageCount)

    kafkaMessagesConsumedCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("topic", topic)
      .increment(messageCount)
  }

  // ============================================
  // API-SPECIFIC METRICS
  // ============================================

  /**
   * Record an API request.
   *
   * @param pipelineId The pipeline ID
   * @param apiUrl The API URL
   * @param method The HTTP method
   * @param responseTimeMs Response time in milliseconds
   * @param statusCode HTTP status code
   */
  def recordApiRequest(
    pipelineId: String,
    apiUrl: String,
    method: String,
    responseTimeMs: Long,
    statusCode: Int,
  ): Unit = {
    log.debug(
      "msg=Record API request pipelineId={} url={} method={} responseTimeMs={} status={}",
      pipelineId,
      apiUrl,
      method,
      responseTimeMs,
      statusCode,
    )

    apiRequestCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("method", method)
      .increment()

    apiResponseTimeHistogram
      .withTag("pipeline_id", pipelineId)
      .withTag("method", method)
      .record(responseTimeMs)

    apiResponseStatusCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("method", method)
      .withTag("status", statusCode.toString)
      .increment()
  }

  /**
   * Update API pagination page.
   *
   * @param pipelineId The pipeline ID
   * @param page The current page number
   */
  def updateApiPaginationPage(
    pipelineId: String,
    page: Int,
  ): Unit = {
    log.trace("msg=Update API pagination page pipelineId={} page={}", pipelineId, page)

    apiPaginationPageGauge
      .withTag("pipeline_id", pipelineId)
      .update(page.toDouble)
  }

  // ============================================
  // DATABASE-SPECIFIC METRICS
  // ============================================

  /**
   * Record a database query execution.
   *
   * @param pipelineId The pipeline ID
   * @param queryType The type of query (select, insert, etc.)
   * @param executionTimeMs Query execution time in milliseconds
   * @param rowCount Number of rows returned/affected
   */
  def recordDatabaseQuery(
    pipelineId: String,
    queryType: String,
    executionTimeMs: Long,
    rowCount: Int,
  ): Unit = {
    log.debug(
      "msg=Record database query pipelineId={} queryType={} executionTimeMs={} rowCount={}",
      pipelineId,
      queryType,
      executionTimeMs,
      rowCount,
    )

    dbQueryCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("query_type", queryType)
      .increment()

    dbQueryTimeHistogram
      .withTag("pipeline_id", pipelineId)
      .withTag("query_type", queryType)
      .record(executionTimeMs)

    dbRowsReadCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("query_type", queryType)
      .increment(rowCount.toLong)
  }

  /**
   * Update database incremental watermark.
   *
   * @param pipelineId The pipeline ID
   * @param watermarkValue The current watermark value (timestamp or ID)
   */
  def updateDatabaseWatermark(
    pipelineId: String,
    watermarkValue: Long,
  ): Unit = {
    log.debug("msg=Update database watermark pipelineId={} watermark={}", pipelineId, watermarkValue)

    dbIncrementalWatermarkGauge
      .withTag("pipeline_id", pipelineId)
      .update(watermarkValue.toDouble)
  }

  // ============================================
  // FILE-SPECIFIC METRICS
  // ============================================

  /**
   * Record file lines read.
   *
   * @param pipelineId The pipeline ID
   * @param filePath The file path
   * @param lineCount Number of lines read
   */
  def recordFileLinesRead(
    pipelineId: String,
    filePath: String,
    lineCount: Long,
  ): Unit = {
    log.trace("msg=Record file lines read pipelineId={} file={} lines={}", pipelineId, filePath, lineCount)

    fileLinesReadCounter
      .withTag("pipeline_id", pipelineId)
      .increment(lineCount)
  }

  /**
   * Update file size.
   *
   * @param pipelineId The pipeline ID
   * @param sizeBytes File size in bytes
   */
  def updateFileSize(
    pipelineId: String,
    sizeBytes: Long,
  ): Unit = {
    log.trace("msg=Update file size pipelineId={} sizeBytes={}", pipelineId, sizeBytes)

    fileSizeBytesGauge
      .withTag("pipeline_id", pipelineId)
      .update(sizeBytes.toDouble)
  }

  /**
   * Update file read progress (0.0 to 1.0).
   *
   * @param pipelineId The pipeline ID
   * @param progress Progress as percentage (0.0 to 1.0)
   */
  def updateFileReadProgress(
    pipelineId: String,
    progress: Double,
  ): Unit = {
    log.trace("msg=Update file read progress pipelineId={} progress={}", pipelineId, progress)

    fileReadProgressGauge
      .withTag("pipeline_id", pipelineId)
      .update(progress)
  }
}

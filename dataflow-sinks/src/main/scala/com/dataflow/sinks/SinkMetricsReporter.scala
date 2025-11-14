package com.dataflow.sinks

import kamon.Kamon
import kamon.metric.{Counter, Gauge, Histogram}
import org.slf4j.{Logger, LoggerFactory}

/**
 * SinkMetricsReporter publishes sink connector metrics to Kamon for monitoring.
 *
 * Metrics Published:
 * - Records written counters
 * - Bytes written counters
 * - Batches written counters
 * - Write latency
 * - Error counters
 * - Sink-specific metrics (Kafka acks, DB commits, file sizes)
 * - Sink health indicators
 *
 * Usage:
 * {{{
 *   SinkMetricsReporter.recordWrite(pipelineId, sinkType, recordCount, bytesWritten, latencyMs)
 *   SinkMetricsReporter.recordBatchWritten(pipelineId, sinkType, batchSize)
 *   SinkMetricsReporter.recordError(pipelineId, sinkType, errorType)
 * }}}
 */
object SinkMetricsReporter {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  // ============================================
  // RECORD WRITE METRICS
  // ============================================

  private val recordsWrittenCounter: Counter =
    Kamon.counter("dataflow.sink.records.written").withoutTags()

  private val bytesWrittenCounter: Counter =
    Kamon.counter("dataflow.sink.bytes.written").withoutTags()

  private val recordsFailedCounter: Counter =
    Kamon.counter("dataflow.sink.records.failed").withoutTags()

  // ============================================
  // BATCH WRITE METRICS
  // ============================================

  private val batchesWrittenCounter: Counter =
    Kamon.counter("dataflow.sink.batches.written").withoutTags()

  private val batchSizeHistogram: Histogram =
    Kamon.histogram("dataflow.sink.batch.size.records").withoutTags()

  private val batchLatencyHistogram: Histogram =
    Kamon.histogram("dataflow.sink.batch.latency.ms").withoutTags()

  private val writeLatencyHistogram: Histogram =
    Kamon.histogram("dataflow.sink.write.latency.ms").withoutTags()

  // ============================================
  // ERROR METRICS
  // ============================================

  private val errorsCounter: Counter =
    Kamon.counter("dataflow.sink.errors").withoutTags()

  private val connectionErrorsCounter: Counter =
    Kamon.counter("dataflow.sink.connection.errors").withoutTags()

  private val writeErrorsCounter: Counter =
    Kamon.counter("dataflow.sink.write.errors").withoutTags()

  private val retriesCounter: Counter =
    Kamon.counter("dataflow.sink.retries").withoutTags()

  // ============================================
  // SINK HEALTH METRICS
  // ============================================

  private val sinkHealthGauge: Gauge =
    Kamon.gauge("dataflow.sink.healthy").withoutTags()

  private val bufferSizeGauge: Gauge =
    Kamon.gauge("dataflow.sink.buffer.size").withoutTags()

  private val backpressureGauge: Gauge =
    Kamon.gauge("dataflow.sink.backpressure").withoutTags()

  // ============================================
  // KAFKA-SPECIFIC METRICS
  // ============================================

  private val kafkaMessagesProducedCounter: Counter =
    Kamon.counter("dataflow.sink.kafka.messages.produced").withoutTags()

  private val kafkaAcksReceivedCounter: Counter =
    Kamon.counter("dataflow.sink.kafka.acks.received").withoutTags()

  private val kafkaProduceLatencyHistogram: Histogram =
    Kamon.histogram("dataflow.sink.kafka.produce.latency.ms").withoutTags()

  // ============================================
  // DATABASE-SPECIFIC METRICS
  // ============================================

  private val dbInsertsCounter: Counter =
    Kamon.counter("dataflow.sink.db.inserts").withoutTags()

  private val dbUpdatesCounter: Counter =
    Kamon.counter("dataflow.sink.db.updates").withoutTags()

  private val dbCommitsCounter: Counter =
    Kamon.counter("dataflow.sink.db.commits").withoutTags()

  private val dbCommitLatencyHistogram: Histogram =
    Kamon.histogram("dataflow.sink.db.commit.latency.ms").withoutTags()

  private val dbConnectionPoolSizeGauge: Gauge =
    Kamon.gauge("dataflow.sink.db.connection.pool.size").withoutTags()

  // ============================================
  // FILE-SPECIFIC METRICS
  // ============================================

  private val fileLinesWrittenCounter: Counter =
    Kamon.counter("dataflow.sink.file.lines.written").withoutTags()

  private val filesBytesWrittenCounter: Counter =
    Kamon.counter("dataflow.sink.file.bytes.written").withoutTags()

  private val filesCreatedCounter: Counter =
    Kamon.counter("dataflow.sink.file.files.created").withoutTags()

  private val fileFlushesCounter: Counter =
    Kamon.counter("dataflow.sink.file.flushes").withoutTags()

  // ============================================
  // ELASTICSEARCH-SPECIFIC METRICS
  // ============================================

  private val esIndexedCounter: Counter =
    Kamon.counter("dataflow.sink.elasticsearch.indexed").withoutTags()

  private val esBulkRequestsCounter: Counter =
    Kamon.counter("dataflow.sink.elasticsearch.bulk.requests").withoutTags()

  private val esBulkLatencyHistogram: Histogram =
    Kamon.histogram("dataflow.sink.elasticsearch.bulk.latency.ms").withoutTags()

  // ============================================
  // RECORD WRITE METRICS
  // ============================================

  /**
   * Record a write operation.
   *
   * @param pipelineId The pipeline ID
   * @param sinkType The sink type (file, kafka, database, elasticsearch)
   * @param recordCount Number of records written
   * @param bytesWritten Number of bytes written
   * @param latencyMs Write latency in milliseconds
   */
  def recordWrite(
    pipelineId: String,
    sinkType: String,
    recordCount: Long,
    bytesWritten: Long,
    latencyMs: Long
  ): Unit = {
    log.trace(
      "msg=Record write pipelineId={} sinkType={} records={} bytes={} latencyMs={}",
      pipelineId,
      sinkType,
      recordCount,
      bytesWritten,
      latencyMs
    )

    recordsWrittenCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("sink_type", sinkType)
      .increment(recordCount)

    bytesWrittenCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("sink_type", sinkType)
      .increment(bytesWritten)

    writeLatencyHistogram
      .withTag("pipeline_id", pipelineId)
      .withTag("sink_type", sinkType)
      .record(latencyMs)
  }

  /**
   * Record a batch write.
   *
   * @param pipelineId The pipeline ID
   * @param sinkType The sink type
   * @param batchSize Number of records in the batch
   * @param latencyMs Batch write latency
   */
  def recordBatchWritten(
    pipelineId: String,
    sinkType: String,
    batchSize: Int,
    latencyMs: Long
  ): Unit = {
    log.debug("msg=Record batch written pipelineId={} sinkType={} size={} latencyMs={}", pipelineId, sinkType, batchSize, latencyMs)

    batchesWrittenCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("sink_type", sinkType)
      .increment()

    batchSizeHistogram
      .withTag("pipeline_id", pipelineId)
      .withTag("sink_type", sinkType)
      .record(batchSize.toLong)

    batchLatencyHistogram
      .withTag("pipeline_id", pipelineId)
      .withTag("sink_type", sinkType)
      .record(latencyMs)
  }

  /**
   * Record failed records.
   *
   * @param pipelineId The pipeline ID
   * @param sinkType The sink type
   * @param failedCount Number of records that failed to write
   */
  def recordFailed(
    pipelineId: String,
    sinkType: String,
    failedCount: Long
  ): Unit = {
    log.warn("msg=Record failed writes pipelineId={} sinkType={} count={}", pipelineId, sinkType, failedCount)

    recordsFailedCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("sink_type", sinkType)
      .increment(failedCount)
  }

  // ============================================
  // ERROR METRICS
  // ============================================

  /**
   * Record a sink error.
   *
   * @param pipelineId The pipeline ID
   * @param sinkType The sink type
   * @param errorType The type of error
   */
  def recordError(
    pipelineId: String,
    sinkType: String,
    errorType: String
  ): Unit = {
    log.warn("msg=Record sink error pipelineId={} sinkType={} errorType={}", pipelineId, sinkType, errorType)

    errorsCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("sink_type", sinkType)
      .withTag("error_type", errorType)
      .increment()
  }

  /**
   * Record a connection error.
   *
   * @param pipelineId The pipeline ID
   * @param sinkType The sink type
   */
  def recordConnectionError(
    pipelineId: String,
    sinkType: String
  ): Unit = {
    log.warn("msg=Record connection error pipelineId={} sinkType={}", pipelineId, sinkType)

    connectionErrorsCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("sink_type", sinkType)
      .increment()
  }

  /**
   * Record a write error.
   *
   * @param pipelineId The pipeline ID
   * @param sinkType The sink type
   */
  def recordWriteError(
    pipelineId: String,
    sinkType: String
  ): Unit = {
    log.warn("msg=Record write error pipelineId={} sinkType={}", pipelineId, sinkType)

    writeErrorsCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("sink_type", sinkType)
      .increment()
  }

  /**
   * Record a retry attempt.
   *
   * @param pipelineId The pipeline ID
   * @param sinkType The sink type
   * @param retryCount The retry attempt number
   */
  def recordRetry(
    pipelineId: String,
    sinkType: String,
    retryCount: Int
  ): Unit = {
    log.debug("msg=Record retry pipelineId={} sinkType={} retryCount={}", pipelineId, sinkType, retryCount)

    retriesCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("sink_type", sinkType)
      .increment()
  }

  // ============================================
  // HEALTH METRICS
  // ============================================

  /**
   * Update sink health status.
   *
   * @param pipelineId The pipeline ID
   * @param sinkType The sink type
   * @param isHealthy Whether the sink is healthy
   */
  def updateHealth(
    pipelineId: String,
    sinkType: String,
    isHealthy: Boolean
  ): Unit = {
    log.trace("msg=Update sink health pipelineId={} sinkType={} healthy={}", pipelineId, sinkType, isHealthy)

    sinkHealthGauge
      .withTag("pipeline_id", pipelineId)
      .withTag("sink_type", sinkType)
      .update(if (isHealthy) 1.0 else 0.0)
  }

  /**
   * Update buffer size.
   *
   * @param pipelineId The pipeline ID
   * @param sinkType The sink type
   * @param size Current buffer size
   */
  def updateBufferSize(
    pipelineId: String,
    sinkType: String,
    size: Int
  ): Unit = {
    log.trace("msg=Update buffer size pipelineId={} sinkType={} size={}", pipelineId, sinkType, size)

    bufferSizeGauge
      .withTag("pipeline_id", pipelineId)
      .withTag("sink_type", sinkType)
      .update(size.toDouble)
  }

  /**
   * Update backpressure status.
   *
   * @param pipelineId The pipeline ID
   * @param sinkType The sink type
   * @param isBackpressured Whether backpressure is active
   */
  def updateBackpressure(
    pipelineId: String,
    sinkType: String,
    isBackpressured: Boolean
  ): Unit = {
    log.debug("msg=Update backpressure pipelineId={} sinkType={} backpressured={}", pipelineId, sinkType, isBackpressured)

    backpressureGauge
      .withTag("pipeline_id", pipelineId)
      .withTag("sink_type", sinkType)
      .update(if (isBackpressured) 1.0 else 0.0)
  }

  // ============================================
  // KAFKA-SPECIFIC METRICS
  // ============================================

  /**
   * Record Kafka message production.
   *
   * @param pipelineId The pipeline ID
   * @param topic The Kafka topic
   * @param messageCount Number of messages produced
   * @param latencyMs Production latency
   */
  def recordKafkaProduced(
    pipelineId: String,
    topic: String,
    messageCount: Long,
    latencyMs: Long
  ): Unit = {
    log.trace("msg=Record Kafka produced pipelineId={} topic={} count={} latencyMs={}", pipelineId, topic, messageCount, latencyMs)

    kafkaMessagesProducedCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("topic", topic)
      .increment(messageCount)

    kafkaProduceLatencyHistogram
      .withTag("pipeline_id", pipelineId)
      .withTag("topic", topic)
      .record(latencyMs)
  }

  /**
   * Record Kafka acks received.
   *
   * @param pipelineId The pipeline ID
   * @param topic The Kafka topic
   * @param ackCount Number of acks received
   */
  def recordKafkaAcks(
    pipelineId: String,
    topic: String,
    ackCount: Long
  ): Unit = {
    log.trace("msg=Record Kafka acks pipelineId={} topic={} count={}", pipelineId, topic, ackCount)

    kafkaAcksReceivedCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("topic", topic)
      .increment(ackCount)
  }

  // ============================================
  // DATABASE-SPECIFIC METRICS
  // ============================================

  /**
   * Record database inserts.
   *
   * @param pipelineId The pipeline ID
   * @param table The database table
   * @param insertCount Number of inserts
   * @param commitLatencyMs Commit latency
   */
  def recordDatabaseInsert(
    pipelineId: String,
    table: String,
    insertCount: Long,
    commitLatencyMs: Long
  ): Unit = {
    log.debug("msg=Record DB inserts pipelineId={} table={} count={} latencyMs={}", pipelineId, table, insertCount, commitLatencyMs)

    dbInsertsCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("table", table)
      .increment(insertCount)

    dbCommitLatencyHistogram
      .withTag("pipeline_id", pipelineId)
      .withTag("table", table)
      .record(commitLatencyMs)
  }

  /**
   * Record database commit.
   *
   * @param pipelineId The pipeline ID
   * @param table The database table
   */
  def recordDatabaseCommit(
    pipelineId: String,
    table: String
  ): Unit = {
    log.trace("msg=Record DB commit pipelineId={} table={}", pipelineId, table)

    dbCommitsCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("table", table)
      .increment()
  }

  // ============================================
  // FILE-SPECIFIC METRICS
  // ============================================

  /**
   * Record file write.
   *
   * @param pipelineId The pipeline ID
   * @param filePath The file path
   * @param linesWritten Number of lines written
   * @param bytesWritten Number of bytes written
   */
  def recordFileWrite(
    pipelineId: String,
    filePath: String,
    linesWritten: Long,
    bytesWritten: Long
  ): Unit = {
    log.trace("msg=Record file write pipelineId={} file={} lines={} bytes={}", pipelineId, filePath, linesWritten, bytesWritten)

    fileLinesWrittenCounter
      .withTag("pipeline_id", pipelineId)
      .increment(linesWritten)

    filesBytesWrittenCounter
      .withTag("pipeline_id", pipelineId)
      .increment(bytesWritten)
  }

  /**
   * Record file created.
   *
   * @param pipelineId The pipeline ID
   * @param filePath The file path
   */
  def recordFileCreated(
    pipelineId: String,
    filePath: String
  ): Unit = {
    log.info("msg=Record file created pipelineId={} file={}", pipelineId, filePath)

    filesCreatedCounter
      .withTag("pipeline_id", pipelineId)
      .increment()
  }

  // ============================================
  // ELASTICSEARCH-SPECIFIC METRICS
  // ============================================

  /**
   * Record Elasticsearch bulk request.
   *
   * @param pipelineId The pipeline ID
   * @param index The ES index
   * @param documentCount Number of documents indexed
   * @param bulkLatencyMs Bulk request latency
   */
  def recordElasticsearchBulk(
    pipelineId: String,
    index: String,
    documentCount: Long,
    bulkLatencyMs: Long
  ): Unit = {
    log.debug("msg=Record ES bulk pipelineId={} index={} count={} latencyMs={}", pipelineId, index, documentCount, bulkLatencyMs)

    esIndexedCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("index", index)
      .increment(documentCount)

    esBulkRequestsCounter
      .withTag("pipeline_id", pipelineId)
      .withTag("index", index)
      .increment()

    esBulkLatencyHistogram
      .withTag("pipeline_id", pipelineId)
      .withTag("index", index)
      .record(bulkLatencyMs)
  }
}

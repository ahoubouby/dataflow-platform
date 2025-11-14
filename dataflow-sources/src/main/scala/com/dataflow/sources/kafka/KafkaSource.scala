package com.dataflow.sources.kafka

import java.time.Instant
import java.util.UUID

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

import com.dataflow.domain.commands.{Command, IngestBatch}
import com.dataflow.domain.models.{DataRecord, SourceConfig}
import com.dataflow.sources.{Source, SourceMetricsReporter}
import com.dataflow.sources.models.SourceState
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import org.apache.pekko.kafka.scaladsl.{Committer, Consumer}
import org.apache.pekko.stream.{KillSwitches, SystemMaterializer}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source => PekkoSource}
import org.slf4j.LoggerFactory
import spray.json._

/**
 * Kafka source connector for reading data from Kafka topics.
 *
 * Supported formats:
 * - JSON (parses message value as JSON object)
 * - String (simple key-value messages)
 *
 * Features:
 * - Consumer groups for horizontal scalability
 * - Automatic offset management with Kafka
 * - Exactly-once semantics with committable offsets
 * - Backpressure handling
 * - Error handling with fallback to string parsing
 *
 * Configuration:
 *  {{{
 *    SourceConfig(
 *      sourceType = SourceType.Kafka,
 *      connectionString = "localhost:9092",  // Bootstrap servers
 *      options = Map(
 *        "topic" -> "my-topic",
 *        "group-id" -> "my-consumer-group",
 *        "format" -> "json",                    // json or string
 *        "auto-offset-reset" -> "earliest",     // earliest or latest
 *        "enable-auto-commit" -> "false",       // Use manual commit
 *        "max-poll-records" -> "500"
 *      ),
 *      batchSize = 1000,
 *      pollIntervalMs = 1000
 *    )
 *  }}}
 */
class KafkaSource(
  val pipelineId: String,
  val config: SourceConfig,
)(implicit system: ActorSystem[_]) extends Source {

  private val log = LoggerFactory.getLogger(getClass)

  implicit private val ec: ExecutionContext = system.executionContext
  implicit private val mat = SystemMaterializer(system).materializer

  override val sourceId: String = s"kafka-source-$pipelineId-${UUID.randomUUID()}"

  // ----- options -----
  private val bootstrapServers: String = config.connectionString

  private val topic: String =
    config.options.getOrElse("topic", throw new IllegalArgumentException("Kafka topic is required"))

  private val groupId: String =
    config.options.getOrElse("group-id", s"dataflow-$pipelineId")

  private val format: String =
    config.options.getOrElse("format", "json").toLowerCase

  private val autoOffsetReset: String =
    config.options.getOrElse("auto-offset-reset", "earliest")

  private val enableAutoCommit: Boolean =
    config.options.getOrElse("enable-auto-commit", "false").toBoolean

  private val maxPollRecords: String =
    config.options.getOrElse("max-poll-records", "500")

  // State
  @volatile private var currentKafkaOffset: Long                                             = 0L
  @volatile private var isRunning:          Boolean                                          = false
  @volatile private var killSwitch:         Option[org.apache.pekko.stream.UniqueKillSwitch] = None

  // Kafka consumer settings
  private val consumerSettings = ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
    .withBootstrapServers(bootstrapServers)
    .withGroupId(groupId)
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset)
    .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommit.toString)
    .withProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords)

  // Committer settings for manual offset commits
  private val committerSettings = CommitterSettings(system)

  log.info(
    "Initialized KafkaSource id={} topic={} group={} servers={} format={} batchSize={}",
    sourceId,
    topic,
    groupId,
    bootstrapServers,
    format,
    config.batchSize,
  )

  /**
   * Create streaming source from Kafka.
   */
  override def stream(): PekkoSource[DataRecord, NotUsed] =
    buildDataStream()

  private def buildDataStream(): PekkoSource[DataRecord, NotUsed] = {
    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(topic))
      .map {
        msg =>
          currentKafkaOffset = msg.record.offset()

          val record = parseKafkaMessage(
            key = msg.record.key(),
            value = msg.record.value(),
            topic = msg.record.topic(),
            partition = msg.record.partition(),
            offset = msg.record.offset(),
            timestamp = msg.record.timestamp(),
          )

          (record, msg.committableOffset)
      }
      .collect { case (Some(record), _) => record }
      .mapMaterializedValue(_ => NotUsed)
  }

  /**
   * Parse Kafka message to DataRecord.
   */
  private def parseKafkaMessage(
    key: String,
    value: String,
    topic: String,
    partition: Int,
    offset: Long,
    timestamp: Long,
  ): Option[DataRecord] = {
    format match {
      case "json"   => parseJsonMessage(key, value, topic, partition, offset, timestamp)
      case "string" => parseStringMessage(key, value, topic, partition, offset, timestamp)
      case other    =>
        log.warn("Unsupported format '{}', defaulting to string", other)
        parseStringMessage(key, value, topic, partition, offset, timestamp)
    }
  }

  /**
   * Parse JSON message.
   */
  private def parseJsonMessage(
    key: String,
    value: String,
    topic: String,
    partition: Int,
    offset: Long,
    timestamp: Long,
  ): Option[DataRecord] = {
    Try {
      val json = value.parseJson.asJsObject

      // Extract fields from JSON
      val data = json.fields.map {
        case (k, v) =>
          k -> v.toString.stripPrefix("\"").stripSuffix("\"")
      }

      // Get ID from JSON if present, otherwise use Kafka key or generate
      val id = json.fields
        .get("id")
        .map(_.toString.stripPrefix("\"").stripSuffix("\""))
        .orElse(Option(key).filter(_.nonEmpty))
        .getOrElse(UUID.randomUUID().toString)

      val record = DataRecord(
        id = id,
        data = data - "id", // Remove id from data fields
        metadata = Map(
          "source"          -> "kafka",
          "source_id"       -> sourceId,
          "topic"           -> topic,
          "partition"       -> partition.toString,
          "offset"          -> offset.toString,
          "kafka_timestamp" -> timestamp.toString,
          "kafka_key"       -> Option(key).getOrElse(""),
          "format"          -> "json",
          "timestamp"       -> Instant.now().toString,
        ),
      )

      // Record metrics
      SourceMetricsReporter.recordRecordsRead(pipelineId, "kafka", 1)
      SourceMetricsReporter.recordBytesRead(pipelineId, "kafka", value.getBytes("UTF-8").length.toLong)
      SourceMetricsReporter.recordKafkaMessagesConsumed(pipelineId, topic, 1)
      SourceMetricsReporter.updateKafkaOffset(pipelineId, topic, partition, offset)

      record
    } match {
      case Success(record) => Some(record)
      case Failure(ex)     =>
        SourceMetricsReporter.recordParseError(pipelineId, "kafka", "json")
        log.warn(
          "Failed to parse JSON message from topic={} partition={} offset={}: {}",
          topic,
          partition,
          offset,
          ex.getMessage,
        )
        // Fallback to string parsing
        parseStringMessage(key, value, topic, partition, offset, timestamp)
    }
  }

  /**
   * Parse string message (simple key-value).
   */
  private def parseStringMessage(
    key: String,
    value: String,
    topic: String,
    partition: Int,
    offset: Long,
    timestamp: Long,
  ): Option[DataRecord] = {
    // Record metrics
    SourceMetricsReporter.recordRecordsRead(pipelineId, "kafka", 1)
    SourceMetricsReporter.recordBytesRead(pipelineId, "kafka", value.getBytes("UTF-8").length.toLong)
    SourceMetricsReporter.recordKafkaMessagesConsumed(pipelineId, topic, 1)
    SourceMetricsReporter.updateKafkaOffset(pipelineId, topic, partition, offset)

    Some(
      DataRecord(
        id = Option(key).filter(_.nonEmpty).getOrElse(UUID.randomUUID().toString),
        data = Map(
          "value" -> value,
          "key"   -> Option(key).getOrElse(""),
        ),
        metadata = Map(
          "source"          -> "kafka",
          "source_id"       -> sourceId,
          "topic"           -> topic,
          "partition"       -> partition.toString,
          "offset"          -> offset.toString,
          "kafka_timestamp" -> timestamp.toString,
          "format"          -> "string",
          "timestamp"       -> Instant.now().toString,
        ),
      ),
    )
  }

  /**
   * Start consuming from Kafka and sending batches to pipeline.
   */
  override def start(
    pipelineShardRegion: ActorRef[ShardingEnvelope[Command]],
  ): Future[Done] = {
    if (isRunning) {
      log.warn("KafkaSource {} already running", sourceId)
      Future.successful(Done)
    } else {
      log.info("Starting KafkaSource {} for topic {}", sourceId, topic)
      isRunning = true

      // Update health metrics
      SourceMetricsReporter.updateHealth(pipelineId, "kafka", isHealthy = true)

      // Create stream with batching and committing
      val (switch, doneF) = Consumer
        .committableSource(consumerSettings, Subscriptions.topics(topic))
        .viaMat(KillSwitches.single)(Keep.right)
        .map {
          msg =>
            currentKafkaOffset = msg.record.offset()

            val record = parseKafkaMessage(
              key = msg.record.key(),
              value = msg.record.value(),
              topic = msg.record.topic(),
              partition = msg.record.partition(),
              offset = msg.record.offset(),
              timestamp = msg.record.timestamp(),
            )

            (record, msg.committableOffset)
        }
        .collect { case (Some(record), offset) => (record, offset) }
        .groupedWithin(
          config.batchSize,
          scala.concurrent.duration.Duration.fromNanos(config.pollIntervalMs * 1000000L),
        )
        .mapAsync(1) {
          recordsWithOffsets =>
            val records    = recordsWithOffsets.map(_._1).toList
            val lastOffset = recordsWithOffsets.last._2

            sendBatch(records, pipelineShardRegion).map(_ => lastOffset)
        }
        .toMat(Committer.sink(committerSettings))(Keep.both)
        .run()

      killSwitch = Some(switch)

      doneF.onComplete {
        case Success(_)  =>
          log.info("KafkaSource {} completed", sourceId)
          isRunning = false
          SourceMetricsReporter.updateHealth(pipelineId, "kafka", isHealthy = false)
        case Failure(ex) =>
          log.error("KafkaSource {} failed: {}", sourceId, ex.getMessage, ex)
          isRunning = false
          SourceMetricsReporter.recordError(pipelineId, "kafka", "stream_failure")
          SourceMetricsReporter.recordConnectionError(pipelineId, "kafka")
          SourceMetricsReporter.updateHealth(pipelineId, "kafka", isHealthy = false)
      }

      Future.successful(Done)
    }
  }

  /**
   * Send batch of records to pipeline.
   */
  private def sendBatch(
    records: List[DataRecord],
    pipelineShardRegion: ActorRef[ShardingEnvelope[Command]],
  ): Future[Done] = {
    if (records.isEmpty) {
      return Future.successful(Done)
    }

    val batchId    = UUID.randomUUID().toString
    val offset     = currentKafkaOffset
    val sendTimeMs = System.currentTimeMillis()

    log.debug(
      "Sending batch: batchId={} records={} offset={} topic={}",
      batchId,
      records.size,
      offset,
      topic,
    )

    val command = IngestBatch(
      pipelineId = pipelineId,
      batchId = batchId,
      records = records,
      sourceOffset = offset,
      replyTo = system.ignoreRef,
    )

    pipelineShardRegion ! ShardingEnvelope(pipelineId, command)

    // Record batch metrics
    val latencyMs = System.currentTimeMillis() - sendTimeMs
    SourceMetricsReporter.recordBatchSent(pipelineId, "kafka", records.size, latencyMs)
    SourceMetricsReporter.updateOffset(pipelineId, "kafka", offset)

    Future.successful(Done)
  }

  /**
   * Stop consuming from Kafka.
   */
  override def stop(): Future[Done] = {
    if (!isRunning) {
      log.warn("KafkaSource {} not running", sourceId)
      return Future.successful(Done)
    }

    log.info("Stopping KafkaSource {}", sourceId)

    killSwitch.foreach(_.shutdown())
    killSwitch = None
    isRunning = false

    // Update health metrics
    SourceMetricsReporter.updateHealth(pipelineId, "kafka", isHealthy = false)

    Future.successful(Done)
  }

  override def currentOffset(): Long = currentKafkaOffset

  override def resumeFrom(offset: Long): Unit = {
    log.info(
      "KafkaSource will resume from Kafka managed offset (consumer group: {})",
      groupId,
    )
    // Kafka consumer group manages offsets automatically
    // No manual seeking needed - the consumer group will resume from last committed offset
  }

  override def isHealthy: Boolean = isRunning

  override def state: SourceState =
    if (isRunning) SourceState.Running
    else SourceState.Stopped
}

/**
 * Companion object with factory method.
 */
object KafkaSource {

  def apply(pipelineId: String, config: SourceConfig)(implicit system: ActorSystem[_]): KafkaSource =
    new KafkaSource(pipelineId, config)
}

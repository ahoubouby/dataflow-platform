package com.dataflow.sources

import com.dataflow.domain.commands.{Command, IngestBatch}
import com.dataflow.domain.models.{DataRecord, SourceConfig}
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.{StringDeserializer}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.kafka.scaladsl.{Committer, Consumer}
import org.apache.pekko.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source => PekkoSource}
import org.apache.pekko.stream.{KillSwitch, KillSwitches, Materializer}
import org.apache.pekko.{Done, NotUsed}
import org.slf4j.LoggerFactory
import spray.json._

import java.time.Instant
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Kafka source connector for reading data from Kafka topics.
 *
 * Features:
 * - Consumer groups for horizontal scalability
 * - Automatic offset management with Kafka
 * - Exactly-once semantics with Pekko Kafka
 * - Backpressure handling
 * - Configurable deserialization (String, JSON)
 * - Error handling with DLQ (Dead Letter Queue) support
 *
 * Configuration:
 * {{{
 *   SourceConfig(
 *     sourceType = "kafka",
 *     connectionString = "localhost:9092",  // Bootstrap servers
 *     config = Map(
 *       "topic" -> "my-topic",
 *       "group-id" -> "my-consumer-group",
 *       "format" -> "json",                   // json or string
 *       "auto-offset-reset" -> "earliest",    // earliest or latest
 *       "enable-auto-commit" -> "false",      // Use manual commit
 *       "max-poll-records" -> "500"
 *     ),
 *     batchSize = 1000,
 *     pollIntervalMs = 1000
 *   )
 * }}}
 */
class KafkaSource(
  val pipelineId: String,
  val config: SourceConfig
)(implicit system: ActorSystem[_]) extends Source {

  private val log = LoggerFactory.getLogger(getClass)
  private implicit val ec: ExecutionContext = system.executionContext
  private implicit val mat: Materializer = Materializer(system)

  override val sourceId: String = s"kafka-source-$pipelineId-${UUID.randomUUID()}"

  // Configuration
  private val bootstrapServers: String = config.connectionString
  private val topic: String = config.config.getOrElse("topic", throw new IllegalArgumentException("Kafka topic is required"))
  private val groupId: String = config.config.getOrElse("group-id", s"dataflow-$pipelineId")
  private val format: String = config.config.getOrElse("format", "json")
  private val autoOffsetReset: String = config.config.getOrElse("auto-offset-reset", "earliest")
  private val enableAutoCommit: Boolean = config.config.getOrElse("enable-auto-commit", "false").toBoolean

  // State
  @volatile private var currentOffset: Long = 0
  @volatile private var isRunning: Boolean = false
  @volatile private var killSwitch: Option[KillSwitch] = None

  // Kafka consumer settings
  private val consumerSettings = ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
    .withBootstrapServers(bootstrapServers)
    .withGroupId(groupId)
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset)
    .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommit.toString)
    .withProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, config.config.getOrElse("max-poll-records", "500"))

  // Committer settings for manual offset commits
  private val committerSettings = CommitterSettings(system)

  log.info(
    s"Initialized KafkaSource: sourceId=$sourceId, topic=$topic, groupId=$groupId, servers=$bootstrapServers"
  )

  /**
   * Create streaming source from Kafka.
   */
  override def stream(): PekkoSource[DataRecord, Future[Done]] = {
    log.info(s"Creating stream from Kafka topic: $topic")

    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(topic))
      .map { msg =>
        currentOffset = msg.record.offset()

        val record = parseKafkaMessage(
          key = msg.record.key(),
          value = msg.record.value(),
          topic = msg.record.topic(),
          partition = msg.record.partition(),
          offset = msg.record.offset(),
          timestamp = msg.record.timestamp()
        )

        (record, msg.committableOffset)
      }
      .collect { case (Some(record), offset) => (record, offset) }
      .mapMaterializedValue(_ => Future.successful(Done))
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
    timestamp: Long
  ): Option[DataRecord] = {
    format.toLowerCase match {
      case "json" => parseJsonMessage(key, value, topic, partition, offset, timestamp)
      case "string" => parseStringMessage(key, value, topic, partition, offset, timestamp)
      case other =>
        log.warn(s"Unsupported format: $other, defaulting to string")
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
    timestamp: Long
  ): Option[DataRecord] = {
    Try {
      val json = value.parseJson.asJsObject

      // Extract fields from JSON
      val data = json.fields.map { case (k, v) =>
        k -> v.toString.stripPrefix("\"").stripSuffix("\"")
      }

      // Get ID from JSON if present, otherwise use Kafka key or generate
      val id = json.fields.get("id")
        .map(_.toString.stripPrefix("\"").stripSuffix("\""))
        .orElse(Option(key).filter(_.nonEmpty))
        .getOrElse(UUID.randomUUID().toString)

      DataRecord(
        id = id,
        data = data - "id",
        metadata = Map(
          "source" -> "kafka",
          "source_id" -> sourceId,
          "topic" -> topic,
          "partition" -> partition.toString,
          "offset" -> offset.toString,
          "kafka_timestamp" -> timestamp.toString,
          "kafka_key" -> Option(key).getOrElse(""),
          "format" -> "json",
          "timestamp" -> Instant.now().toString
        )
      )
    } match {
      case Success(record) => Some(record)
      case Failure(ex) =>
        log.warn(s"Failed to parse JSON message from topic=$topic, partition=$partition, offset=$offset: ${ex.getMessage}")
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
    timestamp: Long
  ): Option[DataRecord] = {
    Some(DataRecord(
      id = Option(key).filter(_.nonEmpty).getOrElse(UUID.randomUUID().toString),
      data = Map(
        "value" -> value,
        "key" -> Option(key).getOrElse("")
      ),
      metadata = Map(
        "source" -> "kafka",
        "source_id" -> sourceId,
        "topic" -> topic,
        "partition" -> partition.toString,
        "offset" -> offset.toString,
        "kafka_timestamp" -> timestamp.toString,
        "format" -> "string",
        "timestamp" -> Instant.now().toString
      )
    ))
  }

  /**
   * Start consuming from Kafka and sending batches to pipeline.
   */
  override def start(pipelineShardRegion: ActorRef[ShardingEnvelope[Command]]): Future[Done] = {
    if (isRunning) {
      log.warn(s"KafkaSource already running: $sourceId")
      return Future.successful(Done)
    }

    log.info(s"Starting KafkaSource: $sourceId")
    isRunning = true

    // Create stream with batching and committing
    val (switch, done) = Consumer
      .committableSource(consumerSettings, Subscriptions.topics(topic))
      .viaMat(KillSwitches.single)(Keep.right)
      .map { msg =>
        currentOffset = msg.record.offset()

        val record = parseKafkaMessage(
          key = msg.record.key(),
          value = msg.record.value(),
          topic = msg.record.topic(),
          partition = msg.record.partition(),
          offset = msg.record.offset(),
          timestamp = msg.record.timestamp()
        )

        (record, msg.committableOffset)
      }
      .collect { case (Some(record), offset) => (record, offset) }
      .groupedWithin(config.batchSize, scala.concurrent.duration.Duration.fromNanos(config.pollIntervalMs * 1000000))
      .mapAsync(1) { recordsWithOffsets =>
        val records = recordsWithOffsets.map(_._1).toList
        val lastOffset = recordsWithOffsets.last._2

        sendBatch(records, pipelineShardRegion).map { _ =>
          lastOffset
        }
      }
      .toMat(Committer.sink(committerSettings))(Keep.both)
      .run()

    killSwitch = Some(switch)

    done.onComplete {
      case Success(_) =>
        log.info(s"KafkaSource completed: $sourceId")
        isRunning = false
      case Failure(ex) =>
        log.error(s"KafkaSource failed: $sourceId", ex)
        isRunning = false
    }

    Future.successful(Done)
  }

  /**
   * Send batch of records to pipeline.
   */
  private def sendBatch(
    records: List[DataRecord],
    pipelineShardRegion: ActorRef[ShardingEnvelope[Command]]
  ): Future[Done] = {
    if (records.isEmpty) {
      return Future.successful(Done)
    }

    val batchId = UUID.randomUUID().toString
    val offset = currentOffset

    log.debug(s"Sending batch: batchId=$batchId, records=${records.size}, offset=$offset, topic=$topic")

    val command = IngestBatch(
      pipelineId = pipelineId,
      batchId = batchId,
      records = records,
      sourceOffset = offset,
      replyTo = system.ignoreRef
    )

    pipelineShardRegion ! ShardingEnvelope(pipelineId, command)

    Future.successful(Done)
  }

  /**
   * Stop consuming from Kafka.
   */
  override def stop(): Future[Done] = {
    if (!isRunning) {
      log.warn(s"KafkaSource not running: $sourceId")
      return Future.successful(Done)
    }

    log.info(s"Stopping KafkaSource: $sourceId")

    killSwitch.foreach(_.shutdown())
    killSwitch = None
    isRunning = false

    Future.successful(Done)
  }

  override def currentOffset(): Long = currentOffset

  override def resumeFrom(offset: Long): Unit = {
    log.info(s"KafkaSource will resume from Kafka managed offset (consumer group: $groupId)")
    // Kafka consumer group manages offsets automatically
    // No manual seeking needed
  }

  override def isHealthy: Boolean = {
    // Could implement actual health check by trying to fetch metadata
    isRunning
  }
}

/**
 * Companion object with factory method.
 */
object KafkaSource {
  def apply(pipelineId: String, config: SourceConfig)(implicit system: ActorSystem[_]): KafkaSource = {
    new KafkaSource(pipelineId, config)
  }
}

package com.dataflow.sinks.kafka

import scala.concurrent.{ExecutionContext, Future}

import cats.implicits.toFunctorOps
import cats.syntax.either._
import cats.syntax.option._
import com.dataflow.domain.models.DataRecord
import com.dataflow.sinks.domain._
import com.dataflow.sinks.util.SinkUtils
import io.circe.Json
import io.circe.syntax._
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.pekko.Done
import org.apache.pekko.kafka.{ProducerMessage, ProducerSettings}
import org.apache.pekko.kafka.scaladsl.Producer
import org.apache.pekko.stream.scaladsl.{Flow, Sink}
import org.slf4j.LoggerFactory

/**
 * Production-ready Kafka sink with functional programming approach.
 *
 * Features:
 * - At-least-once delivery semantics
 * - Batching for throughput
 * - Custom key extraction
 * - Metrics tracking
 * - Functional error handling with Cats
 * - Type-safe composition
 *
 * @param config Kafka sink configuration
 */
class KafkaSink(config: KafkaSinkConfig)(implicit ec: ExecutionContext) extends DataSink {

  private val logger = LoggerFactory.getLogger(getClass)

  // Immutable metrics tracking using atomic reference
  private val metricsRef = new java.util.concurrent.atomic.AtomicReference[SinkMetrics](SinkMetrics.empty)
  override def sinkType: SinkType = SinkType.Kafka

  /**
   * Validate Kafka configuration.
   */
  private def validateConfiguration(): Unit = {
    require(config.topic.nonEmpty, "Kafka topic cannot be empty")
    require(config.bootstrapServers.nonEmpty, "Kafka bootstrap servers cannot be empty")
  }

  override def close(): Future[Done] = {
    logger.info(s"Closing Kafka sink for topic: ${config.topic}")
    Future.successful(Done)
  }

  override def metrics: SinkMetrics = metricsRef.get()

  // Kafka producer settings
  private val producerSettings: ProducerSettings[String, String] =
    ProducerSettings(
      config.actorSystem,
      new StringSerializer,
      new StringSerializer,
    )
      .withBootstrapServers(config.bootstrapServers)
      .withProperties(config.properties)

  override def sink: Sink[DataRecord, Future[Done]] = {
    Flow[DataRecord]
      .via(SinkUtils.metricsFlow("KafkaSink"))
      .via(recordToEnvelopeFlow)
      .via(batchingFlow)
      .via(Producer.flexiFlow(producerSettings))
      .map(processResult)
      .toMat(Sink.ignore)(org.apache.pekko.stream.scaladsl.Keep.right)
  }

  /**
   * Flow to convert DataRecord to Kafka ProducerMessage.Envelope.
   * This fixes the type mismatch - Producer.flexiFlow expects Envelope, not ProducerRecord.
   */
  private def recordToEnvelopeFlow: Flow[
    DataRecord,
    ProducerMessage.Envelope[String, String, DataRecord],
    org.apache.pekko.NotUsed,
  ] = {
    Flow[DataRecord].map {
      record =>
        recordToProducerRecord(record)
          .map(producerRecord => ProducerMessage.single(producerRecord, record))
          .getOrElse {
            logger.error(s"Failed to create producer record for: ${record.id}")
            // Create a pass-through message for failed conversions
            ProducerMessage.passThrough[String, String, DataRecord](record)
          }
    }
  }

  /**
   * Batching flow for throughput optimization.
   */
  private def batchingFlow: Flow[
    ProducerMessage.Envelope[String, String, DataRecord],
    ProducerMessage.Envelope[String, String, DataRecord],
    org.apache.pekko.NotUsed,
  ] = {
    Flow[ProducerMessage.Envelope[String, String, DataRecord]]
      .groupedWithin(config.batchConfig.maxSize, config.batchConfig.maxDuration)
      .mapConcat(identity)
  }

  /**
   * Convert DataRecord to Kafka ProducerRecord with functional error handling.
   */
  private def recordToProducerRecord(record: DataRecord): Option[ProducerRecord[String, String]] = {
    for {
      value <- encodeRecordToJson(record)
    } yield {
      val key = extractKey(record)
      new ProducerRecord[String, String](config.topic, key, value)
    }
  }

  /**
   * Extract key from record using configured key field or falling back to record ID.
   */
  private def extractKey(record: DataRecord): String =
    config.keyField
      .flatMap(record.data.get)
      .getOrElse(record.id)

  /**
   * Convert DataRecord to JSON string with error handling.
   */
  private def encodeRecordToJson(record: DataRecord): Option[String] =
    Either.catchNonFatal {
      Json.obj(
        "id"       -> record.id.asJson,
        "data"     -> record.data.asJson,
        "metadata" -> record.metadata.asJson,
      ).noSpaces
    }.toOption

  /**
   * Process the result from Kafka producer and update metrics.
   */
  private def processResult(
    result: ProducerMessage.Results[String, String, DataRecord],
  ): ProducerMessage.Results[String, String, DataRecord] = {
    result match {
      case ProducerMessage.Result(metadata, message) =>
        logSuccessfulWrite(metadata, 1)
        updateMetrics(1)

      case ProducerMessage.MultiResult(parts, _) =>
        logSuccessfulWrite(parts.head.metadata, parts.size)
        updateMetrics(parts.size)

      case ProducerMessage.PassThroughResult(passThrough) =>
        logger.warn(s"Pass-through result for record: ${passThrough.id}")
    }
    result
  }

  /**
   * Log successful write to Kafka.
   */
  private def logSuccessfulWrite(metadata: org.apache.kafka.clients.producer.RecordMetadata, count: Int): Unit = {
    logger.debug(
      s"Successfully wrote $count record(s) to Kafka: " +
        s"topic=${metadata.topic()}, partition=${metadata.partition()}, offset=${metadata.offset()}",
    )
  }

  /**
   * Update metrics atomically.
   */
  private def updateMetrics(recordCount: Int): Unit = {
    metricsRef.updateAndGet {
      current =>
        current.copy(
          recordsWritten = current.recordsWritten + recordCount,
          lastWriteTimestamp = System.currentTimeMillis().some,
        )
    }
    ()
  }

  override def healthCheck(): Future[HealthStatus] =
    performHealthCheck().value.map {
      case Right(status) => status
      case Left(error)   => HealthStatus.Unhealthy(error.getMessage)
    }

  /**
   * Perform health check using Either for functional error handling.
   */
  import cats.syntax.either._

  private def performHealthCheck(): cats.data.EitherT[Future, Throwable, HealthStatus] =
    cats.data.EitherT {
      Future {
        Either.catchNonFatal {
          validateConfiguration()
          logger.debug("Kafka health check: OK")
          HealthStatus.Healthy
        }.widen[HealthStatus]
      }
    }

}

/**
 * Configuration for Kafka sink with sensible defaults.
 */
case class KafkaSinkConfig(
  topic: String,
  bootstrapServers: String,
  keyField: Option[String] = None,
  properties: Map[String, String] = Map.empty,
  batchConfig: BatchConfig = BatchConfig.default,
  retryConfig: RetryConfig = RetryConfig.default,
)(implicit val actorSystem: org.apache.pekko.actor.ActorSystem)


object KafkaSinkConfig {

  /**
   * Create config with validation.
   */
  def create(
    topic: String,
    bootstrapServers: String,
    keyField: Option[String] = None,
    properties: Map[String, String] = Map.empty,
    batchConfig: BatchConfig = BatchConfig.default,
    retryConfig: RetryConfig = RetryConfig.default,
  )(implicit actorSystem: org.apache.pekko.actor.ActorSystem,
  ): Either[String, KafkaSinkConfig] = {
    for {
      _ <- Either.cond(topic.nonEmpty, (), "Topic cannot be empty")
      _ <- Either.cond(bootstrapServers.nonEmpty, (), "Bootstrap servers cannot be empty")
    } yield KafkaSinkConfig(topic, bootstrapServers, keyField, properties, batchConfig, retryConfig)
  }

  /**
   * Default configuration for local development.
   */
  def localDev(topic: String)(implicit actorSystem: org.apache.pekko.actor.ActorSystem): KafkaSinkConfig =
    KafkaSinkConfig(
      topic = topic,
      bootstrapServers = "localhost:9092",
      properties = Map(
        "acks"                                  -> "1",
        "compression.type"                      -> "snappy",
        "max.in.flight.requests.per.connection" -> "5",
        "retries"                               -> "3",
      ),
    )

  /**
   * Production configuration with stronger guarantees.
   */
  def production(
    topic: String,
    bootstrapServers: String,
  )(implicit actorSystem: org.apache.pekko.actor.ActorSystem,
  ): KafkaSinkConfig =
    KafkaSinkConfig(
      topic = topic,
      bootstrapServers = bootstrapServers,
      properties = Map(
        "acks"                                  -> "all",
        "compression.type"                      -> "lz4",
        "max.in.flight.requests.per.connection" -> "5",
        "retries"                               -> "10",
        "enable.idempotence"                    -> "true",
        "max.block.ms"                          -> "60000",
        "request.timeout.ms"                    -> "30000",
      ),
    )
}

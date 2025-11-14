package com.dataflow.sources.test

import com.dataflow.domain.commands.{Command, IngestBatch}
import com.dataflow.domain.models.DataRecord
import com.dataflow.sources.SourceMetricsReporter
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.pattern.StatusReply
import org.slf4j.Logger

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._
import scala.util.Random

/**
 * Test source connector that generates sample data for testing.
 *
 * This source:
 * - Generates random user events
 * - Sends batches to pipeline at configured interval
 * - Useful for local development and testing
 * - Can be configured to generate specific patterns
 *
 * Usage:
 * {{{
 *   val testSource = spawn(TestSource("test-pipeline", pipelineShardRegion))
 *   testSource ! TestSource.Start
 * }}}
 */
object TestSource {

  // Commands
  sealed trait SourceCommand
  final case object Start extends SourceCommand
  final case object Stop extends SourceCommand
  final case object GenerateBatch extends SourceCommand
  private final case class BatchResponse(result: StatusReply[Any]) extends SourceCommand

  // Configuration
  final case class Config(
    batchSize: Int = 100,              // Records per batch
    batchInterval: FiniteDuration = 5.seconds,  // Time between batches
    recordType: String = "user-event"   // Type of records to generate
  )

  /**
   * Create a test source behavior.
   *
   * @param pipelineId The pipeline ID to send data to
   * @param pipelineShardRegion The pipeline shard region
   * @param config Source configuration
   */
  def apply(
    pipelineId: String,
    pipelineShardRegion: ActorRef[ShardingEnvelope[Command]],
    config: Config = Config()
  ): Behavior[SourceCommand] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new TestSource(pipelineId, pipelineShardRegion, config, context, timers).idle()
      }
    }
  }
}

class TestSource private (
  pipelineId: String,
  pipelineShardRegion: ActorRef[ShardingEnvelope[Command]],
  config: TestSource.Config,
  context: ActorContext[TestSource.SourceCommand],
  timers: TimerScheduler[TestSource.SourceCommand]
) {
  import TestSource._

  private val log: Logger = context.log
  private var totalRecordsGenerated: Long = 0
  private var totalBatchesSent: Long = 0
  private var currentOffset: Long = 0

  /**
   * Idle state - waiting for Start command.
   */
  private def idle(): Behavior[SourceCommand] = {
    Behaviors.receiveMessage {
      case Start =>
        log.info(
          s"Starting test source for pipeline=$pipelineId, batchSize=${config.batchSize}, interval=${config.batchInterval}"
        )
        // Schedule periodic batch generation
        timers.startTimerWithFixedDelay(GenerateBatch, config.batchInterval)
        active()

      case Stop =>
        log.info("Test source already stopped")
        Behaviors.same

      case _ =>
        Behaviors.unhandled
    }
  }

  /**
   * Active state - generating and sending batches.
   */
  private def active(): Behavior[SourceCommand] = {
    Behaviors.receiveMessage {
      case GenerateBatch =>
        generateAndSendBatch()
        Behaviors.same

      case BatchResponse(StatusReply.Success(_)) =>
        log.debug(s"Batch processed successfully by pipeline, totalBatches=$totalBatchesSent")
        Behaviors.same

      case BatchResponse(StatusReply.Error(error)) =>
        log.error(s"Batch processing failed: $error")
        Behaviors.same

      case Stop =>
        log.info(
          s"Stopping test source, stats: totalRecords=$totalRecordsGenerated, totalBatches=$totalBatchesSent"
        )
        timers.cancel(GenerateBatch)
        idle()

      case Start =>
        log.warn("Test source already started")
        Behaviors.same
    }
  }

  /**
   * Generate a batch of test records and send to pipeline.
   */
  private def generateAndSendBatch(): Unit = {
    val batchId = UUID.randomUUID().toString
    val records = generateRecords(config.batchSize)

    log.info(
      s"Generating batch: batchId=$batchId, records=${records.size}, offset=$currentOffset"
    )

    // Create response adapter
    val responseAdapter = context.messageAdapter[StatusReply[Any]](BatchResponse)

    // Send batch to pipeline
    val command = IngestBatch(
      pipelineId = pipelineId,
      batchId = batchId,
      records = records,
      sourceOffset = currentOffset,
      replyTo = responseAdapter
    )

    pipelineShardRegion ! ShardingEnvelope(pipelineId, command)

    // Update stats
    totalRecordsGenerated += records.size
    totalBatchesSent += 1
    currentOffset += records.size
  }

  /**
   * Generate sample records based on configuration.
   */
  private def generateRecords(count: Int): List[DataRecord] = {
    config.recordType match {
      case "user-event" => generateUserEvents(count)
      case "order"      => generateOrders(count)
      case "sensor"     => generateSensorReadings(count)
      case _            => generateGenericRecords(count)
    }
  }

  /**
   * Generate sample user event records.
   */
  private def generateUserEvents(count: Int): List[DataRecord] = {
    val eventTypes = List("login", "logout", "page_view", "click", "purchase", "search")
    val userIds = List("user-1", "user-2", "user-3", "user-4", "user-5")

    (1 to count).map { i =>
      val userId = userIds(Random.nextInt(userIds.length))
      val eventType = eventTypes(Random.nextInt(eventTypes.length))

      DataRecord(
        id = UUID.randomUUID().toString,
        data = Map(
          "event_type" -> eventType,
          "user_id" -> userId,
          "timestamp" -> Instant.now().toString,
          "session_id" -> UUID.randomUUID().toString,
          "ip_address" -> s"192.168.${Random.nextInt(256)}.${Random.nextInt(256)}",
          "user_agent" -> "Mozilla/5.0 (Test Agent)"
        ),
        metadata = Map(
          "source" -> "test-source",
          "pipeline_id" -> pipelineId,
          "record_number" -> (totalRecordsGenerated + i).toString
        )
      )
    }.toList
  }

  /**
   * Generate sample order records.
   */
  private def generateOrders(count: Int): List[DataRecord] = {
    val products = List("laptop", "phone", "tablet", "monitor", "keyboard", "mouse")

    (1 to count).map { i =>
      val product = products(Random.nextInt(products.length))
      val quantity = Random.nextInt(5) + 1
      val price = BigDecimal((Random.nextDouble() * 1000).round / 100.0)

      DataRecord(
        id = UUID.randomUUID().toString,
        data = Map(
          "order_id" -> UUID.randomUUID().toString,
          "user_id" -> s"user-${Random.nextInt(100)}",
          "product" -> product,
          "quantity" -> quantity.toString,
          "price" -> price.toString,
          "total" -> (price * quantity).toString,
          "timestamp" -> Instant.now().toString
        ),
        metadata = Map(
          "source" -> "test-source",
          "pipeline_id" -> pipelineId
        )
      )
    }.toList
  }

  /**
   * Generate sample sensor readings.
   */
  private def generateSensorReadings(count: Int): List[DataRecord] = {
    val sensorIds = List("sensor-1", "sensor-2", "sensor-3")

    (1 to count).map { i =>
      val sensorId = sensorIds(Random.nextInt(sensorIds.length))
      val temperature = 20.0 + (Random.nextDouble() * 10)
      val humidity = 40.0 + (Random.nextDouble() * 30)

      DataRecord(
        id = UUID.randomUUID().toString,
        data = Map(
          "sensor_id" -> sensorId,
          "temperature" -> f"$temperature%.2f",
          "humidity" -> f"$humidity%.2f",
          "timestamp" -> Instant.now().toString
        ),
        metadata = Map(
          "source" -> "test-source",
          "pipeline_id" -> pipelineId
        )
      )
    }.toList
  }

  /**
   * Generate generic sample records.
   */
  private def generateGenericRecords(count: Int): List[DataRecord] = {
    (1 to count).map { i =>
      DataRecord(
        id = UUID.randomUUID().toString,
        data = Map(
          "field1" -> s"value-${Random.nextInt(1000)}",
          "field2" -> Random.nextInt(100).toString,
          "timestamp" -> Instant.now().toString
        ),
        metadata = Map(
          "source" -> "test-source",
          "pipeline_id" -> pipelineId,
          "record_number" -> (totalRecordsGenerated + i).toString
        )
      )
    }.toList
  }
}

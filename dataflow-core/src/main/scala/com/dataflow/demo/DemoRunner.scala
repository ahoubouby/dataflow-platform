package com.dataflow.demo

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import com.dataflow.domain.commands._
import com.dataflow.domain.models._
import com.dataflow.domain.state.State
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory

/**
 * Demo runner that demonstrates the DataFlow Platform capabilities.
 *
 * This creates a simple pipeline that:
 * 1. Reads from a text file (/tmp/dataflow-demo/sample-data.txt)
 * 2. Processes the data (no transforms for now)
 * 3. Prints output to console (simulated with a "console" sink)
 */
object DemoRunner {
  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Run the demo pipeline.
   *
   * @param pipelineShardRegion The pipeline shard region
   * @param system The actor system
   */
  def runDemo(
    pipelineShardRegion: ActorRef[ShardingEnvelope[Command]]
  )(implicit system: ActorSystem[_]): Unit = {
    implicit val ec: ExecutionContext = system.executionContext
    implicit val timeout: Timeout = Timeout(10.seconds)

    log.info("=" * 80)
    log.info("Starting DataFlow Platform Demo")
    log.info("=" * 80)

    // Wait a bit for cluster to be fully ready
    system.scheduler.scheduleOnce(5.seconds) { () =>
      log.info("Creating demo pipeline...")

      val pipelineId = "demo-pipeline-001"

      // Step 1: Create the pipeline
      createPipeline(pipelineShardRegion, pipelineId).onComplete {
        case Success(state) =>
          log.info(s"✓ Pipeline created successfully: $state")
          log.info("Starting pipeline...")

          // Step 2: Start the pipeline
          startPipeline(pipelineShardRegion, pipelineId).onComplete {
            case Success(state) =>
              log.info(s"✓ Pipeline started successfully: $state")
              log.info("Demo pipeline is now running and processing data...")
              log.info("Check the logs for data processing output")

              // Step 3: Simulate data ingestion (in real scenario, source would do this)
              scheduleDataIngestion(pipelineShardRegion, pipelineId)

            case Failure(ex) =>
              log.error("✗ Failed to start pipeline", ex)
          }

        case Failure(ex) =>
          log.error("✗ Failed to create pipeline", ex)
      }
    }
  }

  /**
   * Create a demo pipeline.
   */
  private def createPipeline(
    pipelineShardRegion: ActorRef[ShardingEnvelope[Command]],
    pipelineId: String
  )(implicit system: ActorSystem[_], timeout: Timeout): Future[State] = {
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._

    // Configure source: Text file
    val sourceConfig = SourceConfig(
      sourceType = SourceType.File,
      connectionString = "/tmp/dataflow-demo/sample-data.txt",
      batchSize = 10,
      pollIntervalMs = 1000,
      options = Map(
        "format" -> "text",
        "encoding" -> "UTF-8"
      )
    )

    // Configure transforms: None for now (pass-through)
    val transformConfigs = List.empty[TransformConfig]

    // Configure sink: Console (print to stdout)
    val sinkConfig = SinkConfig(
      sinkType = "console",
      connectionString = "stdout",
      batchSize = 10
    )

    // Send create command
    val command = CreatePipeline(
      pipelineId = pipelineId,
      name = "Demo Text File Pipeline",
      description = "Reads from /tmp/dataflow-demo/sample-data.txt and prints to console",
      sourceConfig = sourceConfig,
      transformConfigs = transformConfigs,
      sinkConfig = sinkConfig,
      replyTo = _
    )

    pipelineShardRegion
      .ask[StatusReply[State]](replyTo => ShardingEnvelope(pipelineId, command(replyTo)))
      .map {
        case StatusReply.Success(state) => state
        case StatusReply.Error(ex) => throw new RuntimeException(s"Failed to create pipeline: ${ex.getMessage}")
      }
  }

  /**
   * Start the pipeline.
   */
  private def startPipeline(
    pipelineShardRegion: ActorRef[ShardingEnvelope[Command]],
    pipelineId: String
  )(implicit system: ActorSystem[_], timeout: Timeout): Future[State] = {
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._

    val command = StartPipeline(
      pipelineId = pipelineId,
      replyTo = _
    )

    pipelineShardRegion
      .ask[StatusReply[State]](replyTo => ShardingEnvelope(pipelineId, command(replyTo)))
      .map {
        case StatusReply.Success(state) => state
        case StatusReply.Error(ex) => throw new RuntimeException(s"Failed to start pipeline: ${ex.getMessage}")
      }
  }

  /**
   * Simulate data ingestion by reading the file and sending batches.
   *
   * In a real scenario, the source connector would handle this automatically.
   * This is just for demonstration purposes.
   */
  private def scheduleDataIngestion(
    pipelineShardRegion: ActorRef[ShardingEnvelope[Command]],
    pipelineId: String
  )(implicit system: ActorSystem[_]): Unit = {
    implicit val ec: ExecutionContext = system.executionContext
    implicit val timeout: Timeout = Timeout(10.seconds)

    // Schedule data ingestion after a delay
    system.scheduler.scheduleOnce(3.seconds) { () =>
      log.info("Simulating data ingestion...")

      // Read the demo file
      try {
        val source = scala.io.Source.fromFile("/tmp/dataflow-demo/sample-data.txt")
        val lines = source.getLines().toList
        source.close()

        // Create data records from lines
        val records = lines.zipWithIndex.map { case (line, idx) =>
          DataRecord(
            id = java.util.UUID.randomUUID().toString,
            data = Map("line" -> line, "lineNumber" -> (idx + 1).toString),
            metadata = Map(
              "source" -> "demo-file",
              "timestamp" -> System.currentTimeMillis().toString
            )
          )
        }

        // Send as a batch
        ingestBatch(pipelineShardRegion, pipelineId, records).onComplete {
          case Success(result) =>
            log.info(s"✓ Data ingestion completed: $result")
            log.info("=" * 80)
            log.info("Demo completed successfully!")
            log.info("The pipeline processed all records from the demo file.")
            log.info("=" * 80)

          case Failure(ex) =>
            log.error("✗ Data ingestion failed", ex)
        }

      } catch {
        case ex: Exception =>
          log.error("Failed to read demo file", ex)
      }
    }
  }

  /**
   * Ingest a batch of data into the pipeline.
   */
  private def ingestBatch(
    pipelineShardRegion: ActorRef[ShardingEnvelope[Command]],
    pipelineId: String,
    records: List[DataRecord]
  )(implicit system: ActorSystem[_], timeout: Timeout): Future[BatchResult] = {
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._

    val batchId = java.util.UUID.randomUUID().toString

    val command = IngestBatch(
      pipelineId = pipelineId,
      batchId = batchId,
      records = records,
      sourceOffset = 0L,
      replyTo = _
    )

    pipelineShardRegion
      .ask[StatusReply[BatchResult]](replyTo => ShardingEnvelope(pipelineId, command(replyTo)))
      .map {
        case StatusReply.Success(result) => result
        case StatusReply.Error(ex) => throw new RuntimeException(s"Failed to ingest batch: ${ex.getMessage}")
      }
  }
}

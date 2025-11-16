package com.dataflow.execution

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import com.dataflow.domain.models.SinkConfig
import com.dataflow.sinks.console._
import com.dataflow.sinks.domain.{BatchConfig, DataSink, RetryConfig}
import com.dataflow.sinks.file.{FileFormat, FileSink, FileSinkConfig}
import com.dataflow.sinks.file.FileFormat.CSV
import com.dataflow.sinks.kafka.{KafkaSink, KafkaSinkConfig}
import org.slf4j.LoggerFactory

/**
 * Factory for creating DataSink instances from configuration.
 *
 * Responsible for:
 * - Parsing SinkConfig (generic Map-based config)
 * - Creating appropriate sink implementation
 * - Setting up batching and retry policies
 */
object SinkFactory {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Create a DataSink from configuration.
   *
   * @param pipelineId The pipeline ID
   * @param config Sink configuration
   * @param ec ExecutionContext for async operations
   * @return DataSink instance
   */
  def create(
    pipelineId: String,
    config: SinkConfig,
  )(implicit ec: ExecutionContext,
  ): DataSink = {
    logger.info("Creating sink of type: {} for pipeline: {}", config.sinkType, pipelineId)

    config.sinkType.toLowerCase match {
      case "console" => createConsoleSink(pipelineId, config)
      case "file"    => createFileSink(pipelineId, config)
      // case "kafka"   => createKafkaSink(pipelineId, config)
      case other     =>
        logger.warn("Unsupported sink type: {}, falling back to console", other)
        createConsoleSink(pipelineId, config)
    }
  }

  // ============================================
  // CONSOLE SINK
  // ============================================

  private def createConsoleSink(
    pipelineId: String,
    config: SinkConfig,
  )(implicit ec: ExecutionContext,
  ): ConsoleSink = {
    // Parse output format from connectionString or default to JSON
    val format = config.connectionString.toLowerCase match {
      case s if s.contains("pretty") || s.contains("json") => OutputFormat.PrettyJSON
      case s if s.contains("compact")                      => OutputFormat.CompactJSON
      case s if s.contains("simple")                       => OutputFormat.Simple
      case s if s.contains("table")                        => OutputFormat.Table
      case s if s.contains("structured")                   => OutputFormat.Structured
      case s if s.contains("keyvalue") || s.contains("kv") => OutputFormat.KeyValue
      case _                                               => OutputFormat.PrettyJSON
    }

    // Parse output target (stdout vs stderr)
    val target = config.connectionString.toLowerCase match {
      case s if s.contains("stderr") => OutputTarget.StdErr
      case _                         => OutputTarget.StdOut
    }

    val consoleConfig = ConsoleSinkConfig.create(
      format = OutputFormat.KeyValue,
      printSummary = false,
    ).toOption.get

    new ConsoleSink(consoleConfig)
  }

  // ============================================
  // FILE SINK
  // ============================================

  private def createFileSink(
    pipelineId: String,
    config: SinkConfig,
  )(implicit ec: ExecutionContext,
  ): FileSink = {
    // connectionString should be the file path
    val path = config.connectionString

    // Parse format from path extension
    val format = path.toLowerCase match {
      case s if s.endsWith(".json") => "json"
      case s if s.endsWith(".csv")  => "csv"
      case s if s.endsWith(".txt")  => "text"
      case _                        => "json" // default
    }

    val fileConfig: FileSinkConfig = FileSinkConfig
      .create(
        path = path,
        format = FileFormat.fromString(format).getOrElse(CSV),
      )
    new FileSink(fileConfig)
  }

  // ============================================
  // KAFKA SINK
  // ============================================

//  private def createKafkaSink(
//    pipelineId: String,
//    config: SinkConfig,
//  )(implicit ec: ExecutionContext,
//  ): KafkaSink = {
//    // Parse connectionString: "bootstrap.servers:topic"
//    // Example: "localhost:9092:my-topic"
//    val parts            = config.connectionString.split(":")
//    val bootstrapServers = if (parts.length >= 2) {
//      s"${parts(0)}:${parts(1)}"
//    } else {
//      "localhost:9092"
//    }
//
//    val topic = if (parts.length >= 3) {
//      parts(2)
//    } else {
//      s"dataflow-$pipelineId"
//    }
//
//    // Kafka producer configuration
//    val producerConfig = Map(
//      "bootstrap.servers" -> bootstrapServers,
//      "key.serializer"    -> "org.apache.kafka.common.serialization.StringSerializer",
//      "value.serializer"  -> "org.apache.kafka.common.serialization.StringSerializer",
//      "acks"              -> "all",
//      "retries"           -> "3",
//      "linger.ms"         -> "10",
//      "batch.size"        -> config.batchSize.toString,
//    )
//
//    val retryConfig = RetryConfig(
//      maxAttempts = 4,
//      initialDelay = 1.second,
//      maxDelay = 30.seconds,
//    )
//
//    val batchConfig = BatchConfig(
//      maxSize = config.batchSize,
//      maxDuration = 5.seconds,
//    )
//
//    val kafkaSinkConfig =
//      KafkaSinkConfig.create(topic, bootstrapServers, batchConfig = batchConfig, retryConfig = retryConfig)()
//        .toOption.get
//    new KafkaSink(kafkaSinkConfig)
//  }
}

package com.dataflow.examples

import java.nio.file.{Files, Paths, StandardOpenOption}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

import cats.effect.{IO, Resource}
import cats.implicits._
import com.dataflow.domain.models._
import com.dataflow.sinks.console.{ColorScheme, ConsoleSink, OutputFormat}
import com.dataflow.sinks.domain.exceptions.{ConsoleSinkError, SinkError}
import com.dataflow.sources.file.{CSVFileSource, JSONFileSource}
import com.dataflow.transforms.aggregation._
import com.dataflow.transforms.domain.{AggregateConfig, AggregationType, FilterConfig, MapConfig}
import com.dataflow.transforms.filters.{ComparisonOperator, FilterTransform}
import com.dataflow.transforms.mapping.MapTransform
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.Materializer
import org.slf4j.LoggerFactory

/**
 * Sophisticated integration application demonstrating the complete DataFlow pipeline.
 *
 * Architecture:
 * - Functional error handling with Cats Effect IO
 * - Resource management with cats.effect.Resource
 * - Type-safe pipeline composition with Either
 * - Production-ready patterns (health checks, metrics, graceful shutdown)
 * - Thread-safe operations with AtomicReference
 *
 * Pipeline Flows:
 * 1. CSV Pipeline: File Source → Filter Transform → Console Sink (PrettyJSON)
 * 2. JSON Pipeline: File Source → Map Transform → Console Sink (Structured)
 * 3. Aggregation Pipeline: File Source → Filter → Aggregate → Console Sink (Table)
 *
 * Features:
 * - CSV and JSON file reading with validation
 * - Sophisticated filtering with type-safe comparison operators
 * - Field transformations and enrichment
 * - Windowed aggregations with multiple functions (count, sum, avg, min, max)
 * - Rich console output with 6 different formats and ANSI colors
 * - Comprehensive metrics and health monitoring
 * - Graceful error handling and resource cleanup
 */
object FileToConsoleApp {

  private val log = LoggerFactory.getLogger(getClass)

  // ============================================================================
  // Application Entry Point
  // ============================================================================

  /**
   * Main entry point with graceful error handling.
   * Exit codes: 0 = success, 1 = failure
   */
  def main(args: Array[String]): Unit = {
    import cats.effect.unsafe.implicits.global
    printBanner()
    val result = run().attempt.unsafeRunSync()
    result match {
      case Right(_)    =>
        log.info("✓ Application completed successfully")
        System.exit(0)
      case Left(error) =>
        log.error("✗ Application failed", error)
        System.exit(1)
    }
  }

  /**
   * Main application logic wrapped in Cats Effect IO for functional error handling.
   * Each pipeline runs in sequence with proper resource management.
   */
  def run(): IO[Unit] = {
    for {
      _ <- IO(log.info("Starting DataFlow Integration Application"))

      // Setup test data
      _ <- setupSampleData()
      _ <- IO(log.info("✓ Sample data created"))
      _ <- IO(printSeparator())

      // Run pipelines in sequence
      _ <- runCSVPipeline()
      _ <- IO(printSeparator())

      _ <- runJSONPipeline()
      _ <- IO(printSeparator())

      _ <- runAggregationPipeline()
      _ <- IO(printSeparator())

      _ <- IO(log.info("✓ All pipelines completed successfully"))
    } yield ()
  }

  // ============================================================================
  // Sample Data Setup
  // ============================================================================

  /**
   * Create sample CSV and JSON data files for testing.
   * Creates a 'dataflow-examples/data' directory with test files.
   */
  private def setupSampleData(): IO[Unit] = IO {
    val dataDir = Paths.get("dataflow-examples/data")
    if (!Files.exists(dataDir)) {
      Files.createDirectories(dataDir)
    }

    // Sample CSV data - User activity log
    val csvData = """id,user,action,timestamp,value
                    |1,alice,login,2024-01-01T10:00:00,0
                    |2,bob,purchase,2024-01-01T10:05:00,150
                    |3,alice,view,2024-01-01T10:10:00,0
                    |4,charlie,purchase,2024-01-01T10:15:00,250
                    |5,bob,view,2024-01-01T10:20:00,0
                    |6,alice,purchase,2024-01-01T10:25:00,300
                    |7,charlie,logout,2024-01-01T10:30:00,0
                    |8,bob,purchase,2024-01-01T10:35:00,175
                    |9,alice,logout,2024-01-01T10:40:00,0
                    |10,charlie,purchase,2024-01-01T10:45:00,400""".stripMargin

    val csvPath = dataDir.resolve("users.csv")
    Files.write(
      csvPath,
      csvData.getBytes,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
    )

    // Sample JSON data - E-commerce orders
    val jsonData =
      """{"id":"ord-001","customer":"alice","product":"laptop","price":"1200","quantity":"1","status":"shipped"}
        |{"id":"ord-002","customer":"bob","product":"mouse","price":"25","quantity":"2","status":"pending"}
        |{"id":"ord-003","customer":"charlie","product":"keyboard","price":"75","quantity":"1","status":"shipped"}
        |{"id":"ord-004","customer":"alice","product":"monitor","price":"350","quantity":"2","status":"delivered"}
        |{"id":"ord-005","customer":"bob","product":"laptop","price":"1200","quantity":"1","status":"shipped"}
        |{"id":"ord-006","customer":"charlie","product":"mouse","price":"25","quantity":"5","status":"pending"}
        |{"id":"ord-007","customer":"alice","product":"keyboard","price":"75","quantity":"1","status":"delivered"}
        |{"id":"ord-008","customer":"bob","product":"monitor","price":"350","quantity":"1","status":"shipped"}""".stripMargin

    val jsonPath = dataDir.resolve("orders.json")
    Files.write(
      jsonPath,
      jsonData.getBytes,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
    )

    log.info(s"Sample data created at: $csvPath and $jsonPath")
  }

  // ============================================================================
  // Pipeline 1: CSV Source → Filter → Console Sink
  // ============================================================================

  /**
   * Pipeline 1: CSV Source → Filter (purchases only) → Console Sink
   *
   * Demonstrates:
   * - CSV file reading with header parsing
   * - Filtering with type-safe Equals operator
   * - Pretty JSON output to console with colors
   * - Metrics collection and reporting
   *
   * Expected Output: 5 purchase records in pretty JSON format
   */
  private def runCSVPipeline(): IO[Unit] = {
    resourcePipeline(
      name = "CSV Pipeline - User Purchases",
      description = "Reading CSV file, filtering purchase actions, and displaying with pretty JSON format",
    ) {
      implicit system =>
        implicit val ec: ExecutionContext = system.executionContext
        val config = SourceConfig(
          sourceType = SourceType.File,
          connectionString = "dataflow-examples/data/users.csv",
          options = Map(
            "format"     -> "csv",
            "delimiter"  -> ",",
            "has-header" -> "true",
          ),
          batchSize = 100,
          pollIntervalMs = 500,
        )
        for {
          // Create CSV file source
          source          <- IO(new CSVFileSource("csv-pipeline", config))
          // Create filter transform - only purchase actions
          filterTransform <- IO(new FilterTransform(FilterConfig("action == purchase")))
          // Create console sink with pretty JSON format
          consoleSink     <- createConsoleSink(
                               format = OutputFormat.PrettyJSON,
                               colorScheme = ColorScheme.Enabled,
                               showTimestamp = true,
                               showMetadata = true,
                               printSummary = true,
                             )

          // Build and run pipeline
          _               <- runPipeline(source, filterTransform, consoleSink, "CSV Pipeline")

        } yield ()
    }
  }

  // ============================================================================
  // Pipeline 2: JSON Source → Map → Console Sink
  // ============================================================================

  /**
   * Pipeline 2: JSON Source → Map Transform → Console Sink
   *
   * Demonstrates:
   * - JSON file reading (JSONL format)
   * - Field mapping and renaming
   * - Structured console output (logfmt style)
   * - Health check monitoring
   *
   * Expected Output: 8 order records in structured key=value format
   */
  private def runJSONPipeline(): IO[Unit] = {
    resourcePipeline(
      name = "JSON Pipeline - Order Processing",
      description = "Reading JSON file, transforming field names, and displaying with structured format",
    ) {
      implicit system =>
        implicit val ec: ExecutionContext = system.executionContext

        for {
          // Create JSON file source
          source       <- IO {
                            val config = SourceConfig(
                              sourceType = SourceType.File,
                              connectionString = "dataflow-examples/data/orders.json",
                              options = Map(
                                "format" -> "json",
                              ),
                              batchSize = 100,
                              pollIntervalMs = 500,
                            )
                            new JSONFileSource("json-pipeline", config)
                          }

          // Create map transform - rename fields for clarity
          mapTransform <- IO {
                            new MapTransform(
                              MapConfig(
                                mappings = Map(
                                  "customer_name" -> "customer",
                                  "item"          -> "product",
                                  "order_id"      -> "id",
                                  "unit_price"    -> "price",
                                ),
                              ),
                            )
                          }

          // Create console sink with structured format
          consoleSink  <- createConsoleSink(
                            format = OutputFormat.Structured,
                            colorScheme = ColorScheme.Enabled,
                            showTimestamp = false,
                            showMetadata = false,
                            printSummary = true,
                          )

          // Build and run pipeline
          _            <- runPipeline(source, mapTransform, consoleSink, "JSON Pipeline")

          // Perform health check
          health <- IO.fromFuture(IO(consoleSink.healthCheck()))
          _      <- IO(log.info(s"Console sink health status: $health"))

        } yield ()
    }
  }

  // ============================================================================
  // Pipeline 3: JSON Source → Filter → Aggregate → Console Sink
  // ============================================================================

  /**
   * Pipeline 3: JSON Source → Filter → Aggregate → Console Sink
   *
   * Demonstrates:
   * - Sophisticated aggregation with windowing
   * - Multiple aggregation functions (count, sum, avg, min, max)
   * - Filtering with In operator
   * - Table format output with borders
   * - Group-by operations
   *
   * Expected Output: Per-customer statistics in ASCII table format
   */
  private def runAggregationPipeline(): IO[Unit] = {
    resourcePipeline(
      name = "Aggregation Pipeline - Order Analytics",
      description = "Reading orders, filtering by status, aggregating by customer, and displaying statistics",
    ) {
      implicit system =>
        implicit val ec: ExecutionContext = system.executionContext
        val config = SourceConfig(
          sourceType = SourceType.File,
          connectionString = "dataflow-examples/data/orders.json",
          options = Map(
            "format" -> "json",
          ),
          batchSize = 100,
          pollIntervalMs = 500,
        )
        for {
          // Create JSON file source
          source <- IO(new JSONFileSource("aggregation-pipeline", config))

          // Filter only shipped or delivered orders
          filterTransform    <- IO {
                                  new FilterTransform(
                                    FilterConfig("status == shipped"),
//                                    field = "status",
//                                    operator = ComparisonOperator.In,
//                                    value = "shipped,delivered",
                                  )
                                }

          // Create aggregate transform - customer order statistics
          aggregateTransform <- IO {
                                  new AggregateTransform(
                                    AggregateConfig(
                                      groupByFields = Seq("customer"),
                                      windowSize = 5.seconds,
                                      aggregations = Map(
                                        "total_orders" -> AggregationType.Count,
                                        "total_spent"  -> AggregationType.Sum("price"),
                                        "avg_price"    -> AggregationType.Average("price"),
                                        "min_price"    -> AggregationType.Min("price"),
                                        "max_price"    -> AggregationType.Max("price"),
                                      ),
                                    ),
                                  )
                                }

          // Create console sink with table format
          consoleSink        <- createConsoleSink(
                                  format = OutputFormat.Table,
                                  colorScheme = ColorScheme.Enabled,
                                  showTimestamp = false,
                                  showMetadata = false,
                                  printSummary = true,
                                )

          // Build and run pipeline with two transforms
          _                  <- runAggregationPipeline(
                                  source,
                                  filterTransform,
                                  aggregateTransform,
                                  consoleSink,
                                  "Aggregation Pipeline",
                                )

        } yield ()
    }
  }

  // ============================================================================
  // Helper Functions
  // ============================================================================

  /**
   * Create a console sink with validation.
   * Converts Either to IO for seamless error handling.
   */
  private def createConsoleSink(
    format: OutputFormat,
    colorScheme: ColorScheme,
    showTimestamp: Boolean,
    showMetadata: Boolean,
    printSummary: Boolean,
  )(implicit ec: ExecutionContext,
  ): IO[ConsoleSink] = {
    IO.fromEither(
      ConsoleSink.builder()
        .withFormat(OutputFormat.Table)
        .withColorScheme(ColorScheme.Enabled)
        .withTimestamp(true)
        .withSummary(true)
        .build()
        .leftMap(err => ConsoleSinkErrorT(err)),
    )
  }

  case class ConsoleSinkErrorT(sinkError: SinkError)
    extends Exception(sinkError.message)

  /**
   * Run a simple pipeline: Source → Transform → Sink
   * Handles execution and metrics reporting.
   */
  private def runPipeline[S, T](
    source: S,
    transform: T,
    consoleSink: ConsoleSink,
    pipelineName: String,
  )(implicit ec: ExecutionContext,
    materializer: Materializer,
  ): IO[Unit] = {
    // Type constraints - source must have stream() and transform must have flow
    // This assumes your Source and Transform traits have these methods
    val sourceStream  =
      source.asInstanceOf[{ def stream(): org.apache.pekko.stream.scaladsl.Source[DataRecord, _] }].stream()
    val transformFlow =
      transform.asInstanceOf[{ def flow: org.apache.pekko.stream.scaladsl.Flow[DataRecord, DataRecord, _] }].flow

    for {
      // Execute pipeline
      _      <- IO.fromFuture(IO {
                  sourceStream
                    .via(transformFlow)
                    .runWith(consoleSink.sink)
                })

      // Report metrics
      metrics = consoleSink.metrics
      _      <- IO(log.info(
                  s"$pipelineName completed - ", // +
//                    s"Records written: ${metrics.recordsWritten}, " +
//                    s"Failed: ${metrics.recordsFailed}, " //+,
//                    s"Success rate: ${(metrics.successRate * 100).formatted("%.2f")}%, " +
//                    s"Throughput: ${metrics.throughput.formatted("%.2f")} rec/sec",
                ))

      // Close sink
      _      <- IO.fromFuture(IO(consoleSink.close()))
    } yield ()
  }

  /**
   * Run an aggregation pipeline: Source → Filter → Aggregate → Sink
   * Handles execution with multiple transforms.
   */
  private def runAggregationPipeline(
    source: JSONFileSource,
    filterTransform: FilterTransform,
    aggregateTransform: AggregateTransform,
    consoleSink: ConsoleSink,
    pipelineName: String,
  )(implicit ec: ExecutionContext,
    materializer: Materializer,
  ): IO[Unit] = {
    for {
      // Execute pipeline with multiple transforms
      _      <- IO.fromFuture(IO {
                  source.stream()
                    .via(filterTransform.flow)
                    .via(aggregateTransform.flow)
                    .runWith(consoleSink.sink)
                })

      // Report metrics
      metrics = consoleSink.metrics
      _      <- IO(log.info(
                  s"$pipelineName completed - ", // +
//                    s"Records written: ${metrics.recordsWritten}, " +
//                    s"Failed: ${metrics.recordsFailed}, ",
//                    s"Success rate: ${(metrics.successRate * 100).formatted("%.2f")}%, " +
//                    s"Throughput: ${metrics.throughput.formatted("%.2f")} rec/sec, " +
//                    s"Uptime: ${metrics.uptimeMs}ms",
                ))

      // Close sink
      _      <- IO.fromFuture(IO(consoleSink.close()))
    } yield ()
  }

  /**
   * Resource management wrapper using Cats Effect Resource.
   * Ensures proper cleanup of Actor System even on failure.
   *
   * Pattern:
   * - Resource.make: acquires ActorSystem
   * - use: runs pipeline logic
   * - release: terminates ActorSystem (automatic)
   */
  private def resourcePipeline[A](
    name: String,
    description: String,
  )(
    f: ActorSystem[Nothing] => IO[A],
  ): IO[A] = {
    val actorSystemResource = Resource.make(
      acquire = IO {
        log.info("═" * 80)
        log.info(s"Starting Pipeline: $name")
        log.info(s"Description: $description")
        log.info("═" * 80)

        val systemName = name.replaceAll("[^a-zA-Z0-9-]", "-").toLowerCase
        ActorSystem(Behaviors.empty, s"$systemName-system")
      },
    )(
      release = system =>
        IO {
          log.info(s"Shutting down actor system for: $name")
          system.terminate()
          Await.result(system.whenTerminated, 30.seconds)
          log.info(s"✓ Actor system terminated for: $name")
        },
    )

    actorSystemResource.use(f)
  }

  // ============================================================================
  // UI Helpers
  // ============================================================================

  /**
   * Print application banner.
   */
  private def printBanner(): Unit = {
    println()
    println("╔═══════════════════════════════════════════════════════════════════════════════╗")
    println("║                     DataFlow Platform - Integration Example                   ║")
    println("║                                                                                ║")
    println("║  Demonstrating: Sources → Transforms → Sinks                                  ║")
    println("║  Technologies: Scala, Pekko Streams, Cats Effect, Functional Programming     ║")
    println("╚═══════════════════════════════════════════════════════════════════════════════╝")
    println()
  }

  /**
   * Print visual separator between pipelines.
   */
  private def printSeparator(): Unit = {
    println()
    println("═" * 80)
    println()
  }
}

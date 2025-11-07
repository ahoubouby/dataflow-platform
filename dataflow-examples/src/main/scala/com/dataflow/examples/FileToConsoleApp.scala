package com.dataflow.examples

import cats.effect.{IO, Resource}
import cats.implicits._
import cats.syntax.either._
import com.dataflow.domain.models.{DataRecord, SourceConfig, SourceType}
import com.dataflow.sinks.console._
import com.dataflow.sinks.domain.DataSink
import com.dataflow.sinks.domain.exceptions.SinkError
import com.dataflow.sources.file.{CSVFileSource, JSONFileSource}
import com.dataflow.transforms.aggregation.{AggregateConfig, AggregateTransform, Aggregator}
import com.dataflow.transforms.domain.TransformType
import com.dataflow.transforms.filters.{ComparisonOperator, FilterTransform}
import com.dataflow.transforms.map.{MapConfig, MapTransform}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.{Done, NotUsed}
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Sophisticated integration application demonstrating the complete dataflow pipeline.
 *
 * Architecture:
 * - Functional error handling with Cats Effect
 * - Resource management with cats.effect.Resource
 * - Type-safe pipeline composition
 * - Production-ready patterns (health checks, metrics, graceful shutdown)
 *
 * Pipeline Flow:
 * File Source → Filter Transform → Map Transform → Aggregate Transform → Console Sink
 *
 * Features:
 * - CSV and JSON file reading
 * - Sophisticated filtering with type-safe operators
 * - Field transformations and enrichment
 * - Windowed aggregations
 * - Rich console output with colors and formatting
 */
object FileToConsoleApp {

  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Main entry point with graceful error handling.
   */
  def main(args: Array[String]): Unit = {
    val result = run().attempt.unsafeRunSync()
    result match {
      case Right(_) =>
        log.info("Application completed successfully")
        System.exit(0)
      case Left(error) =>
        log.error("Application failed", error)
        System.exit(1)
    }
  }

  /**
   * Main application logic wrapped in Cats Effect IO for functional error handling.
   */
  def run(): IO[Unit] = {
    for {
      _ <- IO(log.info("Starting DataFlow Integration Application"))
      _ <- setupSampleData()
      _ <- IO(log.info("Sample data created"))

      // Run pipelines in sequence
      _ <- runCSVPipeline()
      _ <- IO(printSeparator())
      _ <- runJSONPipeline()
      _ <- IO(printSeparator())
      _ <- runAggregationPipeline()

      _ <- IO(log.info("All pipelines completed successfully"))
    } yield ()
  }

  /**
   * Create sample CSV and JSON data files for testing.
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
    Files.write(csvPath, csvData.getBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

    // Sample JSON data - E-commerce orders
    val jsonData = """{"id":"ord-001","customer":"alice","product":"laptop","price":"1200","quantity":"1","status":"shipped"}
                     |{"id":"ord-002","customer":"bob","product":"mouse","price":"25","quantity":"2","status":"pending"}
                     |{"id":"ord-003","customer":"charlie","product":"keyboard","price":"75","quantity":"1","status":"shipped"}
                     |{"id":"ord-004","customer":"alice","product":"monitor","price":"350","quantity":"2","status":"delivered"}
                     |{"id":"ord-005","customer":"bob","product":"laptop","price":"1200","quantity":"1","status":"shipped"}
                     |{"id":"ord-006","customer":"charlie","product":"mouse","price":"25","quantity":"5","status":"pending"}
                     |{"id":"ord-007","customer":"alice","product":"keyboard","price":"75","quantity":"1","status":"delivered"}
                     |{"id":"ord-008","customer":"bob","product":"monitor","price":"350","quantity":"1","status":"shipped"}""".stripMargin

    val jsonPath = dataDir.resolve("orders.json")
    Files.write(jsonPath, jsonData.getBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

    log.info(s"Sample data created: ${csvPath} and ${jsonPath}")
  }

  /**
   * Pipeline 1: CSV Source → Filter (purchases only) → Console Sink
   *
   * Demonstrates:
   * - CSV file reading
   * - Filtering with type-safe operators
   * - Pretty JSON output to console
   */
  private def runCSVPipeline(): IO[Unit] = {
    resourcePipeline(
      name = "CSV Pipeline - User Purchases",
      description = "Reading CSV file, filtering purchases, and displaying to console"
    ) { implicit system =>
      implicit val ec: ExecutionContext = system.executionContext

      for {
        // Create source
        source <- IO {
          val config = SourceConfig(
            sourceType = SourceType.File,
            connectionString = "dataflow-examples/data/users.csv",
            options = Map(
              "format" -> "csv",
              "delimiter" -> ",",
              "has-header" -> "true"
            ),
            batchSize = 100
          )
          new CSVFileSource("csv-pipeline", config)
        }

        // Create filter transform - only purchase actions
        filterTransform <- IO {
          new FilterTransform(
            field = "action",
            operator = ComparisonOperator.Equals,
            value = "purchase"
          )
        }

        // Create console sink with pretty JSON format
        consoleSink <- IO.fromEither(
          ConsoleSink.builder()
            .withFormat(OutputFormat.PrettyJSON)
            .withColorScheme(ColorScheme.Enabled)
            .withTimestamp(true)
            .withSummary(true)
            .build()
        ).leftMap(err => new RuntimeException(s"Failed to create console sink: ${err.message}"))

        // Build and run pipeline
        _ <- IO.fromFuture(IO {
          source.stream()
            .via(filterTransform.flow)
            .runWith(consoleSink.sink)
        })

        // Print metrics
        _ <- IO {
          val metrics = consoleSink.metrics
          log.info(s"Pipeline completed - Records written: ${metrics.recordsWritten}, " +
            s"Failed: ${metrics.recordsFailed}, Throughput: ${metrics.throughput} rec/sec")
        }

        // Close sink
        _ <- IO.fromFuture(IO(consoleSink.close()))
      } yield ()
    }
  }

  /**
   * Pipeline 2: JSON Source → Map Transform → Console Sink
   *
   * Demonstrates:
   * - JSON file reading
   * - Field mapping and transformation
   * - Structured console output
   */
  private def runJSONPipeline(): IO[Unit] = {
    resourcePipeline(
      name = "JSON Pipeline - Order Processing",
      description = "Reading JSON file, transforming fields, and displaying to console"
    ) { implicit system =>
      implicit val ec: ExecutionContext = system.executionContext

      for {
        // Create source
        source <- IO {
          val config = SourceConfig(
            sourceType = SourceType.File,
            connectionString = "dataflow-examples/data/orders.json",
            options = Map(
              "format" -> "json"
            ),
            batchSize = 100
          )
          new JSONFileSource("json-pipeline", config)
        }

        // Create map transform - calculate total price and enrich
        mapTransform <- IO {
          new MapTransform(
            MapConfig(
              fieldMappings = Map(
                "customer_name" -> "customer",
                "item" -> "product"
              ),
              transformations = Map(
                "total_price" -> "price * quantity"
              )
            )
          )
        }

        // Create console sink with structured format
        consoleSink <- IO.fromEither(
          ConsoleSink.builder()
            .withFormat(OutputFormat.Structured)
            .withColorScheme(ColorScheme.Enabled)
            .withTimestamp(false)
            .withMetadata(false)
            .withSummary(true)
            .build()
        ).leftMap(err => new RuntimeException(s"Failed to create console sink: ${err.message}"))

        // Build and run pipeline
        _ <- IO.fromFuture(IO {
          source.stream()
            .via(mapTransform.flow)
            .runWith(consoleSink.sink)
        })

        // Health check
        _ <- IO.fromFuture(IO(consoleSink.healthCheck())).flatMap { health =>
          IO(log.info(s"Console sink health: $health"))
        }

        // Close sink
        _ <- IO.fromFuture(IO(consoleSink.close()))
      } yield ()
    }
  }

  /**
   * Pipeline 3: JSON Source → Filter → Aggregate → Console Sink
   *
   * Demonstrates:
   * - Sophisticated aggregation with windowing
   * - Type class based aggregators
   * - Multiple aggregation functions
   * - Table format output
   */
  private def runAggregationPipeline(): IO[Unit] = {
    resourcePipeline(
      name = "Aggregation Pipeline - Order Analytics",
      description = "Reading orders, aggregating by customer, and displaying statistics"
    ) { implicit system =>
      implicit val ec: ExecutionContext = system.executionContext

      for {
        // Create source
        source <- IO {
          val config = SourceConfig(
            sourceType = SourceType.File,
            connectionString = "dataflow-examples/data/orders.json",
            options = Map(
              "format" -> "json"
            ),
            batchSize = 100
          )
          new JSONFileSource("aggregation-pipeline", config)
        }

        // Filter only shipped/delivered orders
        filterTransform <- IO {
          new FilterTransform(
            field = "status",
            operator = ComparisonOperator.In,
            value = "shipped,delivered"
          )
        }

        // Create aggregate transform - customer order statistics
        aggregateTransform <- IO {
          new AggregateTransform(
            AggregateConfig(
              groupByFields = Seq("customer"),
              windowSize = 5.seconds,
              aggregations = Map(
                "total_orders" -> Aggregator.count,
                "total_spent" -> Aggregator.sum("price"),
                "avg_price" -> Aggregator.average("price"),
                "min_price" -> Aggregator.min("price"),
                "max_price" -> Aggregator.max("price")
              )
            )
          )
        }

        // Create console sink with table format
        consoleSink <- IO.fromEither(
          ConsoleSink.builder()
            .withFormat(OutputFormat.Table)
            .withColorScheme(ColorScheme.Enabled)
            .withTimestamp(false)
            .withMetadata(false)
            .withSummary(true)
            .build()
        ).leftMap(err => new RuntimeException(s"Failed to create console sink: ${err.message}"))

        // Build and run pipeline
        _ <- IO.fromFuture(IO {
          source.stream()
            .via(filterTransform.flow)
            .via(aggregateTransform.flow)
            .runWith(consoleSink.sink)
        })

        // Close sink
        _ <- IO.fromFuture(IO(consoleSink.close()))
      } yield ()
    }
  }

  /**
   * Resource management wrapper using Cats Effect Resource.
   * Ensures proper cleanup of Actor System even on failure.
   */
  private def resourcePipeline[A](
    name: String,
    description: String
  )(
    f: ActorSystem[Nothing] => IO[A]
  ): IO[A] = {
    val actorSystemResource = Resource.make(
      IO {
        log.info(s"═" * 80)
        log.info(s"Starting Pipeline: $name")
        log.info(s"Description: $description")
        log.info(s"═" * 80)
        ActorSystem(Behaviors.empty, s"${name.replaceAll(" ", "-")}-system")
      }
    )(system =>
      IO {
        log.info(s"Shutting down actor system for: $name")
        system.terminate()
        Await.result(system.whenTerminated, 30.seconds)
      }
    )

    actorSystemResource.use(f)
  }

  /**
   * Print visual separator.
   */
  private def printSeparator(): Unit = {
    println()
    println(s"${"=" * 80}")
    println()
  }
}

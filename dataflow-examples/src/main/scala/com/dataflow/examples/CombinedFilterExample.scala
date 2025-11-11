package com.dataflow.examples

import cats.effect.{IO, Resource}
import com.dataflow.domain.models.{DataRecord, SourceConfig, SourceType}
import com.dataflow.sinks.console.{ColorScheme, ConsoleSink, OutputFormat}
import com.dataflow.sinks.domain.exceptions.SinkError
import com.dataflow.sources.file.CSVFileSource
import com.dataflow.transforms.domain.{ErrorHandlingStrategy, FilterConfig}
import com.dataflow.transforms.filters.FilterTransformV2
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

/**
 * Example demonstrating combined filter expressions with AND, OR, NOT operators.
 *
 * This example shows:
 * 1. Simple filters: "status == active"
 * 2. AND filters: "status == active AND age > 18"
 * 3. OR filters: "type == premium OR level >= 5"
 * 4. Complex filters: "(status == active AND age > 18) OR level >= 10"
 * 5. NOT filters: "NOT status == inactive"
 *
 * The example creates sample data and runs multiple pipelines demonstrating
 * each type of filter expression.
 */
object CombinedFilterExample {

  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    import cats.effect.unsafe.implicits.global

    printBanner()
    val result = run().attempt.unsafeRunSync()

    result match {
      case Right(_) =>
        log.info("✓ All combined filter examples completed successfully")
        System.exit(0)
      case Left(error) =>
        log.error("✗ Combined filter examples failed", error)
        System.exit(1)
    }
  }

  def run(): IO[Unit] = {
    for {
      _ <- IO(log.info("Starting Combined Filter Examples"))
      _ <- setupSampleData()
      _ <- IO(printSeparator())

      // Example 1: Simple filter
      _ <- runSimpleFilterExample()
      _ <- IO(printSeparator())

      // Example 2: AND filter
      _ <- runAndFilterExample()
      _ <- IO(printSeparator())

      // Example 3: OR filter
      _ <- runOrFilterExample()
      _ <- IO(printSeparator())

      // Example 4: Complex filter with parentheses
      _ <- runComplexFilterExample()
      _ <- IO(printSeparator())

      // Example 5: NOT filter
      _ <- runNotFilterExample()
      _ <- IO(printSeparator())

      _ <- IO(log.info("✓ All examples completed"))
    } yield ()
  }

  /**
   * Create sample data with various user profiles for testing filters.
   */
  private def setupSampleData(): IO[Unit] = IO {
    val dataDir = Paths.get("dataflow-examples/data")
    if (!Files.exists(dataDir)) {
      Files.createDirectories(dataDir)
    }

    // Sample user data with various attributes
    val userData = """id,name,status,age,type,level,verified
                     |1,Alice,active,25,premium,8,true
                     |2,Bob,inactive,17,basic,2,false
                     |3,Charlie,active,19,premium,5,true
                     |4,Diana,active,16,basic,1,false
                     |5,Eve,pending,22,premium,10,true
                     |6,Frank,active,30,basic,3,true
                     |7,Grace,inactive,28,premium,7,false
                     |8,Henry,active,20,basic,12,true
                     |9,Iris,active,35,premium,15,true
                     |10,Jack,inactive,18,basic,4,false""".stripMargin

    val csvPath = dataDir.resolve("users_filter_test.csv")
    Files.write(
      csvPath,
      userData.getBytes,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING
    )

    log.info(s"Sample data created at: $csvPath")
  }

  /**
   * Example 1: Simple filter
   * Filter: "status == active"
   * Expected: Users with active status (7 users)
   */
  private def runSimpleFilterExample(): IO[Unit] = {
    runFilterPipeline(
      name = "Example 1: Simple Filter",
      description = "Filter users with active status",
      expression = "status == active"
    )
  }

  /**
   * Example 2: AND filter
   * Filter: "status == active AND age > 18"
   * Expected: Active users over 18 years old (5 users)
   */
  private def runAndFilterExample(): IO[Unit] = {
    runFilterPipeline(
      name = "Example 2: AND Filter",
      description = "Filter active users over 18",
      expression = "status == active AND age > 18"
    )
  }

  /**
   * Example 3: OR filter
   * Filter: "type == premium OR level >= 10"
   * Expected: Premium users or users with level >= 10 (7 users)
   */
  private def runOrFilterExample(): IO[Unit] = {
    runFilterPipeline(
      name = "Example 3: OR Filter",
      description = "Filter premium users or high-level users",
      expression = "type == premium OR level >= 10"
    )
  }

  /**
   * Example 4: Complex filter with parentheses
   * Filter: "(status == active AND age > 18) OR level >= 10"
   * Expected: (Active users over 18) OR (users with level >= 10) (6 users)
   */
  private def runComplexFilterExample(): IO[Unit] = {
    runFilterPipeline(
      name = "Example 4: Complex Filter",
      description = "Filter (active users over 18) OR (high-level users)",
      expression = "(status == active AND age > 18) OR level >= 10"
    )
  }

  /**
   * Example 5: NOT filter
   * Filter: "NOT status == inactive"
   * Expected: All users except inactive ones (7 users)
   */
  private def runNotFilterExample(): IO[Unit] = {
    runFilterPipeline(
      name = "Example 5: NOT Filter",
      description = "Filter all users except inactive",
      expression = "NOT status == inactive"
    )
  }

  /**
   * Generic pipeline runner for filter examples.
   */
  private def runFilterPipeline(
    name: String,
    description: String,
    expression: String
  ): IO[Unit] = {
    resourcePipeline(name, description) { implicit system =>
      implicit val ec: ExecutionContext = system.executionContext

      for {
        // Create CSV source
        source <- IO {
          val config = SourceConfig(
            sourceType = SourceType.File,
            connectionString = "dataflow-examples/data/users_filter_test.csv",
            options = Map(
              "format" -> "csv",
              "delimiter" -> ",",
              "has-header" -> "true"
            ),
            batchSize = 100
          )
          new CSVFileSource("filter-example", config)
        }

        // Create filter transform with the test expression
        filterTransform <- IO.fromEither(
          FilterTransformV2.create(expression, ErrorHandlingStrategy.Skip)
        ).leftMap(err => new RuntimeException(s"Failed to create filter: ${err.message}"))

        // Create console sink with table format
        consoleSink <- IO.fromEither(
          ConsoleSink.builder()
            .withFormat(OutputFormat.Table)
            .withColorScheme(ColorScheme.Enabled)
            .withTimestamp(false)
            .withMetadata(false)
            .withSummary(true)
            .build()
        ).leftMap(err => new RuntimeException(s"Failed to create sink: ${err.message}"))

        // Run pipeline
        _ <- IO.fromFuture(IO {
          source.stream()
            .via(filterTransform.flow)
            .runWith(consoleSink.sink)
        })

        // Display results
        metrics <- IO(consoleSink.metrics)
        stats <- IO(filterTransform.stats)
        _ <- IO {
          log.info(s"Filter Expression: $expression")
          log.info(s"Total records processed: ${stats.totalProcessed}")
          log.info(s"Matched records: ${stats.matchedCount}")
          log.info(s"Filtered out: ${stats.filteredCount}")
          log.info(s"Errors: ${stats.errorCount}")
          log.info(s"Records displayed: ${metrics.recordsWritten}")
        }

        // Close sink
        _ <- IO.fromFuture(IO(consoleSink.close()))
      } yield ()
    }
  }

  /**
   * Resource management wrapper.
   */
  private def resourcePipeline[A](
    name: String,
    description: String
  )(
    f: ActorSystem[Nothing] => IO[A]
  ): IO[A] = {
    val actorSystemResource = Resource.make(
      IO {
        log.info("═" * 80)
        log.info(s"$name")
        log.info(s"$description")
        log.info("═" * 80)
        val systemName = name.replaceAll("[^a-zA-Z0-9-]", "-").toLowerCase
        ActorSystem(Behaviors.empty, s"$systemName-system")
      }
    )(system =>
      IO {
        system.terminate()
        Await.result(system.whenTerminated, 30.seconds)
      }
    )

    actorSystemResource.use(f)
  }

  private def printBanner(): Unit = {
    println()
    println("╔═══════════════════════════════════════════════════════════════════════════════╗")
    println("║             Combined Filter Expressions - Examples & Demonstrations           ║")
    println("║                                                                               ║")
    println("║  Demonstrates: AND, OR, NOT operators with complex expressions                ║")
    println("╚═══════════════════════════════════════════════════════════════════════════════╝")
    println()
  }

  private def printSeparator(): Unit = {
    println()
    println("═" * 80)
    println()
  }
}

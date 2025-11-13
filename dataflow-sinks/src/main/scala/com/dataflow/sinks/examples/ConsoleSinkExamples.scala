package com.dataflow.sinks.examples

import com.dataflow.domain.models.DataRecord
import com.dataflow.sinks.console._
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.Materializer

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Random

/**
 * Comprehensive examples demonstrating all ConsoleSink features.
 */
object ConsoleSinkExamples extends App {

  implicit val system: ActorSystem = ActorSystem("ConsoleSinkExamples")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val mat: Materializer = Materializer(system)

  println("=" * 80)
  println("ConsoleSink Examples - Demonstrating All Features")
  println("=" * 80)
  println()

  // ============================================================================
  // Example 1: Pretty JSON Format (Default)
  // ============================================================================

  def example1PrettyJson(): Future[Done] = {
    println("Example 1: Pretty JSON Format with ANSI Colors")
    println("-" * 80)

    val config = ConsoleSinkConfig.default
    val sink = new ConsoleSink(config)

    val records = (1 to 3).map { i =>
      DataRecord(
        id = s"record-$i",
        data = Map(
          "name" -> s"User $i",
          "email" -> s"user$i@example.com",
          "age" -> (20 + i * 5).toString
        ),
        metadata = Map(
          "source" -> "api",
          "version" -> "1.0"
        )
      )
    }

    Source(records)
      .runWith(sink.sink)
      .flatMap { _ =>
        println()
        sink.healthCheck().flatMap { status =>
          println(s"Health Status: $status")
          println(s"Metrics: ${sink.metrics}")
          sink.close()
        }
      }
  }

  // ============================================================================
  // Example 2: Compact JSON Format
  // ============================================================================

  def example2CompactJson(): Future[Done] = {
    println("\nExample 2: Compact JSON Format (Single Line)")
    println("-" * 80)

    val result = for {
      config <- ConsoleSinkConfig.create(
        format = OutputFormat.CompactJSON,
        showTimestamp = false,
        printSummary = false
      )
      sink <- ConsoleSink.create(config)
    } yield sink

    result match {
      case Right(sink) =>
        val records = (1 to 2).map { i =>
          DataRecord(
            id = s"log-$i",
            data = Map("level" -> "INFO", "message" -> s"Processing item $i"),
            metadata = Map.empty
          )
        }

        Source(records)
          .runWith(sink.sink)
          .flatMap(_ => sink.close())

      case Left(error) =>
        Future.failed(new RuntimeException(error.message))
    }
  }

  // ============================================================================
  // Example 3: Simple Format (Human-Readable)
  // ============================================================================

  def example3SimpleFormat(): Future[Done] = {
    println("\nExample 3: Simple Format (Human-Readable)")
    println("-" * 80)

    ConsoleSink.builder()
      .withFormat(OutputFormat.Simple)
      .withTimestamp(true)
      .withMaxFieldWidth(50)
      .build() match {
      case Right(sink) =>
        val records = (1 to 2).map { i =>
          DataRecord(
            id = s"txn-$i",
            data = Map(
              "transaction_id" -> s"TXN${1000 + i}",
              "amount" -> s"${100 * i}.00",
              "currency" -> "USD"
            ),
            metadata = Map("region" -> "US-WEST")
          )
        }

        Source(records)
          .runWith(sink.sink)
          .flatMap(_ => sink.close())

      case Left(error) =>
        Future.failed(new RuntimeException(error.message))
    }
  }

  // ============================================================================
  // Example 4: Table Format
  // ============================================================================

  def example4TableFormat(): Future[Done] = {
    println("\nExample 4: Table Format (ASCII Table)")
    println("-" * 80)

    val config = ConsoleSinkConfig.create(
      format = OutputFormat.Table,
      showTimestamp = false,
      printSummary = false
    ).toOption.get

    val sink = new ConsoleSink(config)

    val record = DataRecord(
      id = "product-001",
      data = Map(
        "name" -> "Laptop",
        "price" -> "999.99",
        "stock" -> "15",
        "category" -> "Electronics"
      ),
      metadata = Map(
        "warehouse" -> "WH-001",
        "updated_at" -> "2025-11-07"
      )
    )

    Source.single(record)
      .runWith(sink.sink)
      .flatMap(_ => sink.close())
  }

  // ============================================================================
  // Example 5: Structured Format (Log-Style)
  // ============================================================================

  def example5StructuredFormat(): Future[Done] = {
    println("\nExample 5: Structured Format (Log-Style Key=Value)")
    println("-" * 80)

    val config = ConsoleSinkConfig.create(
      format = OutputFormat.Structured,
      colorScheme = ColorScheme.Enabled,
      printSummary = false
    ).toOption.get

    val sink = new ConsoleSink(config)

    val records = (1 to 2).map { i =>
      DataRecord(
        id = s"event-$i",
        data = Map(
          "event_type" -> "user_login",
          "user_id" -> s"user_${i}",
          "ip_address" -> s"192.168.1.${i}"
        ),
        metadata = Map("severity" -> "info")
      )
    }

    Source(records)
      .runWith(sink.sink)
      .flatMap(_ => sink.close())
  }

  // ============================================================================
  // Example 6: KeyValue Format (Multi-Line)
  // ============================================================================

  def example6KeyValueFormat(): Future[Done] = {
    println("\nExample 6: KeyValue Format (Multi-Line Display)")
    println("-" * 80)

    val config = ConsoleSinkConfig.create(
      format = OutputFormat.KeyValue,
      showTimestamp = true,
      showMetadata = true,
      printSummary = false
    ).toOption.get

    val sink = new ConsoleSink(config)

    val record = DataRecord(
      id = "order-12345",
      data = Map(
        "customer_name" -> "John Doe",
        "order_total" -> "1,250.00",
        "items_count" -> "5",
        "status" -> "confirmed"
      ),
      metadata = Map(
        "created_by" -> "api_gateway",
        "request_id" -> "req-abc-123"
      )
    )

    Source.single(record)
      .runWith(sink.sink)
      .flatMap(_ => sink.close())
  }

  // ============================================================================
  // Example 7: No Colors (CI/CD Environment)
  // ============================================================================

  def example7NoColors(): Future[Done] = {
    println("\nExample 7: Disabled Colors (CI/CD Environment)")
    println("-" * 80)

    val config = ConsoleSinkConfig.create(
      format = OutputFormat.Simple,
      colorScheme = ColorScheme.Disabled,
      printSummary = false
    ).toOption.get

    val sink = new ConsoleSink(config)

    val record = DataRecord(
      id = "build-001",
      data = Map(
        "status" -> "success",
        "duration" -> "45s",
        "tests_passed" -> "127"
      ),
      metadata = Map("branch" -> "main")
    )

    Source.single(record)
      .runWith(sink.sink)
      .flatMap(_ => sink.close())
  }

  // ============================================================================
  // Example 8: StdErr Output
  // ============================================================================

  def example8StdErr(): Future[Done] = {
    println("\nExample 8: Writing to StdErr")
    println("-" * 80)

    val config = ConsoleSinkConfig.create(
      format = OutputFormat.Simple,
      target = OutputTarget.StdErr,
      printSummary = false
    ).toOption.get

    val sink = new ConsoleSink(config)

    val record = DataRecord(
      id = "error-001",
      data = Map(
        "error_type" -> "ValidationError",
        "message" -> "Invalid email format"
      ),
      metadata = Map("severity" -> "error")
    )

    Source.single(record)
      .runWith(sink.sink)
      .flatMap(_ => sink.close())
  }

  // ============================================================================
  // Example 9: High-Throughput with Summary
  // ============================================================================

  def example9HighThroughput(): Future[Done] = {
    println("\nExample 9: High-Throughput Stream with Summary")
    println("-" * 80)

    val config = ConsoleSinkConfig.create(
      format = OutputFormat.CompactJSON,
      flushInterval = 50,
      printSummary = true,
      showTimestamp = false
    ).toOption.get

    val sink = new ConsoleSink(config)

    val records = (1 to 100).map { i =>
      DataRecord(
        id = f"rec-$i%04d",
        data = Map(
          "sequence" -> i.toString,
          "value" -> Random.nextDouble().toString
        ),
        metadata = Map.empty
      )
    }

    Source(records)
      .throttle(10, 100.milliseconds)
      .runWith(sink.sink)
      .flatMap { _ =>
        Thread.sleep(100) // Give time for summary to print
        sink.close()
      }
  }

  // ============================================================================
  // Example 10: Error Handling and Health Checks
  // ============================================================================

  def example10HealthChecks(): Future[Unit] = {
    println("\nExample 10: Health Checks and Metrics")
    println("-" * 80)

    val config = ConsoleSinkConfig.create(
      format = OutputFormat.Simple,
      printSummary = false
    ).toOption.get

    val sink = new ConsoleSink(config)

    val records = (1 to 10).map { i =>
      DataRecord(
        id = s"health-check-$i",
        data = Map("status" -> "ok", "value" -> i.toString),
        metadata = Map.empty
      )
    }

    for {
      _ <- Source.fromIterator(() => records.iterator).runWith(sink.sink)
      healthBefore <- sink.healthCheck()
      _ = println(s"\nHealth Status: $healthBefore")
      metrics = sink.metrics
      _ = println(s"Metrics:")
      _ = println(s"  - Records Written: ${metrics.recordsWritten}")
      _ = println(s"  - Records Failed: ${metrics.recordsFailed}")
     //  _ = println(s"  - Success Rate: ${(metrics.successRate * 100).formatted("%.2f")}%")
      // _ = println(s"  - Throughput: ${metrics.throughput.formatted("%.2f")} records/sec")
      // _ = println(s"  - Uptime: ${metrics.uptimeMs}ms")
      _ <- sink.close()
    } yield ()
  }

  // ============================================================================
  // Example 11: Configuration Validation
  // ============================================================================

  def example11ConfigValidation(): Unit = {
    println("\nExample 11: Configuration Validation")
    println("-" * 80)

    // Invalid maxFieldWidth
    val result1 = ConsoleSinkConfig.create(maxFieldWidth = -1)
    println(s"Invalid maxFieldWidth: ${result1.left.map(_.message)}")

    // Invalid bufferSize
    val result2 = ConsoleSinkConfig.create(bufferSize = 0)
    println(s"Invalid bufferSize: ${result2.left.map(_.message)}")

    // Invalid flushInterval
    val result3 = ConsoleSinkConfig.create(flushInterval = -5)
    println(s"Invalid flushInterval: ${result3.left.map(_.message)}")

    // Valid configuration
    val result4 = ConsoleSinkConfig.create(maxFieldWidth = 100)
    println(s"Valid config: ${result4.isRight}")
    println()
  }

  // ============================================================================
  // Run All Examples
  // ============================================================================

  val examples = for {
    _ <- example1PrettyJson()
    _ <- example2CompactJson()
    _ <- example3SimpleFormat()
    _ <- example4TableFormat()
    _ <- example5StructuredFormat()
    _ <- example6KeyValueFormat()
    _ <- example7NoColors()
    _ <- example8StdErr()
    _ <- example9HighThroughput()
    _ <- example10HealthChecks()
    _ = example11ConfigValidation()
  } yield ()

  examples.onComplete { result =>
    println("\n" + "=" * 80)
    println(s"All examples completed: ${result.isSuccess}")
    println("=" * 80)
    system.terminate()
  }

  Await.result(system.whenTerminated, 30.seconds)
}
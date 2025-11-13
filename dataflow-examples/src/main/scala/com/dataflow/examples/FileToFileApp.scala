//package com.dataflow.examples
//
//import cats.effect.{IO, Resource}
//import cats.implicits._
//import com.dataflow.domain.models.{DataRecord, SourceConfig, SourceType}
//import com.dataflow.sinks.domain.{BatchConfig, DataSink}
//import com.dataflow.sinks.file.{FileFormat, FileSink, FileSinkConfig}
//import com.dataflow.sources.file.{CSVFileSource, JSONFileSource}
//import com.dataflow.transforms.aggregation.{AggregateConfig, AggregateTransform, Aggregator}
//import com.dataflow.transforms.filters.{ComparisonOperator, FilterTransform}
//import com.dataflow.transforms.map.{MapConfig, MapTransform}
//import org.apache.pekko.actor.typed.ActorSystem
//import org.apache.pekko.actor.typed.scaladsl.Behaviors
//import org.apache.pekko.{Done, NotUsed}
//import org.slf4j.LoggerFactory
//
//import java.nio.file.{Files, Paths, StandardOpenOption}
//import scala.concurrent.duration._
//import scala.concurrent.{Await, ExecutionContext, Future}
//import scala.util.{Failure, Success}
//
///**
// * Sophisticated File-to-File integration demonstrating complete ETL pipelines.
// *
// * Architecture:
// * - Functional error handling with Cats Effect
// * - Type-safe file format handling (CSV, JSON, JSONL)
// * - Production patterns (batching, rotation, compression)
// * - Resource management with automatic cleanup
// *
// * Pipeline Flows:
// * 1. CSV → Filter → Transform → JSONL File
// * 2. JSON → Aggregate → CSV File
// * 3. CSV → Multi-stage Transform → Compressed JSON File
// *
// * Features:
// * - File rotation by size
// * - GZIP compression support
// * - Batched writes for performance
// * - Comprehensive metrics tracking
// * - Graceful error handling
// */
//object FileToFileApp {
//
//  private val log = LoggerFactory.getLogger(getClass)
//
//  def main(args: Array[String]): Unit = {
//    val result = run().attempt.unsafeRunSync()
//    result match {
//      case Right(_) =>
//        log.info("All file-to-file pipelines completed successfully")
//        System.exit(0)
//      case Left(error) =>
//        log.error("File-to-file application failed", error)
//        System.exit(1)
//    }
//  }
//
//  /**
//   * Main application logic with functional error handling.
//   */
//  def run(): IO[Unit] = {
//    for {
//      _ <- IO(log.info("Starting File-to-File ETL Pipelines"))
//      _ <- setupSampleData()
//      _ <- setupOutputDirectories()
//
//      // Run ETL pipelines
//      _ <- runETLPipeline1()
//      _ <- IO(printSeparator())
//      _ <- runETLPipeline2()
//      _ <- IO(printSeparator())
//      _ <- runETLPipeline3()
//
//      _ <- IO(log.info("All ETL pipelines completed successfully"))
//      _ <- displayOutputSummary()
//    } yield ()
//  }
//
//  /**
//   * Setup sample input data files.
//   */
//  private def setupSampleData(): IO[Unit] = IO {
//    val dataDir = Paths.get("dataflow-examples/data")
//    if (!Files.exists(dataDir)) {
//      Files.createDirectories(dataDir)
//    }
//
//    // E-commerce transactions data
//    val transactionsData = """transaction_id,customer_id,product,amount,category,timestamp,status
//                             |txn-001,cust-101,Laptop,1200,Electronics,2024-01-15T10:00:00,completed
//                             |txn-002,cust-102,Coffee Maker,85,Appliances,2024-01-15T10:15:00,completed
//                             |txn-003,cust-103,Book,25,Books,2024-01-15T10:30:00,pending
//                             |txn-004,cust-101,Mouse,35,Electronics,2024-01-15T10:45:00,completed
//                             |txn-005,cust-104,Headphones,150,Electronics,2024-01-15T11:00:00,completed
//                             |txn-006,cust-102,Blender,120,Appliances,2024-01-15T11:15:00,failed
//                             |txn-007,cust-105,Novel,18,Books,2024-01-15T11:30:00,completed
//                             |txn-008,cust-103,Monitor,350,Electronics,2024-01-15T11:45:00,completed
//                             |txn-009,cust-104,Toaster,45,Appliances,2024-01-15T12:00:00,completed
//                             |txn-010,cust-101,Keyboard,75,Electronics,2024-01-15T12:15:00,completed
//                             |txn-011,cust-106,Cookbook,30,Books,2024-01-15T12:30:00,pending
//                             |txn-012,cust-102,Webcam,90,Electronics,2024-01-15T12:45:00,completed""".stripMargin
//
//    Files.write(
//      dataDir.resolve("transactions.csv"),
//      transactionsData.getBytes,
//      StandardOpenOption.CREATE,
//      StandardOpenOption.TRUNCATE_EXISTING
//    )
//
//    // Product catalog data
//    val catalogData = """{"product_id":"prod-001","name":"Laptop","price":"1200","stock":"15","category":"Electronics","supplier":"TechCorp"}
//                        |{"product_id":"prod-002","name":"Coffee Maker","price":"85","stock":"42","category":"Appliances","supplier":"HomeGoods"}
//                        |{"product_id":"prod-003","name":"Wireless Mouse","price":"35","stock":"128","category":"Electronics","supplier":"TechCorp"}
//                        |{"product_id":"prod-004","name":"Headphones","price":"150","stock":"67","category":"Electronics","supplier":"AudioMax"}
//                        |{"product_id":"prod-005","name":"Blender","price":"120","stock":"23","category":"Appliances","supplier":"HomeGoods"}
//                        |{"product_id":"prod-006","name":"Monitor 27-inch","price":"350","stock":"31","category":"Electronics","supplier":"TechCorp"}
//                        |{"product_id":"prod-007","name":"Toaster","price":"45","stock":"55","category":"Appliances","supplier":"HomeGoods"}
//                        |{"product_id":"prod-008","name":"Mechanical Keyboard","price":"75","stock":"89","category":"Electronics","supplier":"TechCorp"}""".stripMargin
//
//    Files.write(
//      dataDir.resolve("catalog.json"),
//      catalogData.getBytes,
//      StandardOpenOption.CREATE,
//      StandardOpenOption.TRUNCATE_EXISTING
//    )
//
//    log.info("Sample data created in dataflow-examples/data/")
//  }
//
//  /**
//   * Create output directories for processed data.
//   */
//  private def setupOutputDirectories(): IO[Unit] = IO {
//    val outputDir = Paths.get("dataflow-examples/output")
//    if (!Files.exists(outputDir)) {
//      Files.createDirectories(outputDir)
//    }
//    log.info("Output directory ready: dataflow-examples/output/")
//  }
//
//  /**
//   * ETL Pipeline 1: CSV → Filter (Electronics only) → Enrich → JSONL File
//   *
//   * Demonstrates:
//   * - CSV source reading
//   * - Filtering by category
//   * - Field mapping and enrichment
//   * - JSONL file output
//   */
//  private def runETLPipeline1(): IO[Unit] = {
//    resourcePipeline(
//      name = "ETL Pipeline 1: Electronics Transactions",
//      description = "Filter electronics transactions and export to JSONL"
//    ) { implicit system =>
//      implicit val ec: ExecutionContext = system.executionContext
//
//      for {
//        // Create CSV source
//        source <- IO {
//          val config = SourceConfig(
//            sourceType = SourceType.File,
//            connectionString = "dataflow-examples/data/transactions.csv",
//            options = Map(
//              "format" -> "csv",
//              "delimiter" -> ",",
//              "has-header" -> "true"
//            ),
//            batchSize = 100
//          )
//          new CSVFileSource("etl-pipeline-1", config)
//        }
//
//        // Filter only Electronics category
//        filterTransform <- IO {
//          new FilterTransform(
//            field = "category",
//            operator = ComparisonOperator.Equals,
//            value = "Electronics"
//          )
//        }
//
//        // Enrich with computed fields
//        mapTransform <- IO {
//          new MapTransform(
//            MapConfig(
//              fieldMappings = Map(
//                "txn_id" -> "transaction_id",
//                "customer" -> "customer_id"
//              ),
//              transformations = Map(
//                "processing_date" -> "2024-01-15",
//                "department" -> "ELECTRONICS"
//              )
//            )
//          )
//        }
//
//        // Create JSONL file sink
//        fileSink <- IO.fromEither(
//          FileSinkConfig.create(
//            path = "dataflow-examples/output/electronics_transactions.jsonl",
//            format = FileFormat.JSONL,
//            compression = None,
//            rotationSize = Some(10 * 1024 * 1024), // 10MB
//            batchConfig = BatchConfig(maxSize = 50, maxDuration = 5.seconds)
//          )
//        ).leftMap(err => new RuntimeException(s"Failed to create file sink config: ${err.message}"))
//
//        sink <- IO(new FileSink(fileSink))
//
//        // Build and run pipeline
//        _ <- IO.fromFuture(IO {
//          source.stream()
//            .via(filterTransform.flow)
//            .via(mapTransform.flow)
//            .runWith(sink.sink)
//        })
//
//        // Health check and metrics
//        _ <- IO.fromFuture(IO(sink.healthCheck())).flatMap { health =>
//          IO(log.info(s"File sink health: $health"))
//        }
//
//        metrics <- IO(sink.metrics)
//        _ <- IO {
//          log.info(s"Pipeline 1 completed - Records written: ${metrics.recordsWritten}, " +
//            s"Failed: ${metrics.recordsFailed}, Success rate: ${(metrics.successRate * 100).formatted("%.2f")}%")
//        }
//
//        _ <- IO.fromFuture(IO(sink.close()))
//      } yield ()
//    }
//  }
//
//  /**
//   * ETL Pipeline 2: JSON → Aggregate (by category) → CSV File
//   *
//   * Demonstrates:
//   * - JSON source reading
//   * - Aggregation with multiple functions
//   * - CSV file output
//   */
//  private def runETLPipeline2(): IO[Unit] = {
//    resourcePipeline(
//      name = "ETL Pipeline 2: Product Catalog Analytics",
//      description = "Aggregate products by category and export to CSV"
//    ) { implicit system =>
//      implicit val ec: ExecutionContext = system.executionContext
//
//      for {
//        // Create JSON source
//        source <- IO {
//          val config = SourceConfig(
//            sourceType = SourceType.File,
//            connectionString = "dataflow-examples/data/catalog.json",
//            options = Map("format" -> "json"),
//            batchSize = 100
//          )
//          new JSONFileSource("etl-pipeline-2", config)
//        }
//
//        // Aggregate by category
//        aggregateTransform <- IO {
//          new AggregateTransform(
//            AggregateConfig(
//              groupByFields = Seq("category"),
//              windowSize = 3.seconds,
//              aggregations = Map(
//                "total_products" -> Aggregator.count,
//                "avg_price" -> Aggregator.average("price"),
//                "total_stock" -> Aggregator.sum("stock"),
//                "min_price" -> Aggregator.min("price"),
//                "max_price" -> Aggregator.max("price")
//              )
//            )
//          )
//        }
//
//        // Create CSV file sink
//        fileSink <- IO.fromEither(
//          FileSinkConfig.create(
//            path = "dataflow-examples/output/category_analytics.csv",
//            format = FileFormat.CSV,
//            compression = None,
//            batchConfig = BatchConfig(maxSize = 10, maxDuration = 2.seconds)
//          )
//        ).leftMap(err => new RuntimeException(s"Failed to create file sink config: ${err.message}"))
//
//        sink <- IO(new FileSink(fileSink))
//
//        // Build and run pipeline
//        _ <- IO.fromFuture(IO {
//          source.stream()
//            .via(aggregateTransform.flow)
//            .runWith(sink.sink)
//        })
//
//        metrics <- IO(sink.metrics)
//        _ <- IO {
//          log.info(s"Pipeline 2 completed - Aggregated records written: ${metrics.recordsWritten}")
//        }
//
//        _ <- IO.fromFuture(IO(sink.close()))
//      } yield ()
//    }
//  }
//
//  /**
//   * ETL Pipeline 3: CSV → Multi-stage Transform → Compressed JSONL
//   *
//   * Demonstrates:
//   * - Multi-stage transformation pipeline
//   * - Status filtering
//   * - Field transformation
//   * - GZIP compression
//   */
//  private def runETLPipeline3(): IO[Unit] = {
//    resourcePipeline(
//      name = "ETL Pipeline 3: Completed Transactions Archive",
//      description = "Filter completed transactions and archive with compression"
//    ) { implicit system =>
//      implicit val ec: ExecutionContext = system.executionContext
//
//      for {
//        // Create CSV source
//        source <- IO {
//          val config = SourceConfig(
//            sourceType = SourceType.File,
//            connectionString = "dataflow-examples/data/transactions.csv",
//            options = Map(
//              "format" -> "csv",
//              "delimiter" -> ",",
//              "has-header" -> "true"
//            ),
//            batchSize = 100
//          )
//          new CSVFileSource("etl-pipeline-3", config)
//        }
//
//        // Filter only completed transactions
//        statusFilter <- IO {
//          new FilterTransform(
//            field = "status",
//            operator = ComparisonOperator.Equals,
//            value = "completed"
//          )
//        }
//
//        // Filter high-value transactions (amount > 100)
//        amountFilter <- IO {
//          new FilterTransform(
//            field = "amount",
//            operator = ComparisonOperator.GreaterThan,
//            value = "100"
//          )
//        }
//
//        // Transform and enrich
//        mapTransform <- IO {
//          new MapTransform(
//            MapConfig(
//              fieldMappings = Map(
//                "id" -> "transaction_id",
//                "cust_id" -> "customer_id",
//                "value" -> "amount"
//              ),
//              transformations = Map(
//                "archived_at" -> java.time.Instant.now().toString,
//                "archive_version" -> "1.0"
//              )
//            )
//          )
//        }
//
//        // Create compressed JSONL file sink
//        fileSink <- IO.fromEither(
//          FileSinkConfig.create(
//            path = "dataflow-examples/output/high_value_completed.jsonl.gz",
//            format = FileFormat.JSONL,
//            compression = Some("gzip"),
//            rotationSize = Some(5 * 1024 * 1024), // 5MB
//            batchConfig = BatchConfig(maxSize = 100, maxDuration = 3.seconds)
//          )
//        ).leftMap(err => new RuntimeException(s"Failed to create file sink config: ${err.message}"))
//
//        sink <- IO(new FileSink(fileSink))
//
//        // Build and run multi-stage pipeline
//        _ <- IO.fromFuture(IO {
//          source.stream()
//            .via(statusFilter.flow)
//            .via(amountFilter.flow)
//            .via(mapTransform.flow)
//            .runWith(sink.sink)
//        })
//
//        metrics <- IO(sink.metrics)
//        _ <- IO {
//          log.info(s"Pipeline 3 completed - High-value records archived: ${metrics.recordsWritten}")
//          log.info(s"Compression: GZIP enabled")
//        }
//
//        _ <- IO.fromFuture(IO(sink.close()))
//      } yield ()
//    }
//  }
//
//  /**
//   * Display summary of all generated output files.
//   */
//  private def displayOutputSummary(): IO[Unit] = IO {
//    val outputDir = Paths.get("dataflow-examples/output")
//
//    log.info("=" * 80)
//    log.info("Output Files Summary")
//    log.info("=" * 80)
//
//    if (Files.exists(outputDir)) {
//      Files.list(outputDir).forEach { path =>
//        val size = Files.size(path)
//        val sizeKB = size / 1024.0
//        log.info(f"  ${path.getFileName}%-50s ${sizeKB}%8.2f KB")
//      }
//    }
//
//    log.info("=" * 80)
//    log.info("ETL pipelines have processed data into:")
//    log.info("  1. electronics_transactions.jsonl - Filtered electronics transactions")
//    log.info("  2. category_analytics.csv - Aggregated product analytics")
//    log.info("  3. high_value_completed.jsonl.gz - Compressed high-value transactions")
//    log.info("=" * 80)
//  }
//
//  /**
//   * Resource management wrapper using Cats Effect Resource.
//   */
//  private def resourcePipeline[A](
//    name: String,
//    description: String
//  )(
//    f: ActorSystem[Nothing] => IO[A]
//  ): IO[A] = {
//    val actorSystemResource = Resource.make(
//      IO {
//        log.info("=" * 80)
//        log.info(s"Starting: $name")
//        log.info(s"Description: $description")
//        log.info("=" * 80)
//        ActorSystem(Behaviors.empty, s"${name.replaceAll(" ", "-")}-system")
//      }
//    )(system =>
//      IO {
//        log.info(s"Shutting down actor system for: $name")
//        system.terminate()
//        Await.result(system.whenTerminated, 30.seconds)
//      }
//    )
//
//    actorSystemResource.use(f)
//  }
//
//  private def printSeparator(): Unit = {
//    println()
//    println("=" * 80)
//    println()
//  }
//}

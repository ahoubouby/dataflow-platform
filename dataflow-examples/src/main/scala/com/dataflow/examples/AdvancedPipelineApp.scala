//package com.dataflow.examples
//
//import java.nio.file.{Files, Paths, StandardOpenOption}
//
//import scala.concurrent.{Await, ExecutionContext, Future}
//import scala.concurrent.duration._
//
//import cats.effect.{IO, Resource}
//import cats.implicits._
//import com.dataflow.domain.models.{DataRecord, SourceConfig, SourceType}
//import com.dataflow.sinks.console._
//import com.dataflow.sinks.domain.{BatchConfig, DataSink}
//import com.dataflow.sinks.domain.exceptions.ConsoleSinkError
//import com.dataflow.sinks.file.{FileFormat, FileSink, FileSinkConfig}
//import com.dataflow.sources.file.{CSVFileSource, JSONFileSource}
//import com.dataflow.transforms.aggregation._
//import com.dataflow.transforms.domain.{AggregateConfig, AggregationType, FilterConfig}
//import com.dataflow.transforms.filters.{ComparisonOperator, FilterTransform}
//import org.apache.pekko.{Done, NotUsed}
//import org.apache.pekko.actor.typed.ActorSystem
//import org.apache.pekko.actor.typed.scaladsl.Behaviors
//import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, Sink}
//import org.slf4j.LoggerFactory
//
///**
// * Advanced pipeline demonstrating sophisticated streaming patterns.
// *
// * Architecture Patterns:
// * - Fan-out (broadcast to multiple sinks)
// * - Multi-stage transformations
// * - Type-safe error handling with Cats
// * - Metrics collection and monitoring
// * - Resource pooling and cleanup
// *
// * Use Cases Demonstrated:
// * 1. Real-time Sales Analytics Dashboard
// *    - Broadcast sales data to console (monitoring) and file (archive)
// *    - Multiple aggregation windows
// *    - Error handling and recovery
// *
// * 2. Data Quality Pipeline
// *    - Validation and filtering
// *    - Quality metrics reporting
// *    - Separate outputs for valid/invalid data
// *
// * 3. Multi-format Export
// *    - Single source, multiple output formats
// *    - Parallel writing to different sinks
// */
//object AdvancedPipelineApp {
//
//  private val log = LoggerFactory.getLogger(getClass)
//
//  def main(args: Array[String]): Unit = {
//    import cats.effect.unsafe.implicits.global
//    val result = run().attempt.unsafeRunSync()
//    result match {
//      case Right(_)    =>
//        log.info("Advanced pipelines completed successfully")
//        System.exit(0)
//      case Left(error) =>
//        log.error("Advanced pipeline application failed", error)
//        System.exit(1)
//    }
//  }
//
//  def run(): IO[Unit] = {
//    for {
//      _ <- IO(log.info("Starting Advanced Pipeline Examples"))
//      _ <- setupSalesData()
//      _ <- setupOutputDirectories()
//
//      _ <- runSalesAnalyticsPipeline()
//      _ <- IO(printSeparator())
//      _ <- runDataQualityPipeline()
//      _ <- IO(printSeparator())
//      _ <- runMultiFormatExportPipeline()
//
//      _ <- IO(log.info("All advanced pipelines completed"))
//    } yield ()
//  }
//
//  /**
//   * Setup sample sales data for advanced examples.
//   */
//  private def setupSalesData(): IO[Unit] = IO {
//    val dataDir = Paths.get("dataflow-examples/data")
//    if (!Files.exists(dataDir)) {
//      Files.createDirectories(dataDir)
//    }
//
//    // Real-time sales data with various qualities
//    val salesData = """sale_id,timestamp,store_id,product_id,quantity,price,payment_method,customer_tier
//                      |sale-001,2024-01-15T09:00:00,store-nyc,prod-laptop,1,1200,credit,gold
//                      |sale-002,2024-01-15T09:15:00,store-sf,prod-mouse,2,50,debit,silver
//                      |sale-003,2024-01-15T09:30:00,store-la,prod-keyboard,1,75,credit,gold
//                      |sale-004,2024-01-15T09:45:00,store-nyc,prod-monitor,3,1050,credit,platinum
//                      |sale-005,2024-01-15T10:00:00,store-chicago,prod-headphones,1,150,cash,bronze
//                      |sale-006,2024-01-15T10:15:00,store-sf,prod-webcam,2,180,credit,silver
//                      |sale-007,2024-01-15T10:30:00,INVALID,prod-laptop,5,6000,credit,platinum
//                      |sale-008,2024-01-15T10:45:00,store-la,prod-mouse,10,250,debit,gold
//                      |sale-009,2024-01-15T11:00:00,store-nyc,INVALID-PROD,-1,0,credit,silver
//                      |sale-010,2024-01-15T11:15:00,store-chicago,prod-keyboard,2,150,credit,gold
//                      |sale-011,2024-01-15T11:30:00,store-sf,prod-monitor,1,350,debit,platinum
//                      |sale-012,2024-01-15T11:45:00,store-la,prod-headphones,3,450,credit,gold
//                      |sale-013,2024-01-15T12:00:00,store-nyc,prod-laptop,2,2400,credit,platinum
//                      |sale-014,2024-01-15T12:15:00,store-sf,prod-webcam,1,90,cash,bronze
//                      |sale-015,2024-01-15T12:30:00,store-chicago,prod-mouse,5,125,credit,silver""".stripMargin
//
//    Files.write(
//      dataDir.resolve("sales_stream.csv"),
//      salesData.getBytes,
//      StandardOpenOption.CREATE,
//      StandardOpenOption.TRUNCATE_EXISTING,
//    )
//
//    // Store configuration data
//    val storesData =
//      """{"store_id":"store-nyc","city":"New York","region":"East","manager":"Alice Johnson"}
//        |{"store_id":"store-sf","city":"San Francisco","region":"West","manager":"Bob Smith"}
//        |{"store_id":"store-la","city":"Los Angeles","region":"West","manager":"Carol White"}
//        |{"store_id":"store-chicago","city":"Chicago","region":"Central","manager":"David Brown"}""".stripMargin
//
//    Files.write(
//      dataDir.resolve("stores.json"),
//      storesData.getBytes,
//      StandardOpenOption.CREATE,
//      StandardOpenOption.TRUNCATE_EXISTING,
//    )
//
//    log.info("Advanced sample data created")
//  }
//
//  private def setupOutputDirectories(): IO[Unit] = IO {
//    val dirs = Seq("output", "output/analytics", "output/quality", "output/exports")
//    dirs.foreach {
//      dir =>
//        val path = Paths.get(s"dataflow-examples/$dir")
//        if (!Files.exists(path)) {
//          Files.createDirectories(path)
//        }
//    }
//  }
//
//  /**
//   * Sales Analytics Pipeline with Fan-out Pattern
//   *
//   * Demonstrates:
//   * - Broadcasting to multiple sinks (console + file)
//   * - Real-time analytics with aggregation
//   * - Parallel writes
//   * - Metrics collection
//   */
//  private def runSalesAnalyticsPipeline(): IO[Unit] = {
//    resourcePipeline(
//      name = "Sales Analytics Dashboard",
//      description = "Real-time sales monitoring with dual output (console + archive)",
//    ) {
//      implicit system =>
//        implicit val ec: ExecutionContext = system.executionContext
//
//        for {
//          // Create sales source
//          source             <- IO {
//                                  val config = SourceConfig(
//                                    sourceType = SourceType.File,
//                                    connectionString = "dataflow-examples/data/sales_stream.csv",
//                                    options = Map(
//                                      "format"     -> "csv",
//                                      "delimiter"  -> ",",
//                                      "has-header" -> "true",
//                                    ),
//                                    batchSize = 100,
//                                    pollIntervalMs = 500,
//                                  )
//                                  new CSVFileSource("sales-analytics", config)
//                                }
//
//          // Filter only valid sales (price > 0, quantity > 0)
//          validationFilter   <- IO {
//                                  new FilterTransform(
//                                    FilterConfig(""),
////            field = "price",
////            operator = ComparisonOperator.GreaterThan,
////            value = "0"
//                                  )
//                                }
//
//          // Aggregate by store
//          aggregateTransform <- IO {
//                                  new AggregateTransform(
//                                    AggregateConfig(
//                                      groupByFields = Seq("store_id"),
//                                      windowSize = 5.seconds,
//                                      aggregations = Map(
//                                        "total_sales"     -> AggregationType.Count,
//                                        "total_revenue"   -> AggregationType.Sum("price"),
//                                        "avg_transaction" -> AggregationType.Average("price"),
//                                        "max_sale"        -> AggregationType.Max("price"),
//                                      ),
//                                    ),
//                                  )
//                                }
//
//          // Create console sink for monitoring
//          consoleSink        <- IO.fromEither(
//                                  ConsoleSink.builder()
//                                    .withFormat(OutputFormat.Table)
//                                    .withColorScheme(ColorScheme.Enabled)
//                                    .withTimestamp(true)
//                                    .withSummary(true)
//                                    .build()
//                                    .leftMap(err => new RuntimeException("")), // Map BEFORE fromEither
//                                )
//
//          // Create file sink for archiving
//          archiveSink        <- IO {
//                                  val config = FileSinkConfig(
//                                    path = "dataflow-examples/output/analytics/sales_analytics.jsonl",
//                                    format = FileFormat.JSONL,
//                                    compression = None,
//                                    rotationSize = Some(10 * 1024 * 1024),
//                                    batchConfig = BatchConfig(maxSize = 50, maxDuration = 3.seconds),
//                                  )
//                                  new FileSink(config)
//                                }
//
//          // Run pipeline with fan-out to both sinks
//          _                  <- IO.fromFuture(IO {
//                                  val graph = RunnableGraph.fromGraph(GraphDSL.create() {
//                                    implicit builder =>
//                                      import GraphDSL.Implicits._
//
//                                      val sourceShape      = builder.add(source.stream())
//                                      val filterShape      = builder.add(validationFilter.flow)
//                                      val aggShape         = builder.add(aggregateTransform.flow)
//                                      val broadcast        = builder.add(Broadcast[DataRecord](2))
//                                      val consoleSinkShape = builder.add(consoleSink.sink)
//                                      val fileSinkShape    = builder.add(archiveSink.sink)
//
//                                      sourceShape ~> filterShape ~> aggShape ~> broadcast
//                                      broadcast ~> consoleSinkShape
//                                      broadcast ~> fileSinkShape
//
//                                      org.apache.pekko.stream.ClosedShape
//                                  })
//
//                                  graph.run()
//                                })
//
//          // Collect metrics from both sinks
//          consoleMetrics     <- IO(consoleSink.metrics)
//          fileMetrics        <- IO(archiveSink.metrics)
//
//          _ <- IO {
//                 log.info("Sales Analytics Pipeline Results:")
//                 log.info(s"  Console Output - Records: ${consoleMetrics.recordsWritten}, " +
//                   s"Throughput: ${consoleMetrics.throughput} rec/sec")
//                 log.info(s"  File Archive - Records: ${fileMetrics.recordsWritten}, " +
//                   s"Success Rate: ${(fileMetrics.successRate * 100).formatted("%.2f")}%")
//               }
//
//          _ <- IO.fromFuture(IO(consoleSink.close()))
//          _ <- IO.fromFuture(IO(archiveSink.close()))
//        } yield ()
//    }
//  }
//
//  /**
//   * Data Quality Pipeline
//   *
//   * Demonstrates:
//   * - Data validation and quality checks
//   * - Separate handling of valid/invalid data
//   * - Quality metrics reporting
//   * - Multi-stage filtering
//   */
//  private def runDataQualityPipeline(): IO[Unit] = {
//    resourcePipeline(
//      name = "Data Quality Assurance",
//      description = "Validate data quality and route to appropriate outputs",
//    ) {
//      implicit system =>
//        implicit val ec: ExecutionContext = system.executionContext
//
//        for {
//          // Create sales source
//          source            <- IO {
//                                 val config = SourceConfig(
//                                   sourceType = SourceType.File,
//                                   connectionString = "dataflow-examples/data/sales_stream.csv",
//                                   options = Map(
//                                     "format"     -> "csv",
//                                     "delimiter"  -> ",",
//                                     "has-header" -> "true",
//                                   ),
//                                   batchSize = 100,
//                                   pollIntervalMs = 5000,
//                                 )
//                                 new CSVFileSource("data-quality", config)
//                               }
//
//          // Quality check: valid quantity
//          quantityValidator <- IO {
//                                 new FilterTransform(
//                                   FilterConfig("")
////                                   field = "quantity",
////                                   operator = ComparisonOperator.GreaterThan,
////                                   value = "0",
//                                 )
//                               }
//
//          // Quality check: valid price
//          priceValidator    <- IO {
//                                 new FilterTransform(
//                                   FilterConfig("")
////                                   field = "price",
////                                   operator = ComparisonOperator.GreaterThan,
////                                   value = "0",
//                                 )
//                               }
//
//          // Enrich with quality flags
////          qualityEnrichment <- IO {
////                                 new MapTransform(
////                                   MapConfig(
////                                     fieldMappings = Map(),
////                                     transformations = Map(
////                                       "quality_checked"      -> "true",
////                                       "validation_timestamp" -> java.time.Instant.now().toString,
////                                       "quality_score"        -> "100",
////                                     ),
////                                   ),
////                                 )
////                               }
//
//          // Create validated data sink
//          validSink         <- IO {
//                                 val config = FileSinkConfig(
//                                   path = "dataflow-examples/output/quality/validated_sales.jsonl",
//                                   format = FileFormat.JSONL,
//                                   compression = None,
//                                   batchConfig = BatchConfig.default,
//                                 )
//                                 new FileSink(config)
//                               }
//
//          // Create quality report sink (console)
//          reportSink        <- IO.fromEither(
//                                 ConsoleSink.builder()
//                                   .withFormat(OutputFormat.Structured)
//                                   .withColorScheme(ColorScheme.Enabled)
//                                   .withMetadata(false)
//                                   .withSummary(true)
//                                   .build(),
//                               ).leftMap(err => new RuntimeException(s"Report sink error: ${err.message}"))
//
//          // Run quality pipeline
//          _                 <- IO.fromFuture(IO {
//                                 source.stream()
//                                   .via(quantityValidator.flow)
//                                   .via(priceValidator.flow)
//                                   .via(qualityEnrichment.flow)
//                                   .runWith(validSink.sink)
//                               })
//
//          metrics <- IO(validSink.metrics)
//          _       <- IO {
//                       log.info("Data Quality Pipeline Results:")
//                       log.info(s"  Validated Records: ${metrics.recordsWritten}")
//                       log.info(s"  Failed Records: ${metrics.recordsFailed}")
//                       log.info(s"  Quality Score: ${(metrics.successRate * 100).formatted("%.2f")}%")
//                     }
//
//          _ <- IO.fromFuture(IO(validSink.close()))
//          _ <- IO.fromFuture(IO(reportSink.close()))
//        } yield ()
//    }
//  }
//
//  /**
//   * Multi-Format Export Pipeline
//   *
//   * Demonstrates:
//   * - Single source, multiple output formats
//   * - Parallel export to JSON, CSV, and Console
//   * - Format-specific transformations
//   * - Comprehensive metrics
//   */
//  private def runMultiFormatExportPipeline(): IO[Unit] = {
//    resourcePipeline(
//      name = "Multi-Format Data Export",
//      description = "Export store data to multiple formats simultaneously",
//    ) {
//      implicit system =>
//        implicit val ec: ExecutionContext = system.executionContext
//
//        for {
//          // Create stores source
//          source          <- IO {
//                               val config = SourceConfig(
//                                 sourceType = SourceType.File,
//                                 connectionString = "dataflow-examples/data/stores.json",
//                                 options = Map("format" -> "json"),
//                                 batchSize = 100,
//                               )
//                               new JSONFileSource("multi-export", config)
//                             }
//
//          // Enrich with export metadata
//          enrichTransform <- IO {
//                               new MapTransform(
//                                 MapConfig(
//                                   fieldMappings = Map(),
//                                   transformations = Map(
//                                     "export_date"    -> "2024-01-15",
//                                     "export_version" -> "1.0",
//                                     "data_source"    -> "stores_master",
//                                   ),
//                                 ),
//                               )
//                             }
//
//          // Create JSONL sink
//          jsonSink        <- IO {
//                               val config = FileSinkConfig(
//                                 path = "dataflow-examples/output/exports/stores.jsonl",
//                                 format = FileFormat.JSONL,
//                                 compression = None,
//                                 batchConfig = BatchConfig.default,
//                               )
//                               new FileSink(config)
//                             }
//
//          // Create CSV sink
//          csvSink         <- IO {
//                               val config = FileSinkConfig(
//                                 path = "dataflow-examples/output/exports/stores.csv",
//                                 format = FileFormat.CSV,
//                                 compression = None,
//                                 batchConfig = BatchConfig.default,
//                               )
//                               new FileSink(config)
//                             }
//
//          // Create console sink for preview
//          consoleSink     <- IO.fromEither(
//                               ConsoleSink.builder()
//                                 .withFormat(OutputFormat.PrettyJSON)
//                                 .withColorScheme(ColorScheme.Enabled)
//                                 .withSummary(false)
//                                 .build(),
//                             ).leftMap(err => new RuntimeException(s"Console sink error: ${err.message}"))
//
//          // Run multi-format export with broadcast
//          _               <- IO.fromFuture(IO {
//                               val graph = RunnableGraph.fromGraph(GraphDSL.create() {
//                                 implicit builder =>
//                                   import GraphDSL.Implicits._
//
//                                   val sourceShape      = builder.add(source.stream())
//                                   val enrichShape      = builder.add(enrichTransform.flow)
//                                   val broadcast        = builder.add(Broadcast[DataRecord](3))
//                                   val jsonSinkShape    = builder.add(jsonSink.sink)
//                                   val csvSinkShape     = builder.add(csvSink.sink)
//                                   val consoleSinkShape = builder.add(consoleSink.sink)
//
//                                   sourceShape ~> enrichShape ~> broadcast
//                                   broadcast ~> jsonSinkShape
//                                   broadcast ~> csvSinkShape
//                                   broadcast ~> consoleSinkShape
//
//                                   org.apache.pekko.stream.ClosedShape
//                               })
//
//                               graph.run()
//                             })
//
//          // Collect metrics from all sinks
//          jsonMetrics     <- IO(jsonSink.metrics)
//          csvMetrics      <- IO(csvSink.metrics)
//          consoleMetrics  <- IO(consoleSink.metrics)
//
//          _ <- IO {
//                 log.info("Multi-Format Export Results:")
//                 log.info(s"  JSONL Export - Records: ${jsonMetrics.recordsWritten}")
//                 log.info(s"  CSV Export - Records: ${csvMetrics.recordsWritten}")
//                 log.info(s"  Console Preview - Records: ${consoleMetrics.recordsWritten}")
//                 log.info("All formats exported successfully!")
//               }
//
//          _ <- IO.fromFuture(IO(jsonSink.close()))
//          _ <- IO.fromFuture(IO(csvSink.close()))
//          _ <- IO.fromFuture(IO(consoleSink.close()))
//        } yield ()
//    }
//  }
//
//  private def resourcePipeline[A](
//    name: String,
//    description: String,
//  )(
//    f: ActorSystem[Nothing] => IO[A],
//  ): IO[A] = {
//    val actorSystemResource = Resource.make(
//      IO {
//        log.info("=" * 80)
//        log.info(s"Starting: $name")
//        log.info(s"Description: $description")
//        log.info("=" * 80)
//        ActorSystem(Behaviors.empty, s"${name.replaceAll(" ", "-")}-system")
//      },
//    )(
//      system =>
//        IO {
//          system.terminate()
//          Await.result(system.whenTerminated, 30.seconds)
//        },
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

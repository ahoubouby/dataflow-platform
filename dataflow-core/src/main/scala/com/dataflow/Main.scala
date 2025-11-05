package com.dataflow

import scala.concurrent.duration._
import scala.util.{Failure, Success}

import com.dataflow.aggregates.coordinator.CoordinatorAggregate
import com.dataflow.cluster.PipelineSharding
import com.dataflow.domain.commands._
import com.dataflow.domain.models._
import com.dataflow.metrics.MetricsReporter
import com.dataflow.sources.Source
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityTypeKey
import org.apache.pekko.cluster.typed.{Cluster, ClusterSingleton, ClusterSingletonSettings, SingletonActor}
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.apache.pekko.management.scaladsl.PekkoManagement
import org.apache.pekko.pattern.StatusReply
import org.slf4j.{Logger, LoggerFactory}
import java.nio.file.{Files, Paths, StandardOpenOption}

/**
 * Main entry point for the DataFlow Platform.
 *
 * This application:
 * 1. Initializes the Pekko cluster
 * 2. Sets up cluster sharding for pipelines
 * 3. Initializes the coordinator singleton
 * 4. Starts metrics collection (Kamon)
 * 5. Optionally starts HTTP API server
 *
 * Run modes:
 * - Single node: For local development/testing
 * - Cluster: For production deployment
 */
object Main {

  private val log: Logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // Load configuration
    val config: Config = loadConfig()

    // Create actor system
    val system: ActorSystem[Nothing] = ActorSystem[Nothing](
      Behaviors.empty,
      "DataflowPlatform",
      config,
    )

    // Initialize and start the platform
    try {
      initializePlatform(system)
    } catch {
      case ex: Exception =>
        log.error("Failed to initialize platform", ex)
        system.terminate()
        System.exit(1)
    }
  }

  /**
   * Load configuration based on command line args or environment.
   */
  private def loadConfig(): Config = {
    val baseConfig = ConfigFactory.load()

    // Check for environment-specific config
    val environment    = sys.env.getOrElse("DATAFLOW_ENV", "local")
    val configResource = environment match {
      case "production"          => "production"
      case "staging"             => "staging"
      case "development" | "dev" => "development"
      case _                     => "local"
    }

    log.info(s"Loading configuration for environment: $environment")

    // Try to load environment-specific config, fallback to base
    try {
      ConfigFactory.parseResources(s"$configResource.conf")
        .withFallback(baseConfig)
        .resolve()
    } catch {
      case _: Exception =>
        log.warn(s"No $configResource.conf found, using base configuration")
        baseConfig
    }
  }

  /**
   * Initialize all platform components.
   */
  private def initializePlatform(system: ActorSystem[Nothing]): Unit = {
    // implicit val sys: ActorSystem[Nothing] = system

    val cluster     = Cluster(system)
    val selfAddress = cluster.selfMember.address

    log.info(s"Starting DataFlow Platform")
    log.info(s"Cluster address: $selfAddress")
    log.info(s"Cluster roles: ${cluster.selfMember.roles.mkString(", ")}")

    // 1. Initialize metrics collection
    log.info("Initializing metrics collection (Kamon)...")
    MetricsReporter.init()
    log.info("✓ Metrics collection initialized")

    // 2. Initialize Pekko Management (for cluster bootstrap and health checks)
    log.info("Starting Pekko Management...")
    val management = PekkoManagement(system)
    management.start()
    log.info("✓ Pekko Management started")

    // 3. Initialize Cluster Bootstrap (for automatic cluster formation)
    log.info("Starting Cluster Bootstrap...")
    ClusterBootstrap(system).start()
    log.info("✓ Cluster Bootstrap started")

    // 4. Wait for cluster to be up
    cluster.manager ! org.apache.pekko.cluster.typed.Join(selfAddress)

    // 5. Initialize cluster sharding for pipelines
    log.info("Initializing Pipeline Cluster Sharding...")
    val pipelineShardRegion: ActorRef[ShardingEnvelope[Command]] = PipelineSharding.init(system)
    log.info("✓ Pipeline Sharding initialized")

    // 6. Initialize coordinator (cluster singleton)
    log.info("Initializing Coordinator Singleton...")
    val coordinatorProxy = initializeCoordinator(system)
    log.info("✓ Coordinator Singleton initialized")

    // 7. Schedule periodic health check
    scheduleHealthCheck(system, cluster)

    // 8. Setup graceful shutdown
    setupGracefulShutdown(system)

    log.info("=" * 80)
    log.info("DataFlow Platform started successfully!")
    log.info("=" * 80)
    log.info(s"Cluster address: $selfAddress")
    log.info(s"Roles: ${cluster.selfMember.roles.mkString(", ")}")
    log.info(s"Management endpoint: http://localhost:8558")
    log.info(s"Metrics endpoint: http://localhost:9095/metrics")
    log.info(s"Kamon status: http://localhost:5266")
    log.info("=" * 80)

    // 9. Run example pipeline (wait a bit for cluster to stabilize)
    import system.executionContext
    system.scheduler.scheduleOnce(5.seconds) {
      runExamplePipeline(system, pipelineShardRegion, coordinatorProxy)
    }
  }

  /**
   * Run an example pipeline to demonstrate the platform.
   * This creates a dummy file, reads from it, and prints the output.
   */
  private def runExamplePipeline(
    system: ActorSystem[Nothing],
    pipelineShardRegion: ActorRef[ShardingEnvelope[Command]],
    coordinatorProxy: ActorRef[CoordinatorCommand]
  ): Unit = {
    implicit val sys: ActorSystem[Nothing] = system
    import system.executionContext

    log.info("=" * 80)
    log.info("RUNNING EXAMPLE PIPELINE")
    log.info("=" * 80)

    // 1. Create a dummy file with sample data
    val dummyFilePath = "/tmp/dataflow-example-input.txt"
    log.info(s"1. Creating dummy file: $dummyFilePath")

    val sampleData = (1 to 10).map { i =>
      s"Sample record $i: timestamp=${System.currentTimeMillis()}, value=${i * 10}"
    }.mkString("\n")

    Files.write(
      Paths.get(dummyFilePath),
      sampleData.getBytes,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING
    )
    log.info(s"✓ Created dummy file with 10 records")

    // 2. Register pipeline with coordinator
    val pipelineId = "example-pipeline-001"
    val pipelineName = "Example Text File Pipeline"

    log.info(s"2. Registering pipeline with coordinator: $pipelineId")
    system.systemActorOf(
      Behaviors.setup[Any] { ctx =>
        coordinatorProxy ! RegisterPipeline(
          pipelineId = pipelineId,
          name = pipelineName,
          replyTo = ctx.messageAdapter {
            case StatusReply.Success(state) =>
              log.info(s"✓ Pipeline registered with coordinator: $pipelineId")
              "registered"
            case StatusReply.Error(error) =>
              log.warn(s"Pipeline registration response: $error (may already exist)")
              "error"
          }
        )
        Behaviors.receiveMessage { _ => Behaviors.same }
      },
      "pipeline-registrar"
    )

    // 3. Create pipeline configuration
    log.info(s"3. Creating pipeline configuration")

    val sourceConfig = SourceConfig(
      sourceType = SourceType.File,
      connectionString = dummyFilePath,
      options = Map(
        "format" -> "text",
        "encoding" -> "UTF-8"
      ),
      batchSize = 5  // Process in batches of 5 records
    )

    val sinkConfig = SinkConfig(
      sinkType = SinkType.Console,  // Print to console
      connectionString = "console",
      options = Map("format" -> "json")
    )

    val pipelineConfig = PipelineConfig(
      name = pipelineName,
      description = "Example pipeline reading from text file and printing output",
      sourceConfig = sourceConfig,
      transformConfigs = List.empty,  // No transformations for now
      sinkConfig = sinkConfig,
      maxRetries = 3,
      timeout = 30.seconds
    )

    // 4. Send CreatePipeline command
    log.info(s"4. Sending CreatePipeline command to pipeline aggregator")

    system.systemActorOf(
      Behaviors.setup[StatusReply[State]] { ctx =>
        val createCmd = CreatePipeline(
          pipelineId = pipelineId,
          name = pipelineName,
          description = "Example pipeline for demonstration",
          sourceConfig = sourceConfig,
          transformConfigs = List.empty,
          sinkConfig = sinkConfig,
          replyTo = ctx.self
        )

        pipelineShardRegion ! ShardingEnvelope(pipelineId, createCmd)

        Behaviors.receiveMessage {
          case StatusReply.Success(state) =>
            log.info(s"✓ Pipeline created successfully: $pipelineId")
            log.info(s"   State: ${state.getClass.getSimpleName}")

            // 5. Start the pipeline
            log.info(s"5. Starting pipeline: $pipelineId")
            pipelineShardRegion ! ShardingEnvelope(
              pipelineId,
              StartPipeline(pipelineId, ctx.messageAdapter {
                case StatusReply.Success(runningState) =>
                  log.info(s"✓ Pipeline started successfully!")
                  log.info(s"   State: ${runningState.getClass.getSimpleName}")

                  // 6. Create and start file source
                  log.info(s"6. Creating file source to read data")
                  ctx.scheduleOnce(2.seconds, ctx.self, StatusReply.success(runningState))
                  StatusReply.success(runningState)

                case StatusReply.Error(error) =>
                  log.error(s"✗ Failed to start pipeline: $error")
                  StatusReply.error(error)
              })
            )
            Behaviors.same

          case StatusReply.Error(error) =>
            log.error(s"✗ Failed to create pipeline: $error")
            Behaviors.stopped
        }
      },
      "pipeline-creator"
    )

    // 7. Start file source after a delay
    system.scheduler.scheduleOnce(8.seconds) {
      log.info(s"7. Starting file source to ingest data")
      try {
        val source = Source(pipelineId, sourceConfig)(system)
        source.start(pipelineShardRegion).onComplete {
          case Success(_) =>
            log.info(s"✓ File source started successfully")
            log.info(s"   Reading from: $dummyFilePath")
            log.info(s"   Pipeline will process records and print to console")
            log.info("=" * 80)

          case Failure(ex) =>
            log.error(s"✗ Failed to start file source: ${ex.getMessage}", ex)
        }
      } catch {
        case ex: Exception =>
          log.error(s"✗ Error creating file source: ${ex.getMessage}", ex)
      }
    }
  }

  /**
   * Initialize coordinator as cluster singleton.
   */
  private def initializeCoordinator(system: ActorSystem[_]): ActorRef[CoordinatorCommand] = {
    val singleton     = ClusterSingleton(system)
    val singletonName = CoordinatorAggregate.CoordinatorId
    val behavior      = CoordinatorAggregate()
    val stopMsg       = CoordinatorCommand.Stop

    // Optional placement constraint: only run on nodes with role "core"
    val settings: ClusterSingletonSettings =
      ClusterSingletonSettings(system).withRole("core")

    singleton.init(
      SingletonActor(behavior, singletonName)
        .withStopMessage(stopMsg) // graceful stop signal for the singleton
        .withSettings(settings),  // placement/hand-over settings
    )
  }

  /**
   * Schedule periodic health check.
   */
  private def scheduleHealthCheck(
    system: ActorSystem[_],
    cluster: Cluster,
  ): Unit = {
    implicit val sys: ActorSystem[_] = system
    import system.executionContext

    system.scheduler.scheduleWithFixedDelay(
      initialDelay = 1.minute,
      delay = 1.minute,
    ) {
      () =>
        val members     = cluster.state.members.size
        val unreachable = cluster.state.unreachable.size
        val leader      = cluster.state.leader.map(_.toString).getOrElse("none")

        log.info(
          s"Cluster health: members=$members, unreachable=$unreachable, leader=$leader",
        )

        // Log warning if cluster is unhealthy
        if (unreachable > 0) {
          log.warn(s"Cluster has $unreachable unreachable members")
        }
    }
  }

  /**
   * Setup graceful shutdown handlers.
   */
  private def setupGracefulShutdown(system: ActorSystem[_]): Unit = {
    import system.executionContext

    // JVM shutdown hook
    sys.addShutdownHook {
      log.info("Received shutdown signal, starting graceful shutdown...")

      // 1. Stop accepting new work
      log.info("1/4 Stopping metrics collection...")
      MetricsReporter.shutdown()

      // 2. Leave cluster
      log.info("2/4 Leaving cluster...")
      val cluster = Cluster(system)
      cluster.manager ! org.apache.pekko.cluster.typed.Leave(cluster.selfMember.address)

      // 3. Wait for cluster to process leave
      Thread.sleep(5000)

      // 4. Terminate actor system
      log.info("3/4 Terminating actor system...")
      system.terminate()

      // 5. Wait for termination
      log.info("4/4 Waiting for actor system termination...")
      system.whenTerminated.onComplete {
        case Success(_)  =>
          log.info("✓ DataFlow Platform shut down successfully")
        case Failure(ex) =>
          log.error("✗ Error during shutdown", ex)
      }
    }

    // Pekko coordinated shutdown
    system.whenTerminated.onComplete {
      case Success(_)  =>
        log.info("Actor system terminated")
      case Failure(ex) =>
        log.error("Actor system termination failed", ex)
    }
  }
}

package com.dataflow

import scala.concurrent.duration._
import scala.util.{Failure, Success}

import com.dataflow.aggregates.coordinator.CoordinatorAggregate
import com.dataflow.cluster.PipelineSharding
import com.dataflow.domain.commands.{Command, CoordinatorCommand}
import com.dataflow.metrics.MetricsReporter
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityTypeKey
import org.apache.pekko.cluster.typed.{Cluster, ClusterSingleton, ClusterSingletonSettings, SingletonActor}
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.apache.pekko.management.scaladsl.PekkoManagement
import org.slf4j.{Logger, LoggerFactory}

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

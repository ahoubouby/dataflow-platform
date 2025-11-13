package com.dataflow.api

import com.dataflow.aggregates.PipelineAggregate
import com.dataflow.api.services.PipelineService
import com.dataflow.domain.commands.Command
import com.dataflow.execution.{ExecutionOrchestrator, PipelineEventListener}
import kamon.Kamon
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.apache.pekko.management.scaladsl.PekkoManagement
import org.apache.pekko.http.scaladsl.Http

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
 * Main application entry point for DataFlow Platform API.
 * Sets up cluster sharding, execution orchestration, and starts the HTTP server.
 */
object ApiMain extends App {

  // Initialize Kamon metrics and monitoring
  Kamon.init()

  // Create root actor system
  val rootBehavior = Behaviors.setup[Nothing] { context =>
    implicit val system: ActorSystem[Nothing] = context.system
    implicit val ec: ExecutionContext = system.executionContext

    // Initialize cluster management
    PekkoManagement(system).start()
    ClusterBootstrap(system).start()

    // Initialize cluster sharding for pipeline aggregates
    val sharding = ClusterSharding(system)
    sharding.init(Entity(PipelineService.TypeKey) { entityContext =>
      PipelineAggregate(entityContext.entityId)
    })

    context.log.info("Cluster sharding initialized for Pipeline aggregates")

    // Start execution orchestration
    // The orchestrator manages pipeline executors based on events
    val orchestrator = context.spawn(ExecutionOrchestrator(), "execution-orchestrator")
    context.log.info("ExecutionOrchestrator spawned")

    // Start event listener to feed events to orchestrator
    PipelineEventListener.start(orchestrator).onComplete {
      case Success(_) =>
        context.log.info("PipelineEventListener started - executors will start when pipelines are started")
      case Failure(ex) =>
        context.log.error("Failed to start PipelineEventListener", ex)
    }


    // Start HTTP server
    val httpServer = HttpServer()
    val config = system.settings.config
    val host = config.getString("dataflow.api.host")
    val port = config.getInt("dataflow.api.port")

    httpServer.start(host, port).onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        context.log.info(
          s"DataFlow Platform API started successfully at http://${address.getHostString}:${address.getPort}/"
        )
        context.log.info(s"WebSocket endpoint: ws://${address.getHostString}:${address.getPort}/api/v1/ws/pipelines/:id")
        context.log.info(s"Health check: http://${address.getHostString}:${address.getPort}/health")

      case Failure(ex) =>
        context.log.error(s"Failed to start HTTP server: ${ex.getMessage}", ex)
        system.terminate()
    }

    // Register shutdown hook
    sys.addShutdownHook {
      context.log.info("Shutting down DataFlow Platform API...")
      Kamon.stop()
      system.terminate()
    }

    Behaviors.empty
  }

  // Start the actor system
  ActorSystem[Nothing](rootBehavior, "DataFlowSystem")
}

package com.dataflow.execution

import com.dataflow.domain.events.Event
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.persistence.cassandra.query.scaladsl.CassandraReadJournal
import org.apache.pekko.persistence.query.{EventEnvelope, Offset}
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.stream.scaladsl.{RestartSource, Sink}
import org.apache.pekko.actor.typed.ActorSystem
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Listens to pipeline events from the Cassandra event journal and forwards them
 * to the ExecutionOrchestrator.
 *
 * This is a lightweight event-driven approach:
 * - PipelineAggregate emits events (pure event sourcing)
 * - PipelineEventListener reads events from journal
 * - ExecutionOrchestrator reacts to events (spawns/stops executors)
 */
object PipelineEventListener {

  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Start listening to pipeline events and forward them to the orchestrator.
   *
   * @param orchestrator The ExecutionOrchestrator actor to send events to
   * @param system ActorSystem for creating the read journal
   * @return Future that completes when listener is running
   */
  def start(
    orchestrator: ActorRef[ExecutionOrchestrator.Command]
  )(implicit system: ActorSystem[_]): Future[Unit] = {
    import system.executionContext

    log.info("Starting PipelineEventListener")

    // Create Cassandra read journal
    val readJournal = PersistenceQuery(system)
      .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

    // Stream all events with tag "pipeline"
    // Note: Events are tagged in PipelineAggregate.eventHandler
    val eventStream = RestartSource.withBackoff(
      minBackoff = 3.seconds,
      maxBackoff = 30.seconds,
      randomFactor = 0.2
    ) { () =>
      readJournal
        .eventsByTag("pipeline", Offset.noOffset)
        .map { envelope =>
          envelope.event match {
            case event: Event =>
              // Extract pipelineId from persistence ID
              // Format: "pipeline-<uuid>"
              val pipelineId = envelope.persistenceId.split("-", 2).lastOption.getOrElse("unknown")

              log.debug("Received event: {} for pipeline: {}", event.getClass.getSimpleName, pipelineId)

              // Forward to orchestrator
              orchestrator ! ExecutionOrchestrator.HandleEvent(event, pipelineId)

            case other =>
              log.warn("Unexpected event type: {}", other.getClass)
          }
        }
    }

    // Run the stream
    eventStream
      .runWith(Sink.ignore)
      .map { _ =>
        log.info("PipelineEventListener started successfully")
      }
      .recover {
        case ex =>
          log.error("PipelineEventListener failed", ex)
      }
  }
}

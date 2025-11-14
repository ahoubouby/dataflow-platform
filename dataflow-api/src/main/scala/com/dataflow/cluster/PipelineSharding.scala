package com.dataflow.cluster

import com.dataflow.aggregates.PipelineAggregate
import com.dataflow.domain.commands.Command
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}

object PipelineSharding {

  // Define entity type key (namespace for all pipelines)
  val TypeKey: EntityTypeKey[Command] =
    EntityTypeKey[Command]("Pipeline")

  /**
   * Initialize cluster sharding for pipelines.
   * Call this once at application startup.
   *
   * @param system The actor system
   * @return ShardRegion that routes messages to pipelines
   */
  def init(system: ActorSystem[_]): ActorRef[ShardingEnvelope[Command]] = {
    val sharding = ClusterSharding(system)

    // Initialize shard region
    sharding.init(Entity(TypeKey) {
      entityContext =>
        // Create PipelineAggregate actor for this pipeline ID
        val pipelineId = entityContext.entityId
        PipelineAggregate(pipelineId)
    })
  }

  /**
   * Send a command to a pipeline (sharded).
   *
   * @param shardRegion The shard region (from init)
   * @param pipelineId The target pipeline ID
   * @param command The command to send
   */
  def sendCommand(
    shardRegion: ActorRef[ShardingEnvelope[Command]],
    pipelineId: String,
    command: Command,
  ): Unit =
    // Wrap command in ShardingEnvelope with entity ID
    shardRegion ! ShardingEnvelope(entityId = pipelineId, message = command)
}

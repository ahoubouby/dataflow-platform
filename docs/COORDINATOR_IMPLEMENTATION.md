# Coordinator Aggregate Implementation

## Overview

The Coordinator Aggregate is a global, event-sourced aggregate that manages the registry of all pipelines in the DataFlow Platform. It tracks pipeline status, monitors resource usage, and provides system-wide operations and queries.

## Architecture

### Singleton Design

The coordinator is deployed as a **cluster singleton** - a single instance across the entire cluster that:
- Maintains a consistent view of all pipelines
- Tracks aggregate resource usage
- Provides centralized system management
- Survives node failures via persistence

### Responsibilities

1. **Pipeline Registry**: Track all registered pipelines with their metadata
2. **Status Monitoring**: Monitor pipeline health and state transitions
3. **Resource Management**: Track CPU and memory usage across all pipelines
4. **System Queries**: Provide system-wide queries and reports
5. **Resource Allocation**: Check resource availability before creating pipelines

## Domain Model

### Commands

Located in: `com.dataflow.domain.coordinator.CoordinatorCommands.scala`

#### RegisterPipeline
Register a new pipeline with the coordinator.

```scala
RegisterPipeline(
  pipelineId: String,
  name: String,
  replyTo: ActorRef[StatusReply[CoordinatorState]]
)
```

**Validation**:
- Pipeline ID must not already exist
- Name must be non-empty

**Success**: Returns updated coordinator state
**Failure**: Returns error if pipeline already registered

#### UnregisterPipeline
Remove a pipeline from the registry.

```scala
UnregisterPipeline(
  pipelineId: String,
  replyTo: ActorRef[StatusReply[CoordinatorState]]
)
```

**Validation**:
- Pipeline must exist in registry

**Success**: Returns updated state with pipeline removed
**Failure**: Returns error if pipeline not found

**Side Effects**:
- Resource totals are recalculated
- Pipeline metrics are removed

#### UpdatePipelineStatus
Update the status of a pipeline (health check, state change).

```scala
UpdatePipelineStatus(
  pipelineId: String,
  status: PipelineStatus,
  totalRecords: Long,
  failedRecords: Long
)
```

**No Reply**: Fire-and-forget command for periodic status updates

**Pipeline Status Values**:
- `Configured` - Pipeline created but not started
- `Running` - Pipeline actively processing data
- `Paused` - Pipeline temporarily paused
- `Stopped` - Pipeline stopped gracefully
- `Failed` - Pipeline encountered fatal error

#### GetSystemStatus
Query the current system status.

```scala
GetSystemStatus(
  replyTo: ActorRef[CoordinatorState]
)
```

**Returns**: Complete coordinator state with all pipeline info

#### GetPipelineInfo
Get information about a specific pipeline.

```scala
GetPipelineInfo(
  pipelineId: String,
  replyTo: ActorRef[Option[PipelineInfo]]
)
```

**Returns**: Pipeline info if found, None otherwise

#### ListPipelines
List all pipelines, optionally filtered by status.

```scala
ListPipelines(
  status: Option[PipelineStatus],
  replyTo: ActorRef[List[PipelineInfo]]
)
```

**Examples**:
```scala
// List all pipelines
ListPipelines(None, replyTo)

// List only running pipelines
ListPipelines(Some(PipelineStatus.Running), replyTo)

// List only failed pipelines
ListPipelines(Some(PipelineStatus.Failed), replyTo)
```

#### ReportResourceUsage
Report resource usage by a pipeline.

```scala
ReportResourceUsage(
  pipelineId: String,
  cpuPercent: Double,      // 0-100
  memoryMB: Long           // Memory in MB
)
```

**No Reply**: Fire-and-forget command for periodic resource updates

**Purpose**: Allows coordinator to track total system resource usage

#### CheckResourceAvailability
Check if resources are available for a new pipeline.

```scala
CheckResourceAvailability(
  estimatedCpuPercent: Double,
  estimatedMemoryMB: Long,
  replyTo: ActorRef[StatusReply[Boolean]]
)
```

**Returns**: `true` if resources available, `false` otherwise

**Resource Limits** (configurable):
- Max CPU: 80% of total system CPU
- Max Memory: 80GB total memory

### Events

Located in: `com.dataflow.domain.coordinator.CoordinatorEvents.scala`

All events extend `CoordinatorEvent` and are tagged with `"coordinator"` for projections.

#### PipelineRegistered
```scala
PipelineRegistered(
  pipelineId: String,
  name: String,
  timestamp: Instant
)
```

Emitted when a pipeline is registered with the coordinator.

#### PipelineUnregistered
```scala
PipelineUnregistered(
  pipelineId: String,
  timestamp: Instant
)
```

Emitted when a pipeline is removed from the registry.

#### PipelineStatusUpdated
```scala
PipelineStatusUpdated(
  pipelineId: String,
  status: PipelineStatus,
  totalRecords: Long,
  failedRecords: Long,
  timestamp: Instant
)
```

Emitted when a pipeline reports its status.

#### ResourceUsageReported
```scala
ResourceUsageReported(
  pipelineId: String,
  cpuPercent: Double,
  memoryMB: Long,
  timestamp: Instant
)
```

Emitted when a pipeline reports resource usage.

### State

Located in: `com.dataflow.domain.coordinator.CoordinatorState.scala`

```scala
CoordinatorState(
  pipelines: Map[String, PipelineInfo],
  totalCpuPercent: Double,
  totalMemoryMB: Long,
  lastUpdated: Instant
)
```

**Pipeline Information**:
```scala
PipelineInfo(
  pipelineId: String,
  name: String,
  status: PipelineStatus,
  totalRecords: Long,
  failedRecords: Long,
  cpuPercent: Double,
  memoryMB: Long,
  registeredAt: Instant,
  lastHeartbeat: Instant
)
```

**Helper Methods**:

```scala
// Get specific pipeline
state.getPipeline(pipelineId: String): Option[PipelineInfo]

// List pipelines with optional filter
state.listPipelines(statusFilter: Option[PipelineStatus]): List[PipelineInfo]

// Count pipelines by status
state.countByStatus: Map[PipelineStatus, Int]

// Check resource availability
state.hasResourcesAvailable(
  estimatedCpu: Double,
  estimatedMemory: Long,
  maxCpuPercent: Double = 80.0,
  maxMemoryMB: Long = 80000
): Boolean

// Get system health summary
state.getSystemHealth: SystemHealth
```

**System Health**:
```scala
SystemHealth(
  status: HealthStatus,      // Healthy, Degraded, Critical, Idle
  totalPipelines: Int,
  runningPipelines: Int,
  failedPipelines: Int,
  totalCpuPercent: Double,
  totalMemoryMB: Long,
  lastUpdated: Instant
)
```

**Health Status Logic**:
- `Healthy`: All pipelines running normally
- `Degraded`: Some pipelines failed (< 50%)
- `Critical`: Majority of pipelines failed (>= 50%)
- `Idle`: No pipelines registered

## Usage Examples

### Creating the Coordinator

```scala
import com.dataflow.aggregates.coordinator.CoordinatorAggregate
import org.apache.pekko.cluster.typed.{Cluster, ClusterSingleton, SingletonActor}
import org.apache.pekko.actor.typed.ActorSystem

val system: ActorSystem[_] = ...

// Create as cluster singleton
val singletonManager = ClusterSingleton(system)
val coordinator = singletonManager.init(
  SingletonActor(
    CoordinatorAggregate(),
    CoordinatorAggregate.CoordinatorId
  )
)
```

### Registering a Pipeline

```scala
import com.dataflow.domain.coordinator._

val probe = testKit.createTestProbe[StatusReply[CoordinatorState]]()

coordinator ! RegisterPipeline(
  pipelineId = "pipeline-123",
  name = "User Events Pipeline",
  replyTo = probe.ref
)

val response = probe.receiveMessage()
response match {
  case StatusReply.Success(state) =>
    println(s"Registered! Total pipelines: ${state.pipelines.size}")
  case StatusReply.Error(error) =>
    println(s"Failed: $error")
}
```

### Updating Pipeline Status

```scala
// Periodic status update (fire-and-forget)
coordinator ! UpdatePipelineStatus(
  pipelineId = "pipeline-123",
  status = PipelineStatus.Running,
  totalRecords = 1000000L,
  failedRecords = 5L
)
```

### Reporting Resource Usage

```scala
// Periodic resource report (fire-and-forget)
coordinator ! ReportResourceUsage(
  pipelineId = "pipeline-123",
  cpuPercent = 15.5,
  memoryMB = 2048L
)
```

### Checking Resource Availability

```scala
val probe = testKit.createTestProbe[StatusReply[Boolean]]()

coordinator ! CheckResourceAvailability(
  estimatedCpuPercent = 10.0,
  estimatedMemoryMB = 4096L,
  replyTo = probe.ref
)

probe.receiveMessage() match {
  case StatusReply.Success(true) =>
    println("Resources available - can create pipeline")
  case StatusReply.Success(false) =>
    println("Insufficient resources")
  case StatusReply.Error(error) =>
    println(s"Error: $error")
}
```

### Querying System Status

```scala
val probe = testKit.createTestProbe[CoordinatorState]()

coordinator ! GetSystemStatus(probe.ref)

val state = probe.receiveMessage()
val health = state.getSystemHealth

println(s"System Status: ${health.status}")
println(s"Total Pipelines: ${health.totalPipelines}")
println(s"Running: ${health.runningPipelines}")
println(s"Failed: ${health.failedPipelines}")
println(s"CPU Usage: ${health.totalCpuPercent}%")
println(s"Memory Usage: ${health.totalMemoryMB}MB")
```

### Listing Pipelines

```scala
val probe = testKit.createTestProbe[List[PipelineInfo]]()

// List all pipelines
coordinator ! ListPipelines(None, probe.ref)

// List only failed pipelines
coordinator ! ListPipelines(Some(PipelineStatus.Failed), probe.ref)

val pipelines = probe.receiveMessage()
pipelines.foreach { info =>
  println(s"${info.pipelineId}: ${PipelineStatus.toString(info.status)}")
  println(s"  Records: ${info.totalRecords} (${info.failedRecords} failed)")
  println(s"  Resources: ${info.cpuPercent}% CPU, ${info.memoryMB}MB")
}
```

### Getting Pipeline Info

```scala
val probe = testKit.createTestProbe[Option[PipelineInfo]]()

coordinator ! GetPipelineInfo("pipeline-123", probe.ref)

probe.receiveMessage() match {
  case Some(info) =>
    println(s"Pipeline: ${info.name}")
    println(s"Status: ${PipelineStatus.toString(info.status)}")
    println(s"Last heartbeat: ${info.lastHeartbeat}")
  case None =>
    println("Pipeline not found")
}
```

### Unregistering a Pipeline

```scala
val probe = testKit.createTestProbe[StatusReply[CoordinatorState]]()

coordinator ! UnregisterPipeline(
  pipelineId = "pipeline-123",
  replyTo = probe.ref
)

probe.receiveMessage() match {
  case StatusReply.Success(state) =>
    println(s"Unregistered! Remaining pipelines: ${state.pipelines.size}")
  case StatusReply.Error(error) =>
    println(s"Failed: $error")
}
```

## Integration with PipelineAggregate

### Automatic Registration

When a pipeline is created, it should register with the coordinator:

```scala
case PipelineCreated(id, name, _, _, _, _, _) =>
  // Send registration to coordinator
  val coordinator = getCoordinator(context.system)
  coordinator ! RegisterPipeline(id, name, context.system.ignoreRef)

  // Update pipeline state
  ConfiguredState(...)
```

### Periodic Status Updates

Pipelines should periodically report their status (e.g., every 30 seconds):

```scala
// In PipelineAggregate, schedule periodic status updates
timers.startTimerWithFixedDelay(
  "status-update",
  SendStatusUpdate,
  initialDelay = 30.seconds,
  delay = 30.seconds
)

case SendStatusUpdate =>
  val coordinator = getCoordinator(context.system)
  state match {
    case r: RunningState =>
      coordinator ! UpdatePipelineStatus(
        r.pipelineId,
        PipelineStatus.Running,
        r.metrics.totalRecordsProcessed,
        r.metrics.totalRecordsFailed
      )
    case _ => // No update needed
  }
```

### Resource Reporting

Pipelines should report resource usage (can be integrated with Kamon metrics):

```scala
// Periodically report resource usage
timers.startTimerWithFixedDelay(
  "resource-report",
  ReportResources,
  initialDelay = 1.minute,
  delay = 1.minute
)

case ReportResources =>
  val coordinator = getCoordinator(context.system)
  val runtime = Runtime.getRuntime
  val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
  val cpuPercent = getCpuUsage() // From Kamon or JMX

  coordinator ! ReportResourceUsage(
    pipelineId,
    cpuPercent,
    usedMemory
  )
```

### Cleanup on Termination

When a pipeline stops or fails, unregister it:

```scala
case PipelineStopped(_, _, _, _) | PipelineFailed(_, _, _) =>
  val coordinator = getCoordinator(context.system)
  coordinator ! UnregisterPipeline(pipelineId, context.system.ignoreRef)

  // Update state
  ...
```

## Persistence and Recovery

### Event Sourcing

The coordinator uses event sourcing with Cassandra journal:

```scala
EventSourcedBehavior
  .withEnforcedReplies[CoordinatorCommand, CoordinatorEvent, CoordinatorState](
    persistenceId = PersistenceId.ofUniqueId("global-coordinator"),
    emptyState = CoordinatorState.empty,
    commandHandler = commandHandler,
    eventHandler = eventHandler,
  )
```

### Snapshots

Snapshots are taken every 50 events:

```scala
.withRetention(
  RetentionCriteria
    .snapshotEvery(numberOfEvents = 50, keepNSnapshots = 2)
    .withDeleteEventsOnSnapshot,
)
```

### Recovery

On recovery, the coordinator rebuilds its state from:
1. Latest snapshot (if available)
2. Events since snapshot
3. Recalculates resource totals

## Monitoring and Observability

### Metrics

Key metrics to track (via Kamon):

```scala
// Pipeline registry metrics
coordinator_pipelines_total{status="running"}
coordinator_pipelines_total{status="failed"}

// Resource usage
coordinator_cpu_percent_total
coordinator_memory_mb_total

// Operations
coordinator_registrations_total
coordinator_unregistrations_total
coordinator_status_updates_total
```

### Health Checks

```scala
// Periodic health check
coordinator ! GetSystemStatus(probe.ref)
val state = probe.receiveMessage()
val health = state.getSystemHealth

health.status match {
  case HealthStatus.Critical => // Alert operations team
  case HealthStatus.Degraded => // Warning notification
  case HealthStatus.Healthy  => // All good
  case HealthStatus.Idle     => // No pipelines
}
```

### Logging

The coordinator logs key operations:

```
INFO  - msg=Register pipeline pipelineId=pipeline-123 name=User Events
INFO  - msg=Unregister pipeline pipelineId=pipeline-123
DEBUG - msg=Update pipeline status pipelineId=pipeline-123 status=running records=1000000
DEBUG - msg=Report resource usage pipelineId=pipeline-123 cpu=15.5% memory=2048MB
WARN  - msg=Resources unavailable cpu=10% (current=72%) memory=4096MB (current=78000)
```

## Testing

### Unit Tests

Test command handling:

```scala
class CoordinatorAggregateSpec extends ScalaTestWithActorTestKit {
  "CoordinatorAggregate" should {
    "register a pipeline" in {
      val coordinator = spawn(CoordinatorAggregate())
      val probe = createTestProbe[StatusReply[CoordinatorState]]()

      coordinator ! RegisterPipeline("p1", "Pipeline 1", probe.ref)

      probe.receiveMessage() match {
        case StatusReply.Success(state) =>
          assert(state.pipelines.contains("p1"))
        case StatusReply.Error(error) =>
          fail(s"Registration failed: $error")
      }
    }

    "reject duplicate registration" in {
      val coordinator = spawn(CoordinatorAggregate())
      val probe = createTestProbe[StatusReply[CoordinatorState]]()

      coordinator ! RegisterPipeline("p1", "Pipeline 1", probe.ref)
      probe.receiveMessage() // Success

      coordinator ! RegisterPipeline("p1", "Pipeline 1", probe.ref)
      probe.receiveMessage() match {
        case StatusReply.Error(_) => // Expected
        case _ => fail("Should have rejected duplicate")
      }
    }

    "track resource usage" in {
      val coordinator = spawn(CoordinatorAggregate())
      val probe = createTestProbe[StatusReply[CoordinatorState]]()

      coordinator ! RegisterPipeline("p1", "Pipeline 1", probe.ref)
      probe.receiveMessage()

      coordinator ! ReportResourceUsage("p1", 10.0, 1024L)

      coordinator ! GetSystemStatus(probe.ref)
      val state = probe.receiveMessage()
      assert(state.totalCpuPercent == 10.0)
      assert(state.totalMemoryMB == 1024L)
    }
  }
}
```

## Future Enhancements

1. **Dynamic Resource Limits**: Configure limits via application.conf
2. **Multi-Datacenter**: Support multiple coordinator instances per datacenter
3. **Pipeline Groups**: Group pipelines by team, project, or namespace
4. **Priority Scheduling**: Prioritize resources for high-priority pipelines
5. **Auto-Scaling**: Automatically scale pipelines based on resource usage
6. **Cost Tracking**: Track and report resource costs per pipeline
7. **Alerting Integration**: Direct integration with alerting systems
8. **Dashboard**: Web UI for visualizing system status

## References

- **CoordinatorAggregate**: `dataflow-core/src/main/scala/com/dataflow/aggregates/coordinator/CoordinatorAggregate.scala`
- **Commands**: `dataflow-core/src/main/scala/com/dataflow/domain/coordinator/CoordinatorCommands.scala`
- **Events**: `dataflow-core/src/main/scala/com/dataflow/domain/coordinator/CoordinatorEvents.scala`
- **State**: `dataflow-core/src/main/scala/com/dataflow/domain/coordinator/CoordinatorState.scala`
- **Cluster Singleton**: https://pekko.apache.org/docs/pekko/current/typed/cluster-singleton.html

## Conclusion

The Coordinator Aggregate provides centralized management and monitoring of all pipelines in the DataFlow Platform. It tracks pipeline status, monitors resource usage, and provides system-wide queries and operations. The event-sourced design ensures consistency and durability, while the cluster singleton pattern ensures a single source of truth across the distributed system.

**Key Benefits**:
- Centralized pipeline registry
- Resource management and allocation
- System-wide health monitoring
- Consistent view across cluster
- Event-sourced persistence
- Scalable query capabilities

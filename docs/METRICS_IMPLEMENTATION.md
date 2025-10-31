# Metrics Collection Implementation

## Overview

This document describes the implementation of comprehensive metrics collection using Kamon for the DataFlow Platform. Metrics are exported via Prometheus for monitoring and alerting.

## Architecture

### Components

1. **Kamon** - Metrics collection and instrumentation library
2. **Prometheus** - Time-series database for metrics storage and querying
3. **MetricsReporter** - Custom reporter for pipeline-specific metrics
4. **PipelineAggregate** - Integrated metrics recording in event handler

### Metrics Flow

```
PipelineAggregate Events
         ↓
    MetricsReporter
         ↓
      Kamon Core
         ↓
  Prometheus Reporter
         ↓
Prometheus Scraper (/metrics endpoint on port 9095)
         ↓
   Prometheus Server
         ↓
Grafana/AlertManager (visualization and alerting)
```

## Dependencies

Added to `build.sbt` (dataflow-core module):

```scala
lazy val kamonVersion = "2.7.5"

libraryDependencies ++= Seq(
  "io.kamon" %% "kamon-core" % kamonVersion,
  "io.kamon" %% "kamon-prometheus" % kamonVersion,
  "io.kamon" %% "kamon-system-metrics" % kamonVersion,
  "io.kamon" %% "kamon-akka" % kamonVersion,
)
```

## Configuration

### Kamon Configuration (application.conf)

Key settings:

```hocon
kamon {
  enabled = true

  environment {
    service = "dataflow-platform"
  }

  metric {
    tick-interval = 10 seconds
  }

  prometheus {
    start-embedded-http-server = yes
    embedded-server {
      hostname = "0.0.0.0"
      port = 9095
    }
  }

  system-metrics {
    jvm.enabled = yes
    process.enabled = yes
  }
}
```

### Environment Variables

Override configuration using environment variables:

- `KAMON_ENABLED` - Enable/disable Kamon (default: true)
- `KAMON_SERVICE_NAME` - Service name for metrics (default: dataflow-platform)
- `KAMON_PROMETHEUS_PORT` - Prometheus scrape endpoint port (default: 9095)
- `KAMON_STATUS_PAGE_ENABLED` - Enable Kamon status page (default: yes)

## Metrics Published

### Pipeline State Metrics

**Gauges** - Current state counts:

- `dataflow.pipelines.running` - Number of currently running pipelines
- `dataflow.pipelines.stopped` - Number of stopped pipelines
- `dataflow.pipelines.paused` - Number of paused pipelines
- `dataflow.pipelines.failed` - Number of failed pipelines

**Counters** - State transitions:

- `dataflow.state.transitions{pipeline_id, from_state, to_state}` - Total state transitions

### Batch Processing Metrics

**Counters**:

- `dataflow.batches.processed{pipeline_id}` - Total batches processed successfully
- `dataflow.batches.failed{pipeline_id, error_code}` - Total batches that failed
- `dataflow.batches.timeouts{pipeline_id}` - Total batch timeouts

**Histograms**:

- `dataflow.batch.processing.time.ms{pipeline_id}` - Batch processing time distribution

Buckets: `[1, 5, 10, 25, 50, 75, 100, 250, 500, 750, 1000, 2500, 5000, 7500, 10000]` ms

### Record Processing Metrics

**Counters**:

- `dataflow.records.processed{pipeline_id}` - Total records processed successfully
- `dataflow.records.failed{pipeline_id}` - Total records that failed

**Gauges**:

- `dataflow.records.throughput.per.second{pipeline_id}` - Current throughput (records/sec)

### Error and Retry Metrics

**Counters**:

- `dataflow.retries.scheduled{pipeline_id, error_code}` - Total retries scheduled
- `dataflow.checkpoint.updates{pipeline_id}` - Total checkpoint updates

### System Metrics (JVM)

Automatically collected by Kamon:

- `jvm.memory.heap.used` - Heap memory usage
- `jvm.memory.non-heap.used` - Non-heap memory usage
- `jvm.gc.promotion` - GC promotion rate
- `jvm.gc.pause` - GC pause time
- `jvm.threads.total` - Number of threads
- `process.cpu.usage` - Process CPU usage

## MetricsReporter API

### Initialization

```scala
// At application startup
MetricsReporter.init()

// At application shutdown
MetricsReporter.shutdown()
```

### Recording Metrics

**State Transitions**:

```scala
MetricsReporter.recordStateTransition(
  pipelineId = "pipeline-123",
  fromState = ConfiguredState(...),
  toState = RunningState(...)
)
```

**Batch Processing**:

```scala
MetricsReporter.recordBatchProcessed(
  pipelineId = "pipeline-123",
  successCount = 1000,
  failureCount = 5,
  processingTimeMs = 250L
)
```

**Batch Failures**:

```scala
MetricsReporter.recordBatchFailed(
  pipelineId = "pipeline-123",
  errorCode = "TIMEOUT"
)
```

**Retries**:

```scala
MetricsReporter.recordRetryScheduled(
  pipelineId = "pipeline-123",
  errorCode = "CONNECTION_FAILED",
  retryCount = 2
)
```

**Batch Timeouts**:

```scala
MetricsReporter.recordBatchTimeout(
  pipelineId = "pipeline-123",
  batchId = "batch-456"
)
```

**Checkpoint Updates**:

```scala
MetricsReporter.recordCheckpointUpdate(
  pipelineId = "pipeline-123",
  offset = 1000L
)
```

**Pipeline Metrics**:

```scala
MetricsReporter.updatePipelineMetrics(
  pipelineId = "pipeline-123",
  metrics = PipelineMetrics(...)
)
```

## Integration with PipelineAggregate

Metrics are recorded automatically in the event handler:

### State Transitions

```scala
case PipelineStarted(_, ts) =>
  val cfg = state.asInstanceOf[ConfiguredState]
  val newState = RunningState(...)
  MetricsReporter.recordStateTransition(cfg.pipelineId, state, newState)
  newState
```

### Batch Processing

```scala
case BatchProcessed(pipelineId, batchId, ok, ko, timeMs, _) =>
  val r = state.asInstanceOf[RunningState]
  MetricsReporter.recordBatchProcessed(pipelineId, ok, ko, timeMs)
  val newMetrics = r.metrics.incrementBatch(ok, ko, timeMs)
  MetricsReporter.updatePipelineMetrics(pipelineId, newMetrics)
  r.copy(metrics = newMetrics, ...)
```

### Retries

```scala
case RetryScheduled(pipelineId, error, retryCount, _, _) =>
  state match {
    case r: RunningState =>
      MetricsReporter.recordRetryScheduled(pipelineId, error.code, retryCount)
      r.copy(retryCount = retryCount)
    case s => s
  }
```

## Prometheus Integration

### Scraping Configuration

Add to `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'dataflow-platform'
    scrape_interval: 15s
    static_configs:
      - targets: ['localhost:9095']
        labels:
          environment: 'production'
          service: 'dataflow'
```

### Querying Metrics

**Pipeline State**:

```promql
# Number of running pipelines
dataflow_pipelines_running

# State transitions per minute
rate(dataflow_state_transitions_total[1m])
```

**Batch Throughput**:

```promql
# Batches processed per second
rate(dataflow_batches_processed_total{pipeline_id="pipeline-123"}[1m])

# Average batch processing time
rate(dataflow_batch_processing_time_ms_sum[5m]) /
rate(dataflow_batch_processing_time_ms_count[5m])

# 95th percentile batch processing time
histogram_quantile(0.95,
  rate(dataflow_batch_processing_time_ms_bucket[5m])
)
```

**Record Throughput**:

```promql
# Records processed per second
rate(dataflow_records_processed_total{pipeline_id="pipeline-123"}[1m])

# Current throughput gauge
dataflow_records_throughput_per_second{pipeline_id="pipeline-123"}
```

**Error Rate**:

```promql
# Batch failure rate
rate(dataflow_batches_failed_total[5m])

# Record failure rate
rate(dataflow_records_failed_total[5m])

# Retry rate by error code
sum(rate(dataflow_retries_scheduled_total[5m])) by (error_code)
```

**System Health**:

```promql
# JVM heap usage
jvm_memory_heap_used_bytes / jvm_memory_heap_max_bytes * 100

# GC pressure
rate(jvm_gc_pause_seconds_sum[1m])

# CPU usage
process_cpu_usage
```

## Grafana Dashboards

### Recommended Dashboard Panels

1. **Pipeline Overview**:
   - Gauge: Running pipelines count
   - Gauge: Failed pipelines count
   - Graph: State transitions over time

2. **Throughput**:
   - Graph: Batches processed/sec
   - Graph: Records processed/sec
   - Graph: Processing time percentiles (p50, p95, p99)

3. **Errors and Retries**:
   - Graph: Batch failure rate
   - Graph: Record failure rate
   - Graph: Retries by error code
   - Stat: Total timeouts

4. **System Health**:
   - Graph: JVM heap usage
   - Graph: GC pause time
   - Graph: CPU usage
   - Graph: Thread count

### Example Panel JSON

```json
{
  "title": "Batch Processing Rate",
  "targets": [
    {
      "expr": "rate(dataflow_batches_processed_total[1m])",
      "legendFormat": "{{pipeline_id}}"
    }
  ],
  "type": "graph"
}
```

## Alerting

### Recommended Alerts

**High Failure Rate**:

```yaml
alert: HighBatchFailureRate
expr: |
  rate(dataflow_batches_failed_total[5m]) > 0.1
for: 5m
labels:
  severity: warning
annotations:
  summary: "High batch failure rate detected"
```

**Pipeline Stuck**:

```yaml
alert: PipelineStuck
expr: |
  rate(dataflow_batches_processed_total[10m]) == 0
  and dataflow_pipelines_running > 0
for: 10m
labels:
  severity: critical
annotations:
  summary: "Pipeline appears stuck - no batches processed"
```

**High Retry Rate**:

```yaml
alert: HighRetryRate
expr: |
  rate(dataflow_retries_scheduled_total[5m]) > 1
for: 5m
labels:
  severity: warning
annotations:
  summary: "High retry rate detected"
```

**Timeout Spike**:

```yaml
alert: BatchTimeoutSpike
expr: |
  rate(dataflow_batches_timeouts_total[5m]) > 0.5
for: 5m
labels:
  severity: warning
annotations:
  summary: "Batch timeout rate is elevated"
```

**Memory Pressure**:

```yaml
alert: HighMemoryUsage
expr: |
  jvm_memory_heap_used_bytes / jvm_memory_heap_max_bytes > 0.9
for: 5m
labels:
  severity: critical
annotations:
  summary: "JVM heap usage above 90%"
```

## Monitoring Endpoints

### Prometheus Metrics

- **URL**: `http://localhost:9095/metrics`
- **Format**: Prometheus text format
- **Example**:

```
# HELP dataflow_batches_processed_total
# TYPE dataflow_batches_processed_total counter
dataflow_batches_processed_total{pipeline_id="pipeline-123"} 1234

# HELP dataflow_batch_processing_time_ms
# TYPE dataflow_batch_processing_time_ms histogram
dataflow_batch_processing_time_ms_bucket{pipeline_id="pipeline-123",le="10"} 45
dataflow_batch_processing_time_ms_bucket{pipeline_id="pipeline-123",le="50"} 120
dataflow_batch_processing_time_ms_sum{pipeline_id="pipeline-123"} 15678
dataflow_batch_processing_time_ms_count{pipeline_id="pipeline-123"} 234
```

### Kamon Status Page

- **URL**: `http://localhost:5266`
- **Purpose**: Debug endpoint for Kamon internal metrics
- **Disable in production**: Set `KAMON_STATUS_PAGE_ENABLED=false`

## Performance Considerations

### Overhead

- **Metrics Collection**: ~1-2% CPU overhead
- **Memory**: ~10-20 MB for metrics storage
- **Network**: ~1-5 KB/sec to Prometheus

### Optimization Tips

1. **Sampling**: Use metric filters to exclude noisy metrics
2. **Aggregation**: Use histogram buckets appropriate for your use case
3. **Cardinality**: Avoid high-cardinality tags (e.g., batch IDs)
4. **Tick Interval**: Increase `tick-interval` for lower overhead

### Cardinality Management

**Good** (low cardinality):

```scala
counter.withTag("pipeline_id", pipelineId)        // ~100s of pipelines
counter.withTag("error_code", errorCode)          // ~10s of error codes
counter.withTag("from_state", stateToString(...)) // 6 states
```

**Bad** (high cardinality):

```scala
counter.withTag("batch_id", batchId)       // Millions of unique values
counter.withTag("record_id", recordId)     // Millions of unique values
counter.withTag("timestamp", timestamp)    // Infinite unique values
```

## Testing

### Unit Tests

Test metrics recording:

```scala
class MetricsReporterSpec extends AnyFlatSpec {
  "MetricsReporter" should "record batch processed" in {
    MetricsReporter.recordBatchProcessed("test-pipeline", 100, 0, 50L)

    val snapshot = Kamon.counter("dataflow.batches.processed")
      .withTag("pipeline_id", "test-pipeline")
      .value()

    assert(snapshot == 1)
  }
}
```

### Integration Tests

Test end-to-end metrics flow:

```scala
class MetricsIntegrationSpec extends ScalaTestWithActorTestKit {
  "PipelineAggregate" should "publish metrics on events" in {
    val pipeline = spawn(PipelineAggregate("test-pipeline"))
    val probe = createTestProbe[State]()

    // Create pipeline
    pipeline ! CreatePipeline(...)

    // Check metrics
    eventually {
      val stateTransitions = Kamon.counter("dataflow.state.transitions").value()
      assert(stateTransitions > 0)
    }
  }
}
```

## Troubleshooting

### Metrics Not Appearing

1. **Check Kamon initialization**:
   ```scala
   MetricsReporter.init()
   ```

2. **Verify Prometheus endpoint**:
   ```bash
   curl http://localhost:9095/metrics
   ```

3. **Check Kamon status**:
   ```bash
   curl http://localhost:5266
   ```

4. **Enable debug logging**:
   ```hocon
   kamon.trace.level = DEBUG
   ```

### High Cardinality Issues

**Symptoms**:
- High memory usage
- Slow Prometheus queries
- Scrape timeouts

**Solutions**:
- Remove high-cardinality tags (batch IDs, record IDs)
- Use metric filters to exclude unwanted metrics
- Increase Prometheus memory limits

### Performance Degradation

**Symptoms**:
- Increased CPU usage
- Slower event processing

**Solutions**:
- Increase `tick-interval` (e.g., 30s instead of 10s)
- Disable unused instrumentation modules
- Use sampling for high-frequency metrics

## References

- **Kamon Documentation**: https://kamon.io/docs/latest/
- **Prometheus Documentation**: https://prometheus.io/docs/
- **Grafana Dashboards**: https://grafana.com/docs/
- **MetricsReporter**: `dataflow-core/src/main/scala/com/dataflow/metrics/MetricsReporter.scala`
- **Configuration**: `dataflow-core/src/main/resources/application.conf`

## Future Enhancements

1. **Distributed Tracing**: Add support for Zipkin/Jaeger
2. **Custom Metrics**: Allow pipeline-specific custom metrics
3. **Metrics API**: Expose metrics via REST API for dashboard
4. **Anomaly Detection**: Implement ML-based anomaly detection
5. **Cost Tracking**: Track resource usage and costs per pipeline
6. **SLA Monitoring**: Track SLA compliance metrics

## Conclusion

The metrics implementation provides comprehensive observability into the DataFlow Platform. All critical metrics are captured and exported to Prometheus for monitoring, alerting, and analysis. The integration is automatic and requires no additional code from pipeline developers.

**Key Benefits**:
- Real-time visibility into pipeline health
- Proactive alerting on issues
- Performance analysis and optimization
- Capacity planning and resource management
- Root cause analysis for failures

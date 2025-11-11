# Grafana Dashboard Metrics Setup Guide

## Overview

This guide explains why Grafana shows "no data" and how to fix it.

## Architecture

```
DataFlow App (Kamon) → Prometheus (Scraper) → Grafana (Visualization)
     :9095                  :9090                    :3000
```

## Problem: No Data in Grafana

The Grafana dashboard shows "no data" because:

1. **Application Not Running**: The DataFlow application needs to be running and exposing metrics
2. **Metrics Not Exposed**: Kamon needs to export metrics in Prometheus format on port 9095
3. **Prometheus Can't Scrape**: Prometheus cannot reach the application to scrape metrics

## Solution

### Step 1: Verify Kamon Configuration

Ensure Kamon is configured to export Prometheus metrics in `application.conf`:

```hocon
kamon {
  # Enable Prometheus reporter
  prometheus {
    enabled = true

    # Embedded HTTP server for metrics
    embedded-server {
      hostname = "0.0.0.0"
      port = 9095
    }

    # Include environment tags
    environment {
      service = "dataflow-platform"
      instance = "local"
    }
  }

  # Metric filters
  metric.filters {
    pekko-actor {
      includes = ["**"]
      excludes = []
    }

    pekko-dispatcher {
      includes = ["**"]
      excludes = []
    }

    pekko-stream {
      includes = ["**"]
      excludes = []
    }
  }

  # APM integration
  apm {
    api-key = ${?KAMON_API_KEY}
  }
}
```

### Step 2: Add Kamon Prometheus Module

Ensure `kamon-prometheus` dependency is in `build.sbt`:

```scala
lazy val metricsDependencies = Seq(
  "io.kamon" %% "kamon-core"       % kamonVersion,
  "io.kamon" %% "kamon-prometheus" % kamonVersion,  // ← Required!
  "io.kamon" %% "kamon-system-metrics" % kamonVersion,
  "io.kamon" %% "kamon-akka" % kamonVersion,  // Works with Pekko
)
```

### Step 3: Initialize Kamon in Application

Add Kamon initialization in your Main class:

```scala
import kamon.Kamon

object Main extends App {
  // Initialize Kamon at startup
  Kamon.init()

  // Your application code
  val system = ActorSystem(...)

  // Shutdown Kamon on application exit
  scala.sys.addShutdownHook {
    Kamon.stop()
    system.terminate()
  }
}
```

### Step 4: Verify Metrics Endpoint

Once your application is running, verify metrics are exposed:

```bash
# Check if metrics endpoint is accessible
curl http://localhost:9095/metrics

# Should return Prometheus-format metrics like:
# # TYPE jvm_memory_used gauge
# jvm_memory_used{area="heap"} 123456789.0
# # TYPE pekko_actor_count gauge
# pekko_actor_count{system="dataflow-system"} 42.0
```

### Step 5: Verify Prometheus is Scraping

1. Start Docker services:
   ```bash
   cd docker
   docker-compose up -d prometheus grafana
   ```

2. Check Prometheus targets:
   ```bash
   open http://localhost:9090/targets
   ```

3. Look for the `dataflow-platform` job:
   - **Status: UP** = Metrics are being scraped successfully
   - **Status: DOWN** = Prometheus cannot reach the application

4. If status is DOWN, check:
   - Is the application running?
   - Is it listening on port 9095?
   - Can you curl localhost:9095/metrics?

### Step 6: Query Metrics in Prometheus

Test queries in Prometheus UI (http://localhost:9090/graph):

```promql
# JVM memory usage
jvm_memory_used{area="heap"}

# Actor count
pekko_actor_count

# HTTP requests (if applicable)
http_requests_total

# Custom application metrics
dataflow_pipeline_records_processed_total
dataflow_source_records_read_total
dataflow_sink_records_written_total
```

### Step 7: Access Grafana Dashboard

1. Open Grafana: http://localhost:3000
2. Login: admin / admin
3. Navigate to Dashboards
4. Open "DataFlow Platform" dashboard
5. You should now see metrics!

## Common Issues and Fixes

### Issue 1: "Connection Refused" in Prometheus Targets

**Problem**: Prometheus shows `connection refused` for dataflow-platform target

**Solution**:
- Application is not running or not exposing metrics on port 9095
- Start your application with Kamon properly initialized
- Verify with: `curl http://localhost:9095/metrics`

### Issue 2: No Metrics in Response

**Problem**: curl http://localhost:9095/metrics returns empty or 404

**Solution**:
- Kamon Prometheus module not added to dependencies
- Kamon not initialized in application
- Check application logs for Kamon initialization errors

### Issue 3: Metrics Showing in Prometheus but Not in Grafana

**Problem**: Metrics visible in Prometheus but Grafana shows "no data"

**Solution**:
- Check Grafana datasource configuration
- Verify Prometheus URL is correct: http://prometheus:9090
- Test connection in Grafana datasource settings
- Check dashboard time range (last 5 minutes, last hour, etc.)

### Issue 4: Docker Network Issues

**Problem**: Prometheus cannot reach application on host machine

**Solution for Mac/Windows**:
```yaml
# Use host.docker.internal in prometheus.yml
- targets: ['host.docker.internal:9095']
```

**Solution for Linux**:
```yaml
# Use bridge network IP in prometheus.yml
- targets: ['172.17.0.1:9095']

# Or add application to docker network:
docker run --network docker_dataflow-network ...
```

## Custom Metrics

To add custom metrics in your application:

```scala
import kamon.Kamon
import kamon.metric.Counter

// Define metric
val recordsProcessed: Counter = Kamon.counter("dataflow.records.processed")
  .withTag("pipeline", "my-pipeline")
  .withTag("source", "kafka")

// Increment metric
recordsProcessed.increment()
recordsProcessed.increment(10)

// In Grafana, query:
// rate(dataflow_records_processed_total[5m])
```

## Monitoring Best Practices

1. **Always Initialize Kamon Early**
   - Call `Kamon.init()` at the start of your application
   - Before creating ActorSystem or any Pekko components

2. **Use Descriptive Metric Names**
   - Follow Prometheus naming conventions
   - Use underscores: `dataflow_pipeline_records_total`
   - Include units: `_bytes`, `_seconds`, `_total`

3. **Add Meaningful Tags**
   - pipeline_id, source_type, sink_type
   - environment (dev, staging, prod)
   - instance_id for distributed systems

4. **Create Dashboards Incrementally**
   - Start with basic metrics (JVM, actors)
   - Add application-specific metrics
   - Create alerts for critical thresholds

## Testing the Setup

Complete end-to-end test:

```bash
# 1. Start infrastructure
cd docker && docker-compose up -d

# 2. Build and run application
sbt compile
sbt "dataflow-examples/runMain com.dataflow.examples.FileToConsoleApp"

# 3. Verify metrics in terminal
curl http://localhost:9095/metrics | head -20

# 4. Check Prometheus targets
open http://localhost:9090/targets
# Verify dataflow-platform is UP

# 5. Query metrics in Prometheus
open http://localhost:9090/graph
# Query: pekko_actor_count

# 6. View in Grafana
open http://localhost:3000
# Navigate to DataFlow dashboard
# Select appropriate time range
```

## Next Steps

1. ✅ Fix Kamon configuration
2. ✅ Initialize Kamon in Main class
3. ✅ Add custom metrics for pipelines
4. ✅ Create dashboards for:
   - Pipeline throughput
   - Source ingestion rates
   - Sink write rates
   - Transform processing times
   - Error rates
5. ✅ Set up alerts for critical metrics

## References

- [Kamon Documentation](https://kamon.io/docs/)
- [Kamon Prometheus Module](https://kamon.io/docs/latest/reporters/prometheus/)
- [Prometheus Configuration](https://prometheus.io/docs/prometheus/latest/configuration/configuration/)
- [Grafana Provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/)

# DataFlow Platform - Source Connector Metrics Guide

This guide covers metrics instrumentation, monitoring, and observability for DataFlow Platform source connectors.

## Table of Contents

- [Overview](#overview)
- [Metrics Architecture](#metrics-architecture)
- [Available Metrics](#available-metrics)
- [Prometheus Queries](#prometheus-queries)
- [Grafana Dashboards](#grafana-dashboards)
- [Alerting](#alerting)
- [Troubleshooting with Metrics](#troubleshooting-with-metrics)

## Overview

All DataFlow source connectors are instrumented with comprehensive Kamon metrics that export to Prometheus. These metrics provide visibility into:

- **Performance**: Throughput, latency, processing times
- **Health**: Source status, connection errors, parse errors
- **Progress**: Offsets, watermarks, pagination state
- **Source-Specific**: Kafka lag, API response times, database query performance

### Metrics Stack

```
Source Connector (Kamon) → Prometheus → Grafana → Alerts
```

- **Kamon**: Metrics instrumentation library
- **Prometheus**: Time-series metrics database
- **Grafana**: Visualization and dashboards
- **AlertManager**: Alert routing and notifications

## Metrics Architecture

### Metric Types

DataFlow uses three types of Kamon metrics:

1. **Counters**: Monotonically increasing values (e.g., records read, errors)
2. **Gauges**: Current values that can go up or down (e.g., health status, offset)
3. **Histograms**: Distribution of values (e.g., latency, batch size)

### Metric Naming Convention

```
dataflow.source.<metric_name>{tags}
```

**Example**:
```promql
dataflow.source.records.read{pipeline_id="orders-pipeline", source_type="kafka"}
```

### Common Tags

All source metrics include these tags:

- `pipeline_id`: Unique pipeline identifier
- `source_type`: Type of source (`file`, `kafka`, `api`, `database`)

Additional tags vary by metric (e.g., `topic`, `partition`, `method`, `status`).

## Available Metrics

### General Ingestion Metrics

#### Records Read
```
dataflow_source_records_read_total{pipeline_id, source_type}
```
**Type**: Counter
**Description**: Total number of records read from the source
**Tags**: pipeline_id, source_type

#### Bytes Read
```
dataflow_source_bytes_read_total{pipeline_id, source_type}
```
**Type**: Counter
**Description**: Total bytes read from the source
**Tags**: pipeline_id, source_type

#### Records Skipped
```
dataflow_source_records_skipped_total{pipeline_id, source_type}
```
**Type**: Counter
**Description**: Records skipped (e.g., due to resume offset)
**Tags**: pipeline_id, source_type

### Batch Processing Metrics

#### Batches Sent
```
dataflow_source_batches_sent_total{pipeline_id, source_type}
```
**Type**: Counter
**Description**: Total batches sent to pipeline
**Tags**: pipeline_id, source_type

#### Batch Size
```
dataflow_source_batch_size_records{pipeline_id, source_type}
```
**Type**: Histogram
**Description**: Distribution of batch sizes (number of records)
**Tags**: pipeline_id, source_type

#### Batch Latency
```
dataflow_source_batch_latency_ms{pipeline_id, source_type}
```
**Type**: Histogram
**Description**: Time from record read to batch emission (milliseconds)
**Tags**: pipeline_id, source_type

### Error Metrics

#### Total Errors
```
dataflow_source_errors_total{pipeline_id, source_type, error_type}
```
**Type**: Counter
**Description**: Total errors by type
**Tags**: pipeline_id, source_type, error_type

**Error Types**: `connection_failure`, `stream_failure`, `query_failure`, `http_<status>`, `invalid_response_path`

#### Connection Errors
```
dataflow_source_connection_errors_total{pipeline_id, source_type}
```
**Type**: Counter
**Description**: Failed connections to source system
**Tags**: pipeline_id, source_type

#### Parse Errors
```
dataflow_source_parse_errors_total{pipeline_id, source_type, format}
```
**Type**: Counter
**Description**: Failed to parse data in specified format
**Tags**: pipeline_id, source_type, format (csv, json, etc.)

### Health Metrics

#### Source Health
```
dataflow_source_healthy{pipeline_id, source_type}
```
**Type**: Gauge
**Description**: Source health status (1.0 = healthy, 0.0 = unhealthy)
**Tags**: pipeline_id, source_type

#### Current Offset
```
dataflow_source_offset{pipeline_id, source_type}
```
**Type**: Gauge
**Description**: Current offset/position in source
**Tags**: pipeline_id, source_type

### Kafka-Specific Metrics

#### Kafka Messages Consumed
```
dataflow_source_kafka_messages_consumed_total{pipeline_id, topic}
```
**Type**: Counter
**Description**: Total Kafka messages consumed
**Tags**: pipeline_id, topic

#### Kafka Consumer Lag
```
dataflow_source_kafka_lag{pipeline_id, topic, partition}
```
**Type**: Gauge
**Description**: Consumer lag (high watermark - current offset)
**Tags**: pipeline_id, topic, partition

#### Kafka Offset
```
dataflow_source_kafka_offset{pipeline_id, topic, partition}
```
**Type**: Gauge
**Description**: Current Kafka consumer offset
**Tags**: pipeline_id, topic, partition

### API-Specific Metrics

#### API Requests
```
dataflow_source_api_requests_total{pipeline_id, method}
```
**Type**: Counter
**Description**: Total API requests made
**Tags**: pipeline_id, method (GET, POST)

#### API Response Time
```
dataflow_source_api_response_time_ms{pipeline_id, method}
```
**Type**: Histogram
**Description**: API response time distribution (milliseconds)
**Tags**: pipeline_id, method

#### API Response Status
```
dataflow_source_api_response_status_total{pipeline_id, method, status}
```
**Type**: Counter
**Description**: API responses by HTTP status code
**Tags**: pipeline_id, method, status (200, 404, 429, 500, etc.)

#### API Pagination Page
```
dataflow_source_api_pagination_page{pipeline_id}
```
**Type**: Gauge
**Description**: Current pagination page number
**Tags**: pipeline_id

### Database-Specific Metrics

#### Database Queries
```
dataflow_source_db_queries_total{pipeline_id, query_type}
```
**Type**: Counter
**Description**: Total database queries executed
**Tags**: pipeline_id, query_type (select, insert, etc.)

#### Database Query Time
```
dataflow_source_db_query_time_ms{pipeline_id, query_type}
```
**Type**: Histogram
**Description**: Query execution time distribution (milliseconds)
**Tags**: pipeline_id, query_type

#### Database Rows Read
```
dataflow_source_db_rows_read_total{pipeline_id, query_type}
```
**Type**: Counter
**Description**: Total rows read from database
**Tags**: pipeline_id, query_type

#### Database Incremental Watermark
```
dataflow_source_db_incremental_watermark{pipeline_id}
```
**Type**: Gauge
**Description**: Current watermark value for incremental sync (timestamp or ID)
**Tags**: pipeline_id

### File-Specific Metrics

#### File Lines Read
```
dataflow_source_file_lines_read_total{pipeline_id}
```
**Type**: Counter
**Description**: Total lines read from file
**Tags**: pipeline_id

#### File Size
```
dataflow_source_file_size_bytes{pipeline_id}
```
**Type**: Gauge
**Description**: File size in bytes
**Tags**: pipeline_id

#### File Read Progress
```
dataflow_source_file_read_progress{pipeline_id}
```
**Type**: Gauge
**Description**: File read progress (0.0 to 1.0)
**Tags**: pipeline_id

## Prometheus Queries

### Basic Queries

**Records ingested per second (overall)**:
```promql
rate(dataflow_source_records_read_total[5m])
```

**Records ingested per second by source type**:
```promql
sum by (source_type) (rate(dataflow_source_records_read_total[5m]))
```

**Records ingested per second by pipeline**:
```promql
sum by (pipeline_id) (rate(dataflow_source_records_read_total[5m]))
```

**Total bytes ingested in last hour**:
```promql
increase(dataflow_source_bytes_read_total[1h])
```

### Performance Queries

**Batch latency p95 (all sources)**:
```promql
histogram_quantile(0.95,
  sum by (le) (rate(dataflow_source_batch_latency_ms_bucket[5m]))
)
```

**Batch latency p95 by source type**:
```promql
histogram_quantile(0.95,
  sum by (source_type, le) (rate(dataflow_source_batch_latency_ms_bucket[5m]))
)
```

**Average batch size**:
```promql
sum(rate(dataflow_source_batch_size_records_sum[5m])) /
sum(rate(dataflow_source_batch_size_records_count[5m]))
```

**API response time p99**:
```promql
histogram_quantile(0.99,
  rate(dataflow_source_api_response_time_ms_bucket[5m])
)
```

**Database query time p95**:
```promql
histogram_quantile(0.95,
  rate(dataflow_source_db_query_time_ms_bucket[5m])
)
```

### Health Queries

**Unhealthy sources (down)**:
```promql
dataflow_source_healthy == 0
```

**Number of healthy sources by type**:
```promql
sum by (source_type) (dataflow_source_healthy)
```

**Sources with connection errors in last 5 minutes**:
```promql
increase(dataflow_source_connection_errors_total[5m]) > 0
```

### Error Rate Queries

**Overall error rate (errors per second)**:
```promql
sum(rate(dataflow_source_errors_total[5m]))
```

**Error rate by type**:
```promql
sum by (error_type) (rate(dataflow_source_errors_total[5m]))
```

**Parse error percentage**:
```promql
(rate(dataflow_source_parse_errors_total[5m]) /
 rate(dataflow_source_records_read_total[5m])) * 100
```

**API error responses (4xx, 5xx) percentage**:
```promql
(sum(rate(dataflow_source_api_response_status_total{status=~"[45].."}[5m])) /
 sum(rate(dataflow_source_api_response_status_total[5m]))) * 100
```

### Kafka-Specific Queries

**Kafka consumer lag by topic**:
```promql
sum by (topic) (dataflow_source_kafka_lag)
```

**Kafka consumer lag by partition**:
```promql
dataflow_source_kafka_lag{topic="your-topic"}
```

**Kafka messages consumed per second**:
```promql
rate(dataflow_source_kafka_messages_consumed_total[5m])
```

**Kafka partitions with high lag (> 1000)**:
```promql
dataflow_source_kafka_lag > 1000
```

### Database-Specific Queries

**Database query time trend**:
```promql
rate(dataflow_source_db_query_time_ms_sum[5m]) /
rate(dataflow_source_db_query_time_ms_count[5m])
```

**Database incremental watermark progress**:
```promql
dataflow_source_db_incremental_watermark{pipeline_id="your-pipeline"}
```

**Database rows per query (average)**:
```promql
rate(dataflow_source_db_rows_read_total[5m]) /
rate(dataflow_source_db_queries_total[5m])
```

### File-Specific Queries

**File read progress percentage**:
```promql
dataflow_source_file_read_progress * 100
```

**Files not making progress (stuck)**:
```promql
dataflow_source_file_read_progress < 1 and
changes(dataflow_source_file_read_progress[5m]) == 0
```

## Grafana Dashboards

### Recommended Dashboard Structure

#### Overview Panel
- Total records ingested (counter)
- Ingestion rate (rate gauge)
- Active sources (gauge)
- Error rate (rate gauge)

#### Performance Panel
- Batch latency histogram
- Throughput by source type (graph)
- API response time p95/p99 (graph)
- Database query time p95/p99 (graph)

#### Health Panel
- Source health status (stat)
- Connection errors (counter)
- Parse errors (counter)
- Recent errors (table)

#### Source-Specific Panels

**Kafka Panel**:
- Consumer lag by topic (graph)
- Messages consumed rate (graph)
- Partition offsets (table)

**API Panel**:
- Response time by endpoint (graph)
- Status code distribution (pie chart)
- Pagination progress (gauge)
- Rate limit errors (counter)

**Database Panel**:
- Query execution time (graph)
- Rows fetched rate (graph)
- Incremental watermark (graph)
- Connection pool metrics (future)

**File Panel**:
- Read progress (gauge)
- Lines read rate (graph)
- File size (stat)

### Example Dashboard JSON

```json
{
  "dashboard": {
    "title": "DataFlow Source Connectors",
    "panels": [
      {
        "title": "Records Ingested per Second",
        "targets": [
          {
            "expr": "sum(rate(dataflow_source_records_read_total[5m]))"
          }
        ],
        "type": "graph"
      },
      {
        "title": "Source Health Status",
        "targets": [
          {
            "expr": "dataflow_source_healthy",
            "legendFormat": "{{pipeline_id}} - {{source_type}}"
          }
        ],
        "type": "stat"
      },
      {
        "title": "Batch Latency P95",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, sum by (source_type, le) (rate(dataflow_source_batch_latency_ms_bucket[5m])))",
            "legendFormat": "{{source_type}}"
          }
        ],
        "type": "graph"
      },
      {
        "title": "Error Rate by Type",
        "targets": [
          {
            "expr": "sum by (error_type) (rate(dataflow_source_errors_total[5m]))",
            "legendFormat": "{{error_type}}"
          }
        ],
        "type": "graph"
      }
    ]
  }
}
```

## Alerting

### Critical Alerts

#### Source Down
```yaml
alert: SourceDown
expr: dataflow_source_healthy == 0
for: 5m
labels:
  severity: critical
annotations:
  summary: "Source {{$labels.pipeline_id}} is unhealthy"
  description: "Source {{$labels.pipeline_id}} ({{$labels.source_type}}) has been unhealthy for 5 minutes"
```

#### High Connection Error Rate
```yaml
alert: HighConnectionErrorRate
expr: rate(dataflow_source_connection_errors_total[5m]) > 1
for: 5m
labels:
  severity: critical
annotations:
  summary: "High connection error rate for {{$labels.pipeline_id}}"
  description: "Pipeline {{$labels.pipeline_id}} experiencing >1 connection error/sec for 5 minutes"
```

#### Kafka High Lag
```yaml
alert: KafkaHighLag
expr: dataflow_source_kafka_lag > 10000
for: 10m
labels:
  severity: warning
annotations:
  summary: "High Kafka lag on {{$labels.topic}} partition {{$labels.partition}}"
  description: "Consumer lag is {{$value}} messages"
```

### Warning Alerts

#### High Parse Error Rate
```yaml
alert: HighParseErrorRate
expr: (rate(dataflow_source_parse_errors_total[5m]) / rate(dataflow_source_records_read_total[5m])) > 0.05
for: 10m
labels:
  severity: warning
annotations:
  summary: "High parse error rate for {{$labels.pipeline_id}}"
  description: "Parse error rate is {{$value | humanizePercentage}} for {{$labels.source_type}}"
```

#### API Rate Limit Errors
```yaml
alert: APIRateLimitErrors
expr: increase(dataflow_source_api_response_status_total{status="429"}[5m]) > 0
for: 5m
labels:
  severity: warning
annotations:
  summary: "API rate limit hit for {{$labels.pipeline_id}}"
  description: "Received {{$value}} 429 responses in last 5 minutes"
```

#### Slow Database Queries
```yaml
alert: SlowDatabaseQueries
expr: histogram_quantile(0.95, rate(dataflow_source_db_query_time_ms_bucket[5m])) > 5000
for: 10m
labels:
  severity: warning
annotations:
  summary: "Slow database queries for {{$labels.pipeline_id}}"
  description: "P95 query time is {{$value}}ms (threshold: 5000ms)"
```

#### File Read Stuck
```yaml
alert: FileReadStuck
expr: dataflow_source_file_read_progress < 1 and changes(dataflow_source_file_read_progress[10m]) == 0
for: 15m
labels:
  severity: warning
annotations:
  summary: "File read stuck for {{$labels.pipeline_id}}"
  description: "File read progress at {{$value | humanizePercentage}} with no changes for 15 minutes"
```

## Troubleshooting with Metrics

### Scenario: Low Throughput

**Symptoms**: `dataflow_source_records_read_total` rate is low

**Investigation**:
1. Check source health: `dataflow_source_healthy`
2. Check error rates: `rate(dataflow_source_errors_total[5m])`
3. Check batch latency: `histogram_quantile(0.95, rate(dataflow_source_batch_latency_ms_bucket[5m]))`
4. Check source-specific metrics:
   - Kafka: Consumer lag
   - API: Response time, rate limits
   - Database: Query time

**Common Causes**:
- Backpressure from downstream
- Source system slowness
- Network issues
- Configuration (poll interval too high)

### Scenario: High Error Rate

**Symptoms**: `dataflow_source_errors_total` increasing rapidly

**Investigation**:
1. Identify error type: `sum by (error_type) (rate(dataflow_source_errors_total[5m]))`
2. Check parse errors by format: `dataflow_source_parse_errors_total`
3. Check connection errors: `dataflow_source_connection_errors_total`
4. For APIs, check status codes: `dataflow_source_api_response_status_total`

**Common Causes**:
- Data format changes
- Connection issues
- Authentication failures
- Schema mismatches

### Scenario: Kafka Consumer Lag Growing

**Symptoms**: `dataflow_source_kafka_lag` increasing

**Investigation**:
1. Check consumer rate: `rate(dataflow_source_kafka_messages_consumed_total[5m])`
2. Check producer rate (from Kafka metrics)
3. Check partition distribution: `dataflow_source_kafka_lag by (partition)`
4. Check batch processing time: `dataflow_source_batch_latency_ms`

**Common Causes**:
- Producer rate > consumer rate
- Slow downstream processing
- Insufficient consumer instances
- Partition skew

### Scenario: API Rate Limiting

**Symptoms**: `dataflow_source_api_response_status_total{status="429"}` increasing

**Investigation**:
1. Check request rate: `rate(dataflow_source_api_requests_total[5m])`
2. Check poll interval configuration
3. Check pagination size
4. Review API rate limit documentation

**Solutions**:
- Increase poll interval
- Reduce pagination size
- Implement exponential backoff
- Request higher rate limits from API provider

### Scenario: Database Query Performance

**Symptoms**: `dataflow_source_db_query_time_ms` p95 > threshold

**Investigation**:
1. Check query execution time trend
2. Check rows per query: `rate(dataflow_source_db_rows_read_total) / rate(dataflow_source_db_queries_total)`
3. Check watermark progress: `dataflow_source_db_incremental_watermark`
4. Review database metrics (from DB monitoring)

**Common Causes**:
- Missing indexes on incremental column
- Large result sets
- Database load
- Network latency

**Solutions**:
- Add indexes
- Increase fetch size
- Partition queries
- Use read replicas

## Best Practices

1. **Baseline Metrics**: Establish baselines for normal operation
2. **Alert Tuning**: Adjust alert thresholds based on your SLAs
3. **Dashboard Organization**: Group related metrics together
4. **Retention Policy**: Keep detailed metrics for at least 30 days
5. **Regular Review**: Review metrics trends weekly
6. **Correlation**: Correlate source metrics with pipeline and sink metrics
7. **Documentation**: Document custom queries and dashboards
8. **Testing**: Test metric collection in non-production first

## Next Steps

- Set up Prometheus scraping of Kamon metrics
- Import Grafana dashboards from JSON
- Configure AlertManager rules
- Integrate with incident management (PagerDuty, Slack)
- Review [SOURCE_CONNECTORS.md](SOURCE_CONNECTORS.md) for source configuration
- Review [METRICS_IMPLEMENTATION.md](METRICS_IMPLEMENTATION.md) for metrics architecture

# DataFlow Platform - API Documentation

> **Complete REST API reference for pipeline management**

---

## 📋 **Table of Contents**

- [Overview](#overview)
- [Base URL](#base-url)
- [Authentication](#authentication)
- [Common Responses](#common-responses)
- [Endpoints](#endpoints)
  - [Pipeline Management](#pipeline-management)
  - [Pipeline Lifecycle](#pipeline-lifecycle)
  - [Monitoring](#monitoring)
  - [WebSocket](#websocket)
- [Examples](#examples)
- [Error Handling](#error-handling)

---

## 🎯 **Overview**

The DataFlow Platform API provides REST endpoints for managing data pipelines. The API supports:

- **Pipeline CRUD operations** - Create, read, update pipelines
- **Lifecycle management** - Start, stop, pause, resume pipelines
- **Real-time monitoring** - Metrics and health checks
- **WebSocket streaming** - Real-time pipeline updates

---

## 🌐 **Base URL**

```
http://localhost:8080
```

All API endpoints are prefixed with `/api/v1`.

---

## 🔐 **Authentication**

Currently, the API does not require authentication (development mode).
In production, implement authentication using:
- JWT tokens
- API keys
- OAuth 2.0

---

## 📦 **Common Responses**

### Success Response
```json
{
  "message": "Operation completed successfully",
  "timestamp": "2025-01-15T10:30:00Z"
}
```

### Error Response
```json
{
  "error": "error_code",
  "message": "Human-readable error message",
  "details": "Optional detailed error information",
  "timestamp": "2025-01-15T10:30:00Z"
}
```

---

## 📡 **Endpoints**

### **Pipeline Management**

#### **Create Pipeline**

Create a new data pipeline.

- **URL:** `/api/v1/pipelines`
- **Method:** `POST`
- **Content-Type:** `application/json`

**Request Body:**
```json
{
  "name": "My Pipeline",
  "description": "Processes user events from Kafka to Elasticsearch",
  "sourceConfig": {
    "sourceType": "kafka",
    "connectionString": "localhost:9092",
    "batchSize": 100,
    "pollIntervalMs": 1000,
    "options": {
      "topic": "user-events",
      "groupId": "pipeline-1"
    }
  },
  "transformConfigs": [
    {
      "transformType": "filter",
      "config": {
        "expression": "age >= 18"
      }
    },
    {
      "transformType": "map",
      "config": {
        "user.name": "userName",
        "user.email": "email"
      }
    }
  ],
  "sinkConfig": {
    "sinkType": "elasticsearch",
    "connectionString": "localhost:9200",
    "batchSize": 50
  }
}
```

**Response:** `201 Created`
```json
{
  "pipelineId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "created",
  "message": "Pipeline 'My Pipeline' created successfully"
}
```

---

#### **List Pipelines**

Get a list of all pipelines.

- **URL:** `/api/v1/pipelines`
- **Method:** `GET`

**Response:** `200 OK`
```json
{
  "pipelines": [
    {
      "pipelineId": "550e8400-e29b-41d4-a716-446655440000",
      "name": "My Pipeline",
      "description": "Processes user events",
      "status": "running",
      "createdAt": "2025-01-15T10:00:00Z",
      "updatedAt": "2025-01-15T10:30:00Z"
    }
  ],
  "total": 1
}
```

---

#### **Get Pipeline**

Get detailed information about a specific pipeline.

- **URL:** `/api/v1/pipelines/{pipelineId}`
- **Method:** `GET`

**Response:** `200 OK`
```json
{
  "pipelineId": "550e8400-e29b-41d4-a716-446655440000",
  "name": "My Pipeline",
  "description": "Processes user events",
  "status": "running",
  "sourceConfig": { ... },
  "transformConfigs": [ ... ],
  "sinkConfig": { ... },
  "metrics": {
    "totalRecordsProcessed": 15000,
    "totalRecordsFailed": 5,
    "totalBatchesProcessed": 150,
    "averageProcessingTimeMs": 45.5,
    "lastProcessedAt": "2025-01-15T10:35:00Z",
    "throughputPerSecond": 330.5
  },
  "checkpoint": {
    "offset": 15000,
    "timestamp": "2025-01-15T10:35:00Z",
    "recordsProcessed": 15000
  },
  "createdAt": "2025-01-15T10:00:00Z",
  "updatedAt": "2025-01-15T10:35:00Z"
}
```

---

#### **Update Pipeline**

Update pipeline configuration (only when stopped).

- **URL:** `/api/v1/pipelines/{pipelineId}`
- **Method:** `PUT`
- **Content-Type:** `application/json`

**Request Body:**
```json
{
  "name": "Updated Pipeline Name",
  "description": "Updated description",
  "sinkConfig": {
    "sinkType": "file",
    "connectionString": "/data/output",
    "batchSize": 100
  }
}
```

**Response:** `200 OK`
```json
{
  "pipelineId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "updated",
  "message": "Pipeline configuration updated successfully",
  "timestamp": "2025-01-15T10:40:00Z"
}
```

---

### **Pipeline Lifecycle**

#### **Start Pipeline**

Start a configured or stopped pipeline.

- **URL:** `/api/v1/pipelines/{pipelineId}/start`
- **Method:** `POST`

**Response:** `200 OK`
```json
{
  "pipelineId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "started",
  "message": "Pipeline started successfully",
  "timestamp": "2025-01-15T10:45:00Z"
}
```

---

#### **Stop Pipeline**

Stop a running or paused pipeline.

- **URL:** `/api/v1/pipelines/{pipelineId}/stop`
- **Method:** `POST`
- **Content-Type:** `application/json`

**Request Body:**
```json
{
  "reason": "Maintenance window"
}
```

**Response:** `200 OK`
```json
{
  "pipelineId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "stopped",
  "message": "Pipeline stopped: Maintenance window",
  "timestamp": "2025-01-15T11:00:00Z"
}
```

---

#### **Pause Pipeline**

Temporarily pause a running pipeline.

- **URL:** `/api/v1/pipelines/{pipelineId}/pause`
- **Method:** `POST`
- **Content-Type:** `application/json`

**Request Body:**
```json
{
  "reason": "High system load"
}
```

**Response:** `200 OK`
```json
{
  "pipelineId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "paused",
  "message": "Pipeline paused: High system load",
  "timestamp": "2025-01-15T11:15:00Z"
}
```

---

#### **Resume Pipeline**

Resume a paused pipeline.

- **URL:** `/api/v1/pipelines/{pipelineId}/resume`
- **Method:** `POST`

**Response:** `200 OK`
```json
{
  "pipelineId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "resumed",
  "message": "Pipeline resumed successfully",
  "timestamp": "2025-01-15T11:20:00Z"
}
```

---

#### **Reset Pipeline**

Reset a failed pipeline to allow restart.

- **URL:** `/api/v1/pipelines/{pipelineId}/reset`
- **Method:** `POST`

**Response:** `200 OK`
```json
{
  "pipelineId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "reset",
  "message": "Pipeline reset successfully",
  "timestamp": "2025-01-15T11:25:00Z"
}
```

---

### **Monitoring**

#### **Get Metrics**

Get pipeline performance metrics.

- **URL:** `/api/v1/pipelines/{pipelineId}/metrics`
- **Method:** `GET`

**Response:** `200 OK`
```json
{
  "pipelineId": "550e8400-e29b-41d4-a716-446655440000",
  "metrics": {
    "totalRecordsProcessed": 25000,
    "totalRecordsFailed": 8,
    "totalBatchesProcessed": 250,
    "averageProcessingTimeMs": 42.3,
    "lastProcessedAt": "2025-01-15T11:30:00Z",
    "throughputPerSecond": 355.2
  },
  "timestamp": "2025-01-15T11:30:00Z"
}
```

---

#### **Health Check**

Check pipeline health status.

- **URL:** `/api/v1/pipelines/{pipelineId}/health`
- **Method:** `GET`

**Response:** `200 OK`
```json
{
  "pipelineId": "550e8400-e29b-41d4-a716-446655440000",
  "healthy": true,
  "status": "running",
  "lastCheck": "2025-01-15T11:35:00Z",
  "details": null
}
```

---

#### **System Health**

Check overall system health.

- **URL:** `/health`
- **Method:** `GET`

**Response:** `200 OK`
```json
{
  "status": "ok",
  "timestamp": "2025-01-15T11:40:00Z"
}
```

---

### **WebSocket**

#### **Pipeline Updates Stream**

Connect to WebSocket for real-time pipeline updates.

- **URL:** `ws://localhost:8080/api/v1/ws/pipelines/{pipelineId}`
- **Protocol:** WebSocket

**Message Format:**
```json
{
  "type": "pipeline_update",
  "pipelineId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "running",
  "metrics": {
    "totalRecordsProcessed": 25500,
    "totalRecordsFailed": 8,
    "totalBatchesProcessed": 255,
    "averageProcessingTimeMs": 42.1,
    "lastProcessedAt": "2025-01-15T11:42:00Z",
    "throughputPerSecond": 360.0
  },
  "timestamp": "2025-01-15T11:42:00Z"
}
```

**Error Message:**
```json
{
  "type": "error",
  "message": "Pipeline not found",
  "timestamp": "2025-01-15T11:42:00Z"
}
```

---

## 💡 **Examples**

### **Create and Start a Pipeline**

```bash
# 1. Create pipeline
curl -X POST http://localhost:8080/api/v1/pipelines \
  -H "Content-Type: application/json" \
  -d '{
    "name": "User Events Pipeline",
    "description": "Process user events",
    "sourceConfig": {
      "sourceType": "file",
      "connectionString": "/data/users.csv",
      "batchSize": 100
    },
    "transformConfigs": [],
    "sinkConfig": {
      "sinkType": "console",
      "connectionString": "",
      "batchSize": 10
    }
  }'

# Response: {"pipelineId": "abc-123", ...}

# 2. Start pipeline
curl -X POST http://localhost:8080/api/v1/pipelines/abc-123/start

# 3. Check metrics
curl http://localhost:8080/api/v1/pipelines/abc-123/metrics

# 4. Stop pipeline
curl -X POST http://localhost:8080/api/v1/pipelines/abc-123/stop \
  -H "Content-Type: application/json" \
  -d '{"reason": "Completed processing"}'
```

### **WebSocket Connection (JavaScript)**

```javascript
const ws = new WebSocket('ws://localhost:8080/api/v1/ws/pipelines/abc-123');

ws.onmessage = (event) => {
  const data = JSON.parse(event.data);

  if (data.type === 'pipeline_update') {
    console.log('Metrics:', data.metrics);
    console.log('Status:', data.status);
  } else if (data.type === 'error') {
    console.error('Error:', data.message);
  }
};

ws.onerror = (error) => {
  console.error('WebSocket error:', error);
};
```

---

## ⚠️ **Error Handling**

### **Error Codes**

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `not_found` | 404 | Pipeline not found |
| `create_failed` | 400 | Failed to create pipeline |
| `update_failed` | 400 | Failed to update pipeline |
| `start_failed` | 400 | Failed to start pipeline |
| `stop_failed` | 400 | Failed to stop pipeline |
| `pause_failed` | 400 | Failed to pause pipeline |
| `resume_failed` | 400 | Failed to resume pipeline |
| `reset_failed` | 400 | Failed to reset pipeline |
| `bad_request` | 400 | Invalid request format |
| `internal_error` | 500 | Internal server error |

### **Common Error Scenarios**

**Pipeline Not Found:**
```json
{
  "error": "not_found",
  "message": "Pipeline not found: invalid-id",
  "timestamp": "2025-01-15T12:00:00Z"
}
```

**Invalid State Transition:**
```json
{
  "error": "start_failed",
  "message": "Failed to start pipeline: Pipeline is already running",
  "timestamp": "2025-01-15T12:05:00Z"
}
```

**Validation Error:**
```json
{
  "error": "create_failed",
  "message": "Failed to create pipeline: Invalid source configuration",
  "details": "batchSize must be greater than 0",
  "timestamp": "2025-01-15T12:10:00Z"
}
```

---

## 📚 **Additional Resources**

- [Architecture Documentation](ARCHITECTURE_AND_ROADMAP.md)
- [Sprint Planning](SPRINT_PLANNING.md)
- [Core Module README](../dataflow-core/README.md)
- [Examples](../dataflow-examples/README.md)

---

## 🔄 **API Versioning**

Current version: **v1**

The API uses URL path versioning (`/api/v1/...`). Future versions will be released as `/api/v2/`, etc., with backward compatibility maintained for at least one major version.

---

## 🚀 **Rate Limiting**

Currently, no rate limiting is enforced (development mode).

In production, implement rate limiting:
- **100 requests per minute** per IP
- **1000 requests per hour** per API key

---

**Happy Pipeline Building! 🚀**

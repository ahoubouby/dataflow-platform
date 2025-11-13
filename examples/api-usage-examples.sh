#!/bin/bash

# ============================================
# DataFlow Platform API - Usage Examples
# ============================================
# Complete examples showing how to use the REST API

set -e

API_BASE_URL="http://localhost:8080"

echo "=== DataFlow Platform API Usage Examples ==="
echo ""

# ============================================
# 1. Health Check
# ============================================
echo "1. Checking system health..."
curl -s "$API_BASE_URL/health" | jq .
echo ""

# ============================================
# 2. Create a Pipeline
# ============================================
echo "2. Creating a new pipeline..."
PIPELINE_RESPONSE=$(curl -s -X POST "$API_BASE_URL/api/v1/pipelines" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "User Events Processing Pipeline",
    "description": "Processes user events from CSV file to console",
    "source": {
      "sourceType": "file",
      "connectionString": "/tmp/users.csv",
      "batchSize": 100,
      "pollIntervalMs": 1000,
      "options": {}
    },
    "transforms": [
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
    "sink": {
      "sinkType": "console",
      "connectionString": "",
      "batchSize": 10
    }
  }')

echo "$PIPELINE_RESPONSE" | jq .
PIPELINE_ID=$(echo "$PIPELINE_RESPONSE" | jq -r '.pipelineId')
echo ""
echo "Pipeline created with ID: $PIPELINE_ID"
echo ""

# ============================================
# 3. Get Pipeline Details
# ============================================
echo "3. Getting pipeline details..."
curl -s "$API_BASE_URL/api/v1/pipelines/$PIPELINE_ID" | jq .
echo ""

# ============================================
# 4. List All Pipelines
# ============================================
echo "4. Listing all pipelines..."
curl -s "$API_BASE_URL/api/v1/pipelines" | jq .
echo ""

# ============================================
# 5. Start the Pipeline
# ============================================
echo "5. Starting the pipeline..."
curl -s -X POST "$API_BASE_URL/api/v1/pipelines/$PIPELINE_ID/start" | jq .
echo ""

# ============================================
# 6. Get Pipeline Metrics
# ============================================
echo "6. Getting pipeline metrics..."
sleep 2  # Wait for some processing
curl -s "$API_BASE_URL/api/v1/pipelines/$PIPELINE_ID/metrics" | jq .
echo ""

# ============================================
# 7. Check Pipeline Health
# ============================================
echo "7. Checking pipeline health..."
curl -s "$API_BASE_URL/api/v1/pipelines/$PIPELINE_ID/health" | jq .
echo ""

# ============================================
# 8. Pause the Pipeline
# ============================================
echo "8. Pausing the pipeline..."
curl -s -X POST "$API_BASE_URL/api/v1/pipelines/$PIPELINE_ID/pause" \
  -H "Content-Type: application/json" \
  -d '{
    "reason": "Temporary maintenance"
  }' | jq .
echo ""

# ============================================
# 9. Resume the Pipeline
# ============================================
echo "9. Resuming the pipeline..."
sleep 2
curl -s -X POST "$API_BASE_URL/api/v1/pipelines/$PIPELINE_ID/resume" | jq .
echo ""

# ============================================
# 10. Update Pipeline Configuration
# ============================================
echo "10. Stopping pipeline for configuration update..."
curl -s -X POST "$API_BASE_URL/api/v1/pipelines/$PIPELINE_ID/stop" \
  -H "Content-Type: application/json" \
  -d '{
    "reason": "Configuration update"
  }' | jq .
echo ""

echo "11. Updating pipeline configuration..."
curl -s -X PUT "$API_BASE_URL/api/v1/pipelines/$PIPELINE_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "description": "Updated: Processes user events from CSV to console with enhanced filtering"
  }' | jq .
echo ""

# ============================================
# 12. WebSocket Example (requires websocat or similar)
# ============================================
echo "12. WebSocket streaming example:"
echo "   To connect to WebSocket for real-time updates, use:"
echo "   websocat ws://localhost:8080/api/v1/ws/pipelines/$PIPELINE_ID"
echo ""
echo "   Or in JavaScript:"
echo "   const ws = new WebSocket('ws://localhost:8080/api/v1/ws/pipelines/$PIPELINE_ID');"
echo "   ws.onmessage = (event) => console.log(JSON.parse(event.data));"
echo ""

# ============================================
# 13. Final Status Check
# ============================================
echo "13. Final pipeline status..."
curl -s "$API_BASE_URL/api/v1/pipelines/$PIPELINE_ID" | jq '.status, .metrics'
echo ""

echo "=== Examples Complete ==="
echo ""
echo "Pipeline ID for reference: $PIPELINE_ID"
echo ""
echo "Additional operations you can try:"
echo "  - POST /api/v1/pipelines/$PIPELINE_ID/start   # Start again"
echo "  - POST /api/v1/pipelines/$PIPELINE_ID/reset   # Reset if failed"
echo "  - GET /api/v1/pipelines/$PIPELINE_ID/metrics  # Get latest metrics"
echo ""

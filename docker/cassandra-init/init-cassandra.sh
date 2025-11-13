#!/bin/bash

# ============================================
# DataFlow Platform - Cassandra Initialization Script
# ============================================
# Initializes Cassandra keyspaces and tables for Pekko Persistence

set -e

CASSANDRA_HOST="${CASSANDRA_HOST:-localhost}"
CASSANDRA_PORT="${CASSANDRA_PORT:-9042}"
CASSANDRA_CONTAINER="${CASSANDRA_CONTAINER:-dataflow-cassandra}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== DataFlow Platform - Cassandra Initialization ==="
echo ""

# Check if we should use docker exec or cqlsh directly
USE_DOCKER=false
if docker ps | grep -q "$CASSANDRA_CONTAINER"; then
    echo "✓ Found Cassandra container: $CASSANDRA_CONTAINER"
    USE_DOCKER=true
else
    echo "⚠ Cassandra container not found, will try direct cqlsh connection to $CASSANDRA_HOST:$CASSANDRA_PORT"
fi

# Wait for Cassandra to be ready
echo ""
echo "Waiting for Cassandra to be ready..."
MAX_RETRIES=30
RETRY_COUNT=0

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    if [ "$USE_DOCKER" = true ]; then
        if docker exec "$CASSANDRA_CONTAINER" cqlsh -e "DESCRIBE KEYSPACES" > /dev/null 2>&1; then
            echo "✓ Cassandra is ready!"
            break
        fi
    else
        if cqlsh "$CASSANDRA_HOST" "$CASSANDRA_PORT" -e "DESCRIBE KEYSPACES" > /dev/null 2>&1; then
            echo "✓ Cassandra is ready!"
            break
        fi
    fi

    RETRY_COUNT=$((RETRY_COUNT + 1))
    echo "  Waiting... ($RETRY_COUNT/$MAX_RETRIES)"
    sleep 2
done

if [ $RETRY_COUNT -eq $MAX_RETRIES ]; then
    echo "✗ ERROR: Cassandra did not become ready in time"
    exit 1
fi

# Execute initialization script
echo ""
echo "Initializing Cassandra keyspaces and tables..."
echo ""

if [ "$USE_DOCKER" = true ]; then
    docker exec -i "$CASSANDRA_CONTAINER" cqlsh < "$SCRIPT_DIR/01-init-keyspaces.cql"
else
    cqlsh "$CASSANDRA_HOST" "$CASSANDRA_PORT" -f "$SCRIPT_DIR/01-init-keyspaces.cql"
fi

echo ""
echo "=== Initialization Complete ==="
echo ""
echo "Created keyspaces:"
echo "  - dataflow_journal (event store)"
echo "  - dataflow_snapshot (snapshots)"
echo ""
echo "You can now start the DataFlow Platform API."
echo ""

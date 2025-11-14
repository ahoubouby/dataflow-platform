#!/bin/bash
# Wait for Cassandra to be ready before starting the application

set -e

host="${CASSANDRA_HOST:-127.0.0.1}"
port="${CASSANDRA_PORT:-9042}"
max_attempts=60
attempt=0

echo "Waiting for Cassandra at $host:$port..."

until nc -z "$host" "$port" > /dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ $attempt -ge $max_attempts ]; then
    echo "ERROR: Cassandra did not become ready in time!"
    exit 1
  fi
  echo "Cassandra is unavailable - sleeping (attempt $attempt/$max_attempts)"
  sleep 2
done

echo "Cassandra is up - checking if it's ready to accept queries..."

# Wait for CQL to be available
until docker exec dataflow-cassandra cqlsh -e "describe keyspaces" > /dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ $attempt -ge $max_attempts ]; then
    echo "ERROR: Cassandra CQL not ready in time!"
    exit 1
  fi
  echo "Cassandra CQL not ready - sleeping (attempt $attempt/$max_attempts)"
  sleep 2
done

echo "✓ Cassandra is ready!"

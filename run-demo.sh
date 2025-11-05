#!/bin/bash
# DataFlow Platform Demo Runner
# This script runs the DataFlow Platform with demo mode enabled

echo "=========================================="
echo "DataFlow Platform - Demo Mode"
echo "=========================================="
echo ""
echo "Starting DataFlow Platform with demo enabled..."
echo "This will:"
echo "  1. Start the platform with all components"
echo "  2. Create a demo pipeline that reads from /tmp/dataflow-demo/sample-data.txt"
echo "  3. Process and display the data"
echo ""
echo "Prerequisites:"
echo "  - Cassandra running on localhost:9042"
echo "  - Java 11+ installed"
echo "  - SBT installed"
echo ""

# Check if Cassandra is running
if ! nc -z localhost 9042 2>/dev/null; then
    echo "WARNING: Cassandra does not appear to be running on localhost:9042"
    echo "Please start Cassandra first:"
    echo "  docker-compose -f docker/docker-compose.yml up cassandra -d"
    echo ""
    read -p "Continue anyway? (y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# Set environment variables for demo
export DATAFLOW_RUN_DEMO=true
export DATAFLOW_ENV=local
export PEKKO_LOG_LEVEL=INFO

# Run the application
sbt "dataflow-core/run"

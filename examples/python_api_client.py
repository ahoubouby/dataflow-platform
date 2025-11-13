#!/usr/bin/env python3
"""
DataFlow Platform API - Python Client Example

This example demonstrates how to interact with the DataFlow Platform API
using Python's requests library.

Requirements:
    pip install requests websocket-client
"""

import requests
import json
import time
import websocket
from typing import Dict, Any, Optional

class DataFlowClient:
    """Client for interacting with DataFlow Platform API."""

    def __init__(self, base_url: str = "http://localhost:8080"):
        """Initialize the client."""
        self.base_url = base_url
        self.api_base = f"{base_url}/api/v1"

    def health_check(self) -> Dict[str, Any]:
        """Check system health."""
        response = requests.get(f"{self.base_url}/health")
        response.raise_for_status()
        return response.json()

    def create_pipeline(
        self,
        name: str,
        description: str,
        source_config: Dict[str, Any],
        transform_configs: list,
        sink_config: Dict[str, Any]
    ) -> Dict[str, Any]:
        """Create a new pipeline."""
        payload = {
            "name": name,
            "description": description,
            "source": source_config,
            "transforms": transform_configs,
            "sink": sink_config
        }

        response = requests.post(
            f"{self.api_base}/pipelines",
            json=payload
        )
        response.raise_for_status()
        return response.json()

    def get_pipeline(self, pipeline_id: str) -> Dict[str, Any]:
        """Get pipeline details."""
        response = requests.get(f"{self.api_base}/pipelines/{pipeline_id}")
        response.raise_for_status()
        return response.json()

    def list_pipelines(self) -> Dict[str, Any]:
        """List all pipelines."""
        response = requests.get(f"{self.api_base}/pipelines")
        response.raise_for_status()
        return response.json()

    def update_pipeline(
        self,
        pipeline_id: str,
        **updates
    ) -> Dict[str, Any]:
        """Update pipeline configuration."""
        response = requests.put(
            f"{self.api_base}/pipelines/{pipeline_id}",
            json=updates
        )
        response.raise_for_status()
        return response.json()

    def start_pipeline(self, pipeline_id: str) -> Dict[str, Any]:
        """Start a pipeline."""
        response = requests.post(f"{self.api_base}/pipelines/{pipeline_id}/start")
        response.raise_for_status()
        return response.json()

    def stop_pipeline(self, pipeline_id: str, reason: str) -> Dict[str, Any]:
        """Stop a pipeline."""
        response = requests.post(
            f"{self.api_base}/pipelines/{pipeline_id}/stop",
            json={"reason": reason}
        )
        response.raise_for_status()
        return response.json()

    def pause_pipeline(self, pipeline_id: str, reason: str) -> Dict[str, Any]:
        """Pause a pipeline."""
        response = requests.post(
            f"{self.api_base}/pipelines/{pipeline_id}/pause",
            json={"reason": reason}
        )
        response.raise_for_status()
        return response.json()

    def resume_pipeline(self, pipeline_id: str) -> Dict[str, Any]:
        """Resume a paused pipeline."""
        response = requests.post(f"{self.api_base}/pipelines/{pipeline_id}/resume")
        response.raise_for_status()
        return response.json()

    def reset_pipeline(self, pipeline_id: str) -> Dict[str, Any]:
        """Reset a failed pipeline."""
        response = requests.post(f"{self.api_base}/pipelines/{pipeline_id}/reset")
        response.raise_for_status()
        return response.json()

    def get_metrics(self, pipeline_id: str) -> Dict[str, Any]:
        """Get pipeline metrics."""
        response = requests.get(f"{self.api_base}/pipelines/{pipeline_id}/metrics")
        response.raise_for_status()
        return response.json()

    def get_health(self, pipeline_id: str) -> Dict[str, Any]:
        """Get pipeline health status."""
        response = requests.get(f"{self.api_base}/pipelines/{pipeline_id}/health")
        response.raise_for_status()
        return response.json()

    def stream_updates(self, pipeline_id: str, on_message_callback):
        """
        Stream real-time pipeline updates via WebSocket.

        Args:
            pipeline_id: The pipeline ID to monitor
            on_message_callback: Function called with each update message
        """
        ws_url = f"ws://localhost:8080/api/v1/ws/pipelines/{pipeline_id}"

        def on_message(ws, message):
            data = json.loads(message)
            on_message_callback(data)

        def on_error(ws, error):
            print(f"WebSocket error: {error}")

        def on_close(ws, close_status_code, close_msg):
            print(f"WebSocket closed: {close_status_code} - {close_msg}")

        ws = websocket.WebSocketApp(
            ws_url,
            on_message=on_message,
            on_error=on_error,
            on_close=on_close
        )

        ws.run_forever()


def main():
    """Example usage of the DataFlow client."""
    client = DataFlowClient()

    print("=== DataFlow Platform API - Python Client Example ===\n")

    # 1. Health check
    print("1. Checking system health...")
    health = client.health_check()
    print(f"   Status: {health['status']}")
    print()

    # 2. Create a pipeline
    print("2. Creating a new pipeline...")
    pipeline_response = client.create_pipeline(
        name="Python Test Pipeline",
        description="Test pipeline created from Python client",
        source_config={
            "sourceType": "file",
            "connectionString": "/tmp/test-data.csv",
            "batchSize": 100,
            "pollIntervalMs": 1000,
            "options": {}
        },
        transform_configs=[
            {
                "transformType": "filter",
                "config": {"expression": "age >= 21"}
            },
            {
                "transformType": "map",
                "config": {
                    "name": "fullName",
                    "email": "emailAddress"
                }
            }
        ],
        sink_config={
            "sinkType": "console",
            "connectionString": "",
            "batchSize": 10
        }
    )

    pipeline_id = pipeline_response['pipelineId']
    print(f"   Pipeline created: {pipeline_id}")
    print(f"   Status: {pipeline_response['status']}")
    print()

    # 3. Get pipeline details
    print("3. Getting pipeline details...")
    details = client.get_pipeline(pipeline_id)
    print(f"   Name: {details['name']}")
    print(f"   Status: {details['status']}")
    print(f"   Description: {details['description']}")
    print()

    # 4. List all pipelines
    print("4. Listing all pipelines...")
    pipelines = client.list_pipelines()
    print(f"   Total pipelines: {pipelines['total']}")
    print()

    # 5. Start the pipeline
    print("5. Starting the pipeline...")
    start_response = client.start_pipeline(pipeline_id)
    print(f"   Status: {start_response['status']}")
    print(f"   Message: {start_response['message']}")
    print()

    # 6. Wait and get metrics
    print("6. Waiting 3 seconds and getting metrics...")
    time.sleep(3)
    try:
        metrics = client.get_metrics(pipeline_id)
        print(f"   Records processed: {metrics['metrics']['totalRecordsProcessed']}")
        print(f"   Batches processed: {metrics['metrics']['totalBatchesProcessed']}")
        print(f"   Throughput: {metrics['metrics']['throughputPerSecond']:.2f} records/sec")
    except requests.exceptions.HTTPError as e:
        print(f"   Metrics not yet available: {e}")
    print()

    # 7. Check health
    print("7. Checking pipeline health...")
    health = client.get_health(pipeline_id)
    print(f"   Healthy: {health['healthy']}")
    print(f"   Status: {health['status']}")
    print()

    # 8. Pause the pipeline
    print("8. Pausing the pipeline...")
    pause_response = client.pause_pipeline(pipeline_id, "Testing pause functionality")
    print(f"   Status: {pause_response['status']}")
    print()

    # 9. Resume the pipeline
    print("9. Resuming the pipeline...")
    time.sleep(2)
    resume_response = client.resume_pipeline(pipeline_id)
    print(f"   Status: {resume_response['status']}")
    print()

    # 10. Stop the pipeline
    print("10. Stopping the pipeline...")
    stop_response = client.stop_pipeline(pipeline_id, "Example complete")
    print(f"   Status: {stop_response['status']}")
    print()

    # 11. Update configuration
    print("11. Updating pipeline configuration...")
    update_response = client.update_pipeline(
        pipeline_id,
        description="Updated description from Python client"
    )
    print(f"   Status: {update_response['status']}")
    print()

    # 12. Final status
    print("12. Final pipeline status...")
    final_details = client.get_pipeline(pipeline_id)
    print(f"   Status: {final_details['status']}")
    print(f"   Description: {final_details['description']}")
    print()

    print("=== Example Complete ===")
    print(f"\nPipeline ID: {pipeline_id}")
    print("\nTo monitor real-time updates, you can use:")
    print(f"  client.stream_updates('{pipeline_id}', lambda msg: print(msg))")


if __name__ == "__main__":
    try:
        main()
    except requests.exceptions.ConnectionError:
        print("Error: Could not connect to DataFlow API at http://localhost:8080")
        print("Make sure the API server is running:")
        print("  sbt \"project dataflowApi\" run")
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()

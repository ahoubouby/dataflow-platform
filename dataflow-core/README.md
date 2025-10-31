
* ============================================
* PIPELINE AGGREGATE - Real Data Pipeline
* ============================================
*
* This is a PRODUCTION-GRADE aggregate for managing data pipelines.
* Unlike Counter (learning example), this demonstrates real-world complexity.
*
* WHAT IS A DATA PIPELINE?
* ------------------------
* A data pipeline is a series of data processing steps:
* 1. SOURCE: Ingest data (file, Kafka, API, database)
* 2. TRANSFORM: Process data (filter, map, aggregate)
* 3. SINK: Output data (file, Kafka, Cassandra, Elasticsearch)
*
* Example Pipeline:
* Kafka → Filter invalid records → Enrich with lookup → Cassandra
*
* WHY EVENT SOURCING FOR PIPELINES?
* ----------------------------------
* - Complete audit trail: Who changed what, when
* - Checkpoint management: Exactly-once semantics
* - Debugging: Replay pipeline execution
* - Metrics: Derive statistics from events
* - Recovery: Rebuild state after crashes
*
* COMPLEX FEATURES DEMONSTRATED:
* ------------------------------
* 1. State Machine: Uninitialized → Configured → Running → Paused → Stopped → Failed
* 2. Checkpoint Management: Track processed offsets
* 3. Metrics Tracking: Records processed, failures, latency
* 4. Batch Processing: Handle batches of records
* 5. Error Handling: Graceful degradation
* 6. Idempotency: Safe to retry operations
* 7. Business Rules: Complex validation
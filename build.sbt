// ============================================
// DataFlow Platform - Build Configuration
// ============================================
// Multi-module SBT project using Apache Pekko
// Apache Pekko is the Apache-licensed fork of Akka

// ============================================
// PROJECT METADATA
// ============================================
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.16"
ThisBuild / scalaBinaryVersion := "2.13"
ThisBuild / organization := "com.dataflow"

// ============================================
// DEPENDENCY VERSIONS
// ============================================
// Apache Pekko Versions
// Pekko 1.0.x = Akka 2.6.x API compatibility
// Pekko 1.1.x = Akka 2.8.x API compatibility (RECOMMENDED)
lazy val pekkoVersion                     = "1.1.3" // Core Pekko framework
lazy val pekkoHttpVersion                 = "1.1.0" // HTTP server/client
lazy val pekkoPersistenceCassandraVersion = "1.1.0" // Cassandra persistence plugin
lazy val pekkoConnectorsVersion           = "1.1.0" // Connectors (Alpakka equivalent)
lazy val pekkoConnectorsKafkaVersion      = "1.1.0" // Kafka connector
lazy val pekkoProjectionVersion           = "1.1.0" // Event projections/CQRS
lazy val pekkoManagementVersion           = "1.1.0" // Cluster management

// Third-party dependencies
lazy val logbackVersion        = "1.4.14" // Logging implementation
lazy val scalaTestVersion      = "3.2.17" // Testing framework
lazy val scalaMockVersion      = "5.2.0"  // Mocking framework
lazy val testContainersVersion = "0.41.0" // Docker containers for tests
lazy val kamonVersion          = "2.7.5"  // Metrics and tracing

// ============================================
// COMMON SETTINGS
// ============================================
lazy val commonSettings = Seq(
  // Scala compiler options for quality and warnings
  scalacOptions ++= Seq(
    "-deprecation",         // Emit warning for deprecated APIs
    "-feature",             // Emit warning for features that should be imported
    "-unchecked",           // Enable additional warnings
    "-Xlint",               // Enable recommended warnings
    "-Ywarn-dead-code",     // Warn when dead code is identified
    "-Ywarn-numeric-widen", // Warn when numerics are widened
    "-Ywarn-value-discard", // Warn when non-Unit values are discarded
    "-encoding",
    "UTF-8",                // Source file encoding
  ),

  // Test configuration
  Test / parallelExecution := false,           // Run tests sequentially (safer for integration tests)
  Test / fork := true,                         // Run tests in separate JVM
  Test / testOptions += Tests.Argument("-oD"), // Show test durations

  // Dependency resolution
  resolvers ++= Seq(
    "Apache Snapshots" at "https://repository.apache.org/content/repositories/snapshots/",
    "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
  ),

  // Javac options
  // javacOptions ++= Seq("-source", "11", "-target", "11")
)

// ============================================
// ROOT PROJECT
// ============================================
lazy val root = (project in file("."))
  .aggregate(
    dataflowCore,
    dataflowSources,
    dataflowSinks,
    dataflowTransforms,
    dataflowApi,
    dataflowProjections,
  )
  .settings(
    name := "dataflow-platform",
    publish / skip := true,
  )

// ============================================
// CORE MODULE
// ============================================
// Contains:
// - Event Sourced Aggregates (PipelineAggregate, CoordinatorAggregate)
// - Domain Models (Commands, Events, State)
// - Core Business Logic
// - Cluster Configuration
// - Serialization Setup
lazy val dataflowCore = (project in file("dataflow-core"))
  .settings(commonSettings)
  .settings(
    name := "dataflow-core",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-typed" % pekkoVersion,
      // Query API for reading events
      "org.apache.pekko" %% "pekko-persistence-query" % pekkoVersion,
      // Cassandra journal plugin (event store)
      "org.apache.pekko" %% "pekko-persistence-cassandra" % pekkoPersistenceCassandraVersion,
      "org.apache.pekko" %% "pekko-persistence-cassandra-launcher" % "1.1.0" % Test,
      "com.datastax.oss" % "java-driver-core" % "4.17.0", // Cassandra driver
      // PEKKO CLUSTER: Distributed actor system
      "org.apache.pekko" %% "pekko-cluster-typed" % pekkoVersion,
      // Cluster Sharding - distribute entities across nodes
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
      // Cluster tools (pub-sub, distributed data, singleton)
      "org.apache.pekko" %% "pekko-cluster-tools" % pekkoVersion,
      // ==========================================
      // PEKKO MANAGEMENT: Cluster bootstrap and health checks
      // ==========================================
      // Core management functionality
      "org.apache.pekko" %% "pekko-management" % pekkoManagementVersion,
      // Cluster HTTP management API
      "org.apache.pekko" %% "pekko-management-cluster-http" % pekkoManagementVersion,
      // Cluster Bootstrap for automatic cluster formation
      "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % pekkoManagementVersion,
      // Discovery mechanism (for K8s, DNS, or config-based discovery)
      "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
      // SERIALIZATION: Jackson for JSON/CBOR serialization
      "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
      // SLF4J bridge
      "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
      // Logback implementation
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      // Configuration library
      "com.typesafe" % "config" % "1.4.3",
      // Validation library
      "com.wix" %% "accord-core" % "0.7.6",
      // ==========================================
      // METRICS AND MONITORING
      // ==========================================
      // Kamon core - metrics and tracing
      "io.kamon" %% "kamon-core" % kamonVersion,
      // Prometheus reporter - export metrics to Prometheus
      "io.kamon" %% "kamon-prometheus" % kamonVersion,
      // System metrics - CPU, memory, GC
      "io.kamon" %% "kamon-system-metrics" % kamonVersion,
      // Pekko instrumentation - actor metrics
      "io.kamon" %% "kamon-akka" % kamonVersion,          // Note: Uses Akka naming but works with Pekko
      // Actor TestKit
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
      // Persistence TestKit (in-memory journal for tests)
      "org.apache.pekko" %% "pekko-persistence-testkit" % pekkoVersion % Test,
      // Stream TestKit
      "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVersion % Test,
      // Multi-node TestKit (for cluster tests)
      "org.apache.pekko" %% "pekko-multi-node-testkit" % pekkoVersion % Test,
      // ScalaTest
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      // ScalaMock
      "org.scalamock" %% "scalamock" % scalaMockVersion % Test,
    ),
  )

// ============================================
// SOURCES MODULE
// ============================================
// Data ingestion from various sources:
// - File sources (CSV, JSON, Parquet)
// - Kafka sources (Pekko Connectors Kafka)
// - API sources (REST, WebSocket)
// - Database sources (JDBC, CDC)
lazy val dataflowSources = (project in file("dataflow-sources"))
  .dependsOn(dataflowCore % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "dataflow-sources",
    libraryDependencies ++= Seq(
      // Pekko Streams
      "org.apache.pekko" %% "pekko-stream-typed" % pekkoVersion,
      // ==========================================
      // PEKKO CONNECTORS (Alpakka equivalent)
      // ==========================================
      // Kafka connector
      "org.apache.pekko" %% "pekko-connectors-kafka" % pekkoConnectorsKafkaVersion,
      // File connector
      "org.apache.pekko" %% "pekko-connectors-file" % pekkoConnectorsVersion,
      // CSV support
      "org.apache.pekko" %% "pekko-connectors-csv" % pekkoConnectorsVersion,
      // FTP/SFTP
      "org.apache.pekko" %% "pekko-connectors-ftp" % pekkoConnectorsVersion,
      // ==========================================
      // HTTP CLIENT
      // ==========================================
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      // ==========================================
      // DATABASE
      // ==========================================
      // JDBC
      "org.apache.pekko" %% "pekko-connectors-slick" % pekkoConnectorsVersion,
      // PostgreSQL driver
      "org.postgresql" % "postgresql" % "42.7.8",
      // ==========================================
      // DATA FORMATS
      // ==========================================
      // JSON parsing
      "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
      // CSV parsing
      "com.github.tototoshi" %% "scala-csv" % "2.0.0",
      // ==========================================
      // TESTING
      // ==========================================
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-connectors-kafka-testkit" % pekkoConnectorsKafkaVersion % Test,
      // TestContainers for integration tests
      "com.dimafeng" %% "testcontainers-scala-scalatest" % testContainersVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-kafka" % testContainersVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-postgresql" % testContainersVersion % Test,
    ),
    coverageExcludedFiles := ".*TestSource.scala",
  )

// ============================================
// TRANSFORMS MODULE
// ============================================
// Data transformation and processing:
// - Filter, Map, FlatMap
// - Aggregate, Window
// - Join, Lookup
// - Schema validation
lazy val dataflowTransforms = (project in file("dataflow-transforms"))
  .dependsOn(dataflowCore % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "dataflow-transforms",
    libraryDependencies ++= Seq(
      // Pekko Streams
      "org.apache.pekko" %% "pekko-stream-typed" % pekkoVersion,
      // ==========================================
      // DATA PROCESSING
      // ==========================================
      // JSON processing
      "io.circe" %% "circe-core" % "0.14.15",
      "io.circe" %% "circe-generic" % "0.14.15",
      "io.circe" %% "circe-parser" % "0.14.15",
      // ==========================================
      // SCHEMA VALIDATION
      // ==========================================
      // JSON Schema validation
      "com.github.java-json-tools" % "json-schema-validator" % "2.2.14",
      // ==========================================
      // TESTING
      // ==========================================
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVersion % Test,
    ),
  )

// ============================================
// SINKS MODULE
// ============================================
// Data output to various destinations:
// - File sinks (CSV, JSON, Parquet)
// - Kafka sinks (Pekko Connectors Kafka)
// - Database sinks (Cassandra, PostgreSQL, JDBC)
// - Search sinks (Elasticsearch)
// - Cloud sinks (S3, GCS)
lazy val dataflowSinks = (project in file("dataflow-sinks"))
  .dependsOn(dataflowCore % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "dataflow-sinks",
    libraryDependencies ++= Seq(
      // Pekko Streams
      "org.apache.pekko" %% "pekko-stream-typed" % pekkoVersion,

      // ==========================================
      // PEKKO CONNECTORS
      // ==========================================
      // Kafka
      "org.apache.pekko" %% "pekko-connectors-kafka" % pekkoConnectorsKafkaVersion,

      // Cassandra
      "org.apache.pekko" %% "pekko-connectors-cassandra" % pekkoConnectorsVersion,

      // Elasticsearch
      "org.apache.pekko" %% "pekko-connectors-elasticsearch" % pekkoConnectorsVersion,

      // S3
      "org.apache.pekko" %% "pekko-connectors-s3" % pekkoConnectorsVersion,

      // File
      "org.apache.pekko" %% "pekko-connectors-file" % pekkoConnectorsVersion,

      // JDBC
      "org.apache.pekko" %% "pekko-connectors-slick" % pekkoConnectorsVersion,

      // ==========================================
      // DATABASE DRIVERS
      // ==========================================
      "org.postgresql" % "postgresql" % "42.7.8",

      // ==========================================
      // DATA FORMATS
      // ==========================================
      "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,

      // ==========================================
      // TESTING
      // ==========================================
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-connectors-kafka-testkit" % pekkoConnectorsKafkaVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-scalatest" % testContainersVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-kafka" % testContainersVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-cassandra" % testContainersVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-postgresql" % testContainersVersion % Test,
    ),
  )

// ============================================
// API MODULE
// ============================================
// HTTP API for pipeline management:
// - REST API (CRUD operations)
// - WebSocket (real-time updates)
// - API documentation
// - Request validation
lazy val dataflowApi = (project in file("dataflow-api"))
  .dependsOn(dataflowCore % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "dataflow-api",
    libraryDependencies ++= Seq(
      // ==========================================
      // PEKKO HTTP
      // ==========================================
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,

      // ==========================================
      // CORS SUPPORT
      // ==========================================
      "ch.megard" %% "akka-http-cors" % "1.2.0", // Note: No Pekko version yet, uses Akka

      // ==========================================
      // API DOCUMENTATION
      // ==========================================
      // OpenAPI / Swagger
      "com.github.swagger-akka-http" %% "swagger-akka-http" % "2.11.0" exclude ("com.typesafe.akka", "akka-stream_2.13"),

      // ==========================================
      // VALIDATION
      // ==========================================
      "com.wix" %% "accord-core" % "0.7.6",

      // ==========================================
      // TESTING
      // ==========================================
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
    ),
  )

// ============================================
// PROJECTIONS MODULE
// ============================================
// CQRS read models and projections:
// - Pipeline status view
// - Metrics aggregation
// - Audit log
// - Search index
lazy val dataflowProjections = (project in file("dataflow-projections"))
  .dependsOn(dataflowCore % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "dataflow-projections",
    libraryDependencies ++= Seq(
      // ==========================================
      // PEKKO PROJECTIONS
      // ==========================================
      // Core projections
      "org.apache.pekko" %% "pekko-projection-core" % pekkoProjectionVersion,

      // Event Sourced projections
      "org.apache.pekko" %% "pekko-projection-eventsourced" % pekkoProjectionVersion,

      // Cassandra offset store
      "org.apache.pekko" %% "pekko-projection-cassandra" % pekkoProjectionVersion,

      // Kafka projection (optional)
      "org.apache.pekko" %% "pekko-projection-kafka" % pekkoProjectionVersion,

      // JDBC projection (optional)
      "org.apache.pekko" %% "pekko-projection-jdbc" % pekkoProjectionVersion,

      // ==========================================
      // READ MODEL STORAGE
      // ==========================================
      // Cassandra (primary read store)
      "org.apache.pekko" %% "pekko-connectors-cassandra" % pekkoConnectorsVersion,

      // PostgreSQL (optional - for complex queries)
      "org.apache.pekko" %% "pekko-connectors-slick" % pekkoConnectorsVersion,
      "org.postgresql" % "postgresql" % "42.7.8",

      // ==========================================
      // TESTING
      // ==========================================
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      "org.apache.pekko" %% "pekko-projection-testkit" % pekkoProjectionVersion % Test,
      "org.apache.pekko" %% "pekko-persistence-testkit" % pekkoVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-scalatest" % testContainersVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-cassandra" % testContainersVersion % Test,
    ),
  )

ThisBuild / dependencyOverrides ++= Seq(
  "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
  "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream-typed" % pekkoVersion,
  "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
  "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
  "org.apache.pekko" %% "pekko-cluster" % pekkoVersion,
  "org.apache.pekko" %% "pekko-cluster-typed" % pekkoVersion,
  "org.apache.pekko" %% "pekko-cluster-sharding" % pekkoVersion,
  "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
  "org.apache.pekko" %% "pekko-distributed-data" % pekkoVersion,
  "org.apache.pekko" %% "pekko-persistence" % pekkoVersion,
  "org.apache.pekko" %% "pekko-persistence-typed" % pekkoVersion,
  "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVersion,
  "org.apache.pekko" %% "pekko-persistence-testkit" % pekkoVersion,
  "org.apache.pekko" %% "pekko-multi-node-testkit" % pekkoVersion,
  "org.apache.pekko" %% "pekko-testkit" % pekkoVersion,
  "org.apache.pekko" %% "pekko-coordination" % pekkoVersion,
  "org.apache.pekko" %% "pekko-cluster-tools" % pekkoVersion,
  "org.apache.pekko" %% "pekko-protobuf-v3" % pekkoVersion,
  "org.apache.pekko" %% "pekko-pki" % pekkoVersion,
  "org.apache.pekko" %% "pekko-remote" % pekkoVersion,
)

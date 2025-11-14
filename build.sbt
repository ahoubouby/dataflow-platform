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
lazy val pekkoVersion                     = "1.1.3"  // Core Pekko framework
lazy val pekkoHttpVersion                 = "1.1.0"  // HTTP server/client
lazy val pekkoPersistenceCassandraVersion = "1.1.0"  // Cassandra persistence plugin
lazy val pekkoConnectorsVersion           = "1.1.0"  // Connectors (Alpakka equivalent)
lazy val pekkoConnectorsKafkaVersion      = "1.1.0"  // Kafka connector
lazy val pekkoProjectionVersion           = "1.1.0"  // Event projections/CQRS
lazy val pekkoManagementVersion           = "1.1.0"  // Cluster management

// Third-party dependencies
lazy val catsVersion           = "2.10.0"  // Functional programming library
lazy val logbackVersion        = "1.4.14"  // Logging implementation
lazy val scalaTestVersion      = "3.2.17"  // Testing framework
lazy val scalaMockVersion      = "5.2.0"   // Mocking framework
lazy val testContainersVersion = "0.41.0"  // Docker containers for tests
lazy val kamonVersion          = "2.7.5"   // Metrics and tracing
lazy val circeVersion          = "0.14.15" // JSON library
lazy val accordVersion         = "0.7.6"   // Validation library

// ============================================
// COMMON DEPENDENCIES
// ============================================
lazy val commonDependencies = Seq(
  // Core Pekko
  "org.apache.pekko" %% "pekko-actor-typed"   % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream-typed"  % pekkoVersion,
  "org.apache.pekko" %% "pekko-slf4j"         % pekkoVersion,

  // Cats - Functional Programming
  "org.typelevel"    %% "cats-core"           % catsVersion,
  "org.typelevel"    %% "cats-effect"         % "3.5.2",

  // Logging
  "ch.qos.logback"    % "logback-classic"     % logbackVersion,

  // Configuration
  "com.typesafe"      % "config"              % "1.4.3",
)

lazy val testDependencies = Seq(
  // Pekko Testing
  "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion     % Test,
  "org.apache.pekko" %% "pekko-stream-testkit"      % pekkoVersion     % Test,

  // ScalaTest & Mocking
  "org.scalatest"    %% "scalatest"                 % scalaTestVersion % Test,
  "org.scalamock"    %% "scalamock"                 % scalaMockVersion % Test,
)

lazy val clusterDependencies = Seq(
  // Pekko Cluster
  "org.apache.pekko" %% "pekko-cluster-typed"          % pekkoVersion,
  "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
  "org.apache.pekko" %% "pekko-cluster-tools"          % pekkoVersion,

  // Cluster Management
  "org.apache.pekko" %% "pekko-management"                      % pekkoManagementVersion,
  "org.apache.pekko" %% "pekko-management-cluster-http"         % pekkoManagementVersion,
  "org.apache.pekko" %% "pekko-management-cluster-bootstrap"    % pekkoManagementVersion,
  "org.apache.pekko" %% "pekko-discovery"                       % pekkoVersion,
)

lazy val persistenceDependencies = Seq(
  // Pekko Persistence
  "org.apache.pekko" %% "pekko-persistence-typed"      % pekkoVersion,
  "org.apache.pekko" %% "pekko-persistence-query"      % pekkoVersion,

  // Cassandra Plugin
  "org.apache.pekko" %% "pekko-persistence-cassandra"  % pekkoPersistenceCassandraVersion,
  "com.datastax.oss"  % "java-driver-core"             % "4.17.0",

  // Serialization
  "org.apache.pekko" %% "pekko-serialization-jackson"  % pekkoVersion,

  // Testing
  "org.apache.pekko" %% "pekko-persistence-testkit"           % pekkoVersion                     % Test,
  "org.apache.pekko" %% "pekko-persistence-cassandra-launcher" % pekkoPersistenceCassandraVersion % Test,
  "org.apache.pekko" %% "pekko-multi-node-testkit"            % pekkoVersion                     % Test,
)

lazy val metricsDependencies = Seq(
  // Kamon - Metrics and Tracing
  "io.kamon" %% "kamon-core"           % kamonVersion,
  "io.kamon" %% "kamon-prometheus"     % kamonVersion,
  "io.kamon" %% "kamon-system-metrics" % kamonVersion,
  "io.kamon" %% "kamon-akka"           % kamonVersion,  // Works with Pekko
)

lazy val httpDependencies = Seq(
  // Pekko HTTP
  "org.apache.pekko" %% "pekko-http"            % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,

  // Testing
  "org.apache.pekko" %% "pekko-http-testkit"    % pekkoHttpVersion % Test,
)

lazy val kafkaDependencies = Seq(
  // Kafka Connector
  "org.apache.pekko" %% "pekko-connectors-kafka"         % pekkoConnectorsKafkaVersion,
  "org.apache.pekko" %% "pekko-connectors-kafka-testkit" % pekkoConnectorsKafkaVersion % Test,

  // TestContainers
  "com.dimafeng"     %% "testcontainers-scala-kafka"     % testContainersVersion       % Test,
)

lazy val databaseDependencies = Seq(
  // JDBC
  "org.apache.pekko" %% "pekko-connectors-slick" % pekkoConnectorsVersion,
  "org.postgresql"    % "postgresql"             % "42.7.8",

  // Cassandra
  "org.apache.pekko" %% "pekko-connectors-cassandra" % pekkoConnectorsVersion,

  // TestContainers
  "com.dimafeng"     %% "testcontainers-scala-postgresql" % testContainersVersion % Test,
  "com.dimafeng"     %% "testcontainers-scala-cassandra"  % testContainersVersion % Test,
)

lazy val jsonDependencies = Seq(
  // Circe - JSON Processing
  "io.circe" %% "circe-core"    % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser"  % circeVersion,
  "io.circe" %% "circe-optics"  % "0.15.1",
)

lazy val validationDependencies = Seq(
  // Validation
  "com.wix" %% "accord-core" % accordVersion,
)

// ============================================
// COMMON SETTINGS
// ============================================
lazy val commonSettings = Seq(
  // Scala compiler options
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xlint",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard",
    "-encoding",
    "UTF-8",
  ),

  // Test configuration
  Test / parallelExecution := false,
  Test / fork := true,
  Test / testOptions += Tests.Argument("-oD"),

  // Dependency resolution
  resolvers ++= Seq(
    "Apache Snapshots" at "https://repository.apache.org/content/repositories/snapshots/",
    "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
  ),
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
    dataflowExamples,
  )
  .settings(
    name := "dataflow-platform",
    publish / skip := true,
  )

// ============================================
// CORE MODULE
// ============================================
lazy val dataflowCore = (project in file("dataflow-core"))
  .settings(commonSettings)
  .settings(
    name := "dataflow-core",
    libraryDependencies ++=
      commonDependencies ++
        testDependencies ++
        // Core only needs persistence API, not cluster or Cassandra client
        Seq(
          "org.apache.pekko" %% "pekko-persistence-typed"     % pekkoVersion,
          "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
          // Testing
          "org.apache.pekko" %% "pekko-persistence-testkit" % pekkoVersion % Test,
        ) ++
        validationDependencies,
  )

// ============================================
// SOURCES MODULE
// ============================================
lazy val dataflowSources = (project in file("dataflow-sources"))
  .dependsOn(dataflowCore % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "dataflow-sources",
    libraryDependencies ++=
      commonDependencies ++
        testDependencies ++
        httpDependencies ++
        kafkaDependencies ++
        databaseDependencies ++
        metricsDependencies ++  // ← Kamon for SourceMetricsReporter
        Seq(
          // File Connectors
          "org.apache.pekko"     %% "pekko-connectors-file" % pekkoConnectorsVersion,
          "org.apache.pekko"     %% "pekko-connectors-csv"  % pekkoConnectorsVersion,
          "org.apache.pekko"     %% "pekko-connectors-ftp"  % pekkoConnectorsVersion,

          // CSV Parsing
          "com.github.tototoshi" %% "scala-csv"             % "2.0.0",

          // TestContainers
          "com.dimafeng"         %% "testcontainers-scala-scalatest" % testContainersVersion % Test,
        ),
    coverageExcludedFiles := ".*TestSource.scala",
  )

// ============================================
// TRANSFORMS MODULE
// ============================================
lazy val dataflowTransforms = (project in file("dataflow-transforms"))
  .dependsOn(dataflowCore % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "dataflow-transforms",
    libraryDependencies ++=
      commonDependencies ++
        testDependencies ++
        jsonDependencies ++
        validationDependencies ++
        metricsDependencies ++  // ← Kamon for TransformMetricsReporter
        Seq(
          // JSON Schema Validation
          "com.github.java-json-tools" % "json-schema-validator" % "2.2.14",
        ),
  )

// ============================================
// SINKS MODULE
// ============================================
lazy val dataflowSinks = (project in file("dataflow-sinks"))
  .dependsOn(dataflowCore % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "dataflow-sinks",
    libraryDependencies ++=
      commonDependencies ++
        testDependencies ++
        httpDependencies ++
        kafkaDependencies ++
        jsonDependencies ++
        databaseDependencies ++
        metricsDependencies ++  // ← Kamon for SinkMetricsReporter
        Seq(
          // Additional Connectors
          "org.apache.pekko" %% "pekko-connectors-elasticsearch" % pekkoConnectorsVersion,
          "org.apache.pekko" %% "pekko-connectors-s3"            % pekkoConnectorsVersion,
          "org.apache.pekko" %% "pekko-connectors-file"          % pekkoConnectorsVersion,

          // TestContainers
          "com.dimafeng"     %% "testcontainers-scala-scalatest" % testContainersVersion % Test,
        ),
  )

// ============================================
// API MODULE
// ============================================
lazy val dataflowApi = (project in file("dataflow-api"))
  .dependsOn(
    dataflowCore % "compile->compile;test->test",
    dataflowSources % "compile->compile",
    dataflowTransforms % "compile->compile",
    dataflowSinks % "compile->compile"
  )
  .settings(commonSettings)
  .settings(
    name := "dataflow-api",
    libraryDependencies ++=
      commonDependencies ++
        testDependencies ++
        httpDependencies ++
        validationDependencies ++
        metricsDependencies ++
        clusterDependencies ++      // API module runs the cluster
        persistenceDependencies ++  // API module connects to Cassandra
        Seq(
          // CORS Support (still uses Akka naming)
          "ch.megard" %% "akka-http-cors" % "1.2.0",

          // OpenAPI / Swagger
          "com.github.swagger-akka-http" %% "swagger-akka-http" % "2.11.0" exclude ("com.typesafe.akka", "akka-stream_2.13"),
        ),
  )

// ============================================
// PROJECTIONS MODULE
// ============================================
lazy val dataflowProjections = (project in file("dataflow-projections"))
  .dependsOn(dataflowCore % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "dataflow-projections",
    libraryDependencies ++=
      commonDependencies ++
        testDependencies ++
        databaseDependencies ++
        Seq(
          // Pekko Projections
          "org.apache.pekko" %% "pekko-projection-core"          % pekkoProjectionVersion,
          "org.apache.pekko" %% "pekko-projection-eventsourced"  % pekkoProjectionVersion,
          "org.apache.pekko" %% "pekko-projection-cassandra"     % pekkoProjectionVersion,
          "org.apache.pekko" %% "pekko-projection-kafka"         % pekkoProjectionVersion,
          "org.apache.pekko" %% "pekko-projection-jdbc"          % pekkoProjectionVersion,

          // Testing
          "org.apache.pekko" %% "pekko-projection-testkit"       % pekkoProjectionVersion % Test,
          "com.dimafeng"     %% "testcontainers-scala-scalatest" % testContainersVersion  % Test,
        ),
  )

// ============================================
// EXAMPLES MODULE
// ============================================
lazy val dataflowExamples = (project in file("dataflow-examples"))
  .dependsOn(
    dataflowCore % "compile->compile",
    dataflowSources % "compile->compile",
    dataflowSinks % "compile->compile",
    dataflowTransforms % "compile->compile",
  )
  .settings(commonSettings)
  .settings(
    name := "dataflow-examples",
    libraryDependencies ++=
      commonDependencies ++
        jsonDependencies,
    publish / skip := true,
  )

// ============================================
// DEPENDENCY OVERRIDES
// ============================================
ThisBuild / dependencyOverrides ++= Seq(
  "org.apache.pekko" %% "pekko-actor"                     % pekkoVersion,
  "org.apache.pekko" %% "pekko-actor-typed"               % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream"                    % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream-typed"              % pekkoVersion,
  "org.apache.pekko" %% "pekko-slf4j"                     % pekkoVersion,
  "org.apache.pekko" %% "pekko-serialization-jackson"     % pekkoVersion,
  "org.apache.pekko" %% "pekko-cluster"                   % pekkoVersion,
  "org.apache.pekko" %% "pekko-cluster-typed"             % pekkoVersion,
  "org.apache.pekko" %% "pekko-cluster-sharding"          % pekkoVersion,
  "org.apache.pekko" %% "pekko-cluster-sharding-typed"    % pekkoVersion,
  "org.apache.pekko" %% "pekko-distributed-data"          % pekkoVersion,
  "org.apache.pekko" %% "pekko-persistence"               % pekkoVersion,
  "org.apache.pekko" %% "pekko-persistence-typed"         % pekkoVersion,
  "org.apache.pekko" %% "pekko-actor-testkit-typed"       % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream-testkit"            % pekkoVersion,
  "org.apache.pekko" %% "pekko-persistence-testkit"       % pekkoVersion,
  "org.apache.pekko" %% "pekko-multi-node-testkit"        % pekkoVersion,
  "org.apache.pekko" %% "pekko-testkit"                   % pekkoVersion,
  "org.apache.pekko" %% "pekko-coordination"              % pekkoVersion,
  "org.apache.pekko" %% "pekko-cluster-tools"             % pekkoVersion,
  "org.apache.pekko" %% "pekko-protobuf-v3"               % pekkoVersion,
  "org.apache.pekko" %% "pekko-pki"                       % pekkoVersion,
  "org.apache.pekko" %% "pekko-remote"                    % pekkoVersion,
)

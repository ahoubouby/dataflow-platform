// ============================================
// DataFlow Platform - SBT Plugins
// ============================================
// Essential plugins for professional Scala development

// ============================================
// ASSEMBLY PLUGIN
// ============================================
// Creates "fat JARs" (uber JARs) with all dependencies
// Usage: sbt assembly
// Useful for: Deploying standalone applications
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.1.5")

// ============================================
// NATIVE PACKAGER
// ============================================
// Creates deployable packages: Docker images, RPMs, DEBs, etc.
// Usage: sbt docker:publishLocal
// Useful for: Containerization and deployment
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.16")

// ============================================
// CODE FORMATTING
// ============================================
// Scalafmt - Automatic code formatting
// Usage: sbt scalafmt (format code)
//        sbt scalafmtCheck (check if formatted)
// Config: Create .scalafmt.conf in project root
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

// ============================================
// CODE QUALITY
// ============================================
// WartRemover - Linting for Scala (catches common errors)
// Usage: Runs automatically during compile
// Useful for: Preventing common mistakes
// addSbtPlugin("org.wartremover" % "sbt-wartremover" % "3.1.5")

// Scalafix - Refactoring and linting tool
// Usage: sbt scalafix
// Useful for: Code migrations and automated refactoring
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.11.1")

// ============================================
// CODE COVERAGE
// ============================================
// Scoverage - Code coverage measurement
// Usage: sbt clean coverage test coverageReport
// Useful for: Measuring test coverage
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.0")

// ============================================
// DEPENDENCY MANAGEMENT
// ============================================
// Dependency graph visualization
// Usage: sbt dependencyTree
// Useful for: Understanding dependency conflicts
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.10.0-RC1")

// Check for dependency updates
// Usage: sbt dependencyUpdates
// Useful for: Keeping dependencies current
addSbtPlugin("org.jmotor.sbt" % "sbt-dependency-updates" % "1.2.7")

// ============================================
// DOCUMENTATION
// ============================================
// API documentation site generator
// Usage: sbt doc
addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "1.4.1")

// Unified documentation (combines multiple docs)
// Usage: sbt unidoc
addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.5.0")

// ============================================
// RELEASE MANAGEMENT
// ============================================
// Automated release process
// Usage: sbt release
// Useful for: Version bumping, tagging, publishing
addSbtPlugin("com.github.sbt" % "sbt-release" % "1.4.0")

// Git integration
// Usage: Automatic (adds git info to builds)
addSbtPlugin("com.github.sbt" % "sbt-git" % "2.0.1")

// ============================================
// BUILD INFO
// ============================================
// Generate BuildInfo object with build metadata
// Usage: Automatic (creates BuildInfo.scala)
// Useful for: Including version/date in app
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")

// ============================================
// MULTI-NODE TESTING (for Pekko Cluster)
// ============================================
// Multi-JVM testing for distributed systems
// Usage: sbt multi-jvm:test
// Useful for: Testing cluster behavior
addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.4.0")

// ============================================
// PERFORMANCE
// ============================================
// JMH benchmarking plugin
// Usage: sbt jmh:run
// Useful for: Performance benchmarking
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.6")

// ============================================
// CONTINUOUS INTEGRATION
// ============================================
// GitHub Actions integration
// Usage: Automatic (configures CI)
addSbtPlugin("com.codecommit" % "sbt-github-actions" % "0.14.2")

// ============================================
// DOCKER INTEGRATION
// ============================================
// Note: Already included in sbt-native-packager
// Additional Docker settings can be configured in build.sbt:
//
// Docker / packageName := "dataflow-platform"
// Docker / version := version.value
// Docker / maintainer := "your-email@example.com"
// dockerBaseImage := "eclipse-temurin:11-jre"
// dockerExposedPorts := Seq(8080, 2551)
// dockerUpdateLatest := true

// ============================================
// EXAMPLE USAGE
// ============================================
//
// sbt assembly                    # Create fat JAR
// sbt docker:publishLocal         # Build Docker image
// sbt scalafmt                    # Format code
// sbt coverage test coverageReport  # Run tests with coverage
// sbt dependencyUpdates           # Check for dependency updates
// sbt release                     # Execute release process
// sbt multi-jvm:test             # Run cluster tests
// sbt jmh:run                     # Run benchmarks
//

// ============================================
// PLUGIN CONFIGURATION NOTES
// ============================================
//
// 1. Assembly Plugin:
//    - Configure merge strategy in build.sbt
//    - Example: assembly / assemblyMergeStrategy
//
// 2. Native Packager:
//    - Enable specific packager: enablePlugins(JavaAppPackaging, DockerPlugin)
//    - Configure Docker settings in build.sbt
//
// 3. Scalafmt:
//    - Create .scalafmt.conf in project root
//    - Example config: version = "3.7.17"
//
// 4. Scoverage:
//    - Configure coverage settings in build.sbt
//    - Example: coverageMinimumStmtTotal := 80
//
// 5. Multi-JVM:
//    - Configure in build.sbt: enablePlugins(MultiJvmPlugin)
//    - Create multi-jvm test sources
//
// 6. BuildInfo:
//    - Configure in build.sbt: enablePlugins(BuildInfoPlugin)
//    - Example: buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion)

// ============================================
// RECOMMENDED MINIMAL SETUP
// ============================================
// For a minimal professional setup, enable these:
// - sbt-assembly (deployment)
// - sbt-native-packager (Docker)
// - sbt-scalafmt (code formatting)
// - sbt-scoverage (test coverage)
// - sbt-dependency-updates (keep deps current)

// ============================================
// ADVANCED FEATURES
// ============================================
// For advanced workflows, consider adding:
// - sbt-wartremover (strict linting)
// - sbt-scalafix (automated refactoring)
// - sbt-jmh (performance benchmarking)
// - sbt-multi-jvm (cluster testing)
// - sbt-release (automated releases)
dependencyOverrides += "org.scala-lang.modules" %% "scala-xml" % "2.4.0"

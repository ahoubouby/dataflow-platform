package com.dataflow.examples

import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.filters.FilterTransform
import com.dataflow.transforms.mapping.{MapTransform, FlatMapTransform}
import com.dataflow.transforms.domain._
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.{Source, Sink}

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._

/**
 * Practical examples of using transforms in the DataFlow Platform.
 *
 * These examples demonstrate common use cases and integration patterns.
 */
object TransformExamples {

  implicit val system: ActorSystem[_] = ???
  implicit val ec: ExecutionContext = system.executionContext

  // ============================================
  // EXAMPLE 1: USER DATA PROCESSING
  // ============================================

  /**
   * Process user registration data:
   * - Filter adults only (age >= 18)
   * - Normalize field names
   * - Split interests into separate records
   */
  def userProcessingPipeline(): Future[Seq[DataRecord]] = {
    // Sample user data
    val users = Seq(
      DataRecord(
        id = "1",
        data = Map(
          "firstName" -> "John",
          "lastName" -> "Doe",
          "age" -> "25",
          "interests" -> "music,sports,reading"
        )
      ),
      DataRecord(
        id = "2",
        data = Map(
          "firstName" -> "Jane",
          "lastName" -> "Smith",
          "age" -> "17",
          "interests" -> "art,dance"
        )
      ),
      DataRecord(
        id = "3",
        data = Map(
          "firstName" -> "Bob",
          "lastName" -> "Wilson",
          "age" -> "30",
          "interests" -> "coding,gaming"
        )
      )
    )

    // Create transforms
    val filterAdults = new FilterTransform(
      FilterConfig("$.age >= 18")
    )

    val normalizeFields = new MapTransform(
      MapConfig(
        mappings = Map(
          "firstName" -> "first_name",
          "lastName" -> "last_name"
        ),
        preserveUnmapped = true
      )
    )

    val splitInterests = new FlatMapTransform(
      FlatMapConfig(
        splitField = "interests",
        targetField = Some("interest"),
        preserveParent = true
      )
    )

    // Execute pipeline
    Source(users)
      .via(filterAdults.flow)        // Only John and Bob pass
      .via(normalizeFields.flow)      // Rename firstName/lastName
      .via(splitInterests.flow)       // Split interests
      .runWith(Sink.seq)

    // Expected output:
    // [
    //   {id: "1-1", first_name: "John", age: "25", interest: "music", ...},
    //   {id: "1-2", first_name: "John", age: "25", interest: "sports", ...},
    //   {id: "1-3", first_name: "John", age: "25", interest: "reading", ...},
    //   {id: "3-1", first_name: "Bob", age: "30", interest: "coding", ...},
    //   {id: "3-2", first_name: "Bob", age: "30", interest: "gaming", ...}
    // ]
  }

  // ============================================
  // EXAMPLE 2: E-COMMERCE ORDER PROCESSING
  // ============================================

  /**
   * Process e-commerce orders:
   * - Filter confirmed orders only
   * - Add processing metadata
   * - Remove sensitive payment data
   * - Split line items
   */
  def orderProcessingPipeline(): Future[Seq[DataRecord]] = {
    val orders = Seq(
      DataRecord(
        id = "order-1",
        data = Map(
          "orderId" -> "12345",
          "status" -> "confirmed",
          "customerId" -> "C001",
          "lineItems" -> "item1,item2,item3",
          "creditCard" -> "1234-5678-9012-3456"  // Sensitive!
        )
      ),
      DataRecord(
        id = "order-2",
        data = Map(
          "orderId" -> "12346",
          "status" -> "pending",
          "customerId" -> "C002",
          "lineItems" -> "item4,item5"
        )
      )
    )

    val filterConfirmed = new FilterTransform(
      FilterConfig("status == confirmed")
    )

    val removeSensitiveData = new MapTransform(
      MapConfig(
        mappings = Map(
          "creditCard" -> null,  // Remove credit card
          "ssn" -> null          // Remove SSN if present
        ),
        preserveUnmapped = true
      )
    )

    val addMetadata = new MapTransform(
      MapConfig(
        mappings = Map(
          "processed" -> "processingStatus",
          "2024-01-01" -> "processedDate"
        ),
        preserveUnmapped = true
      )
    )

    val splitLineItems = new FlatMapTransform(
      FlatMapConfig(
        splitField = "lineItems",
        targetField = Some("itemId"),
        preserveParent = true
      )
    )

    Source(orders)
      .via(filterConfirmed.flow)
      .via(removeSensitiveData.flow)
      .via(addMetadata.flow)
      .via(splitLineItems.flow)
      .runWith(Sink.seq)
  }

  // ============================================
  // EXAMPLE 3: LOG AGGREGATION
  // ============================================

  /**
   * Process application logs:
   * - Filter ERROR level logs only
   * - Normalize timestamps
   * - Remove stack traces (too verbose)
   * - Extract error codes
   */
  def logProcessingPipeline(): Future[Seq[DataRecord]] = {
    val logs = Seq(
      DataRecord(
        id = "log-1",
        data = Map(
          "level" -> "ERROR",
          "msg" -> "Database connection failed",
          "ts" -> "2024-01-01T10:00:00Z",
          "stackTrace" -> "very.long.stack.trace...",
          "errorCode" -> "DB_001"
        )
      ),
      DataRecord(
        id = "log-2",
        data = Map(
          "level" -> "INFO",
          "msg" -> "User logged in",
          "ts" -> "2024-01-01T10:01:00Z"
        )
      ),
      DataRecord(
        id = "log-3",
        data = Map(
          "level" -> "ERROR",
          "msg" -> "Invalid user input",
          "ts" -> "2024-01-01T10:02:00Z",
          "stackTrace" -> "another.long.trace...",
          "errorCode" -> "VAL_002"
        )
      )
    )

    val filterErrors = new FilterTransform(
      FilterConfig("level == ERROR")
    )

    val normalizeAndClean = new MapTransform(
      MapConfig(
        mappings = Map(
          "msg" -> "message",
          "ts" -> "timestamp",
          "stackTrace" -> null  // Remove verbose stack traces
        ),
        preserveUnmapped = true
      )
    )

    Source(logs)
      .via(filterErrors.flow)
      .via(normalizeAndClean.flow)
      .runWith(Sink.seq)

    // Expected output:
    // [
    //   {id: "log-1", level: "ERROR", message: "Database connection failed", timestamp: "...", errorCode: "DB_001"},
    //   {id: "log-3", level: "ERROR", message: "Invalid user input", timestamp: "...", errorCode: "VAL_002"}
    // ]
  }

  // ============================================
  // EXAMPLE 4: DATA QUALITY PIPELINE
  // ============================================

  /**
   * Multi-stage data cleaning:
   * - Remove test data
   * - Remove incomplete records
   * - Standardize field names
   * - Add quality metadata
   */
  def dataQualityPipeline(): Future[Seq[DataRecord]] = {
    val rawData = Seq(
      DataRecord("1", Map("env" -> "prod", "userId" -> "U001", "email" -> "user1@example.com")),
      DataRecord("2", Map("env" -> "test", "userId" -> "U002", "email" -> "test@example.com")),
      DataRecord("3", Map("env" -> "prod", "userId" -> "U003")),  // Missing email
      DataRecord("4", Map("env" -> "prod", "userId" -> "U004", "email" -> "user4@example.com"))
    )

    // Stage 1: Remove test data
    val removeTestData = new FilterTransform(
      FilterConfig("env != test")
    )

    // Stage 2: Remove incomplete records (must have email)
    val requireEmail = new FilterTransform(
      FilterConfig("$.email != null")  // In practice, check if field exists
    )

    // Stage 3: Standardize field names
    val standardizeNames = new MapTransform(
      MapConfig(
        mappings = Map(
          "userId" -> "user_id",
          "email" -> "email_address"
        ),
        preserveUnmapped = true
      )
    )

    // Stage 4: Add quality metadata
    val addQualityScore = new MapTransform(
      MapConfig(
        mappings = Map(
          "high" -> "dataQuality",
          "validated" -> "status"
        ),
        preserveUnmapped = true
      )
    )

    Source(rawData)
      .via(removeTestData.flow)
      .via(requireEmail.flow)
      .via(standardizeNames.flow)
      .via(addQualityScore.flow)
      .runWith(Sink.seq)

    // Expected: Only records 1 and 4 pass all stages
  }

  // ============================================
  // EXAMPLE 5: MULTI-TENANT DATA ROUTING
  // ============================================

  /**
   * Route data for different tenants:
   * - Filter by tenant ID
   * - Apply tenant-specific transforms
   * - Add tenant metadata
   */
  def tenantRoutingPipeline(tenantId: String): Future[Seq[DataRecord]] = {
    val records = Seq(
      DataRecord("1", Map("tenantId" -> "tenant-A", "data" -> "value1")),
      DataRecord("2", Map("tenantId" -> "tenant-B", "data" -> "value2")),
      DataRecord("3", Map("tenantId" -> "tenant-A", "data" -> "value3"))
    )

    val filterTenant = new FilterTransform(
      FilterConfig(s"tenantId == $tenantId")
    )

    val addTenantMetadata = new MapTransform(
      MapConfig(
        mappings = Map(
          tenantId -> "tenant",
          "isolated" -> "processingMode"
        ),
        preserveUnmapped = true
      )
    )

    Source(records)
      .via(filterTenant.flow)
      .via(addTenantMetadata.flow)
      .runWith(Sink.seq)
  }

  // ============================================
  // EXAMPLE 6: REAL-TIME ANALYTICS PREP
  // ============================================

  /**
   * Prepare events for real-time analytics:
   * - Filter relevant event types
   * - Flatten nested structures
   * - Split dimensions for analytics
   */
  def analyticsPreparationPipeline(): Future[Seq[DataRecord]] = {
    val events = Seq(
      DataRecord(
        id = "evt-1",
        data = Map(
          "eventType" -> "page_view",
          "userId" -> "U001",
          "page" -> "home",
          "dimensions" -> "country:US,device:mobile,browser:chrome"
        )
      ),
      DataRecord(
        id = "evt-2",
        data = Map(
          "eventType" -> "purchase",
          "userId" -> "U002",
          "amount" -> "99.99",
          "dimensions" -> "country:UK,device:desktop"
        )
      )
    )

    val filterPageViews = new FilterTransform(
      FilterConfig("eventType == page_view")
    )

    val splitDimensions = new FlatMapTransform(
      FlatMapConfig(
        splitField = "dimensions",
        targetField = Some("dimension"),
        preserveParent = true
      )
    )

    Source(events)
      .via(filterPageViews.flow)
      .via(splitDimensions.flow)
      .runWith(Sink.seq)

    // Output: Multiple records per event, one per dimension
    // Makes it easy to aggregate by country, device, browser, etc.
  }

  // ============================================
  // HELPER: RUN ALL EXAMPLES
  // ============================================

  def runAllExamples(): Future[Unit] = {
    for {
      users <- userProcessingPipeline()
      orders <- orderProcessingPipeline()
      logs <- logProcessingPipeline()
      quality <- dataQualityPipeline()
      analytics <- analyticsPreparationPipeline()
    } yield {
      println(s"User processing: ${users.size} records")
      println(s"Order processing: ${orders.size} records")
      println(s"Log processing: ${logs.size} records")
      println(s"Data quality: ${quality.size} records")
      println(s"Analytics prep: ${analytics.size} records")
    }
  }
}

// ============================================
// MAIN APPLICATION
// ============================================

object TransformExamplesApp extends App {
  import org.apache.pekko.actor.typed.ActorSystem
  import org.apache.pekko.actor.typed.scaladsl.Behaviors

  implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "transform-examples")
  implicit val ec: ExecutionContext = system.executionContext

  println("Running Transform Examples...")
  println("=" * 60)

  TransformExamples.runAllExamples().onComplete { result =>
    println("=" * 60)
    println(s"All examples completed: $result")
    system.terminate()
  }
}

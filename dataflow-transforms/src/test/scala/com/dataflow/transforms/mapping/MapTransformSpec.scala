package com.dataflow.transforms.mapping

import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.domain.MapConfig
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.{Source, Sink}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

class MapTransformSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

  "MapTransform" should {

    "rename fields" in {
      val transform = new MapTransform(MapConfig(
        mappings = Map(
          "firstName" -> "first_name",
          "lastName" -> "last_name"
        ),
        preserveUnmapped = true
      ))

      val input = Seq(
        DataRecord("1", Map("firstName" -> "John", "lastName" -> "Doe", "age" -> "30"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 1
      val record = result.head
      record.data should contain key "first_name"
      record.data should contain key "last_name"
      record.data should contain key "age"
      record.data should not contain key("firstName")
      record.data should not contain key("lastName")
      record.data("first_name") shouldBe "John"
      record.data("last_name") shouldBe "Doe"
    }

    "delete fields when mapped to null" in {
      val transform = new MapTransform(MapConfig(
        mappings = Map(
          "password" -> null,
          "ssn" -> null
        ),
        preserveUnmapped = true
      ))

      val input = Seq(
        DataRecord("1", Map(
          "username" -> "john",
          "password" -> "secret",
          "ssn" -> "123-45-6789",
          "email" -> "john@example.com"
        ))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 1
      val record = result.head
      record.data should not contain key("password")
      record.data should not contain key("ssn")
      record.data should contain key "username"
      record.data should contain key "email"
    }

    "inject constant values" in {
      val transform = new MapTransform(MapConfig(
        mappings = Map(
          "processed" -> "status",
          "v1.0" -> "version"
        ),
        preserveUnmapped = true
      ))

      val input = Seq(
        DataRecord("1", Map("data" -> "value"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 1
      val record = result.head
      record.data should contain key "status"
      record.data should contain key "version"
      record.data should contain key "data"
      record.data("status") shouldBe "processed"
      record.data("version") shouldBe "v1.0"
    }

    "preserve unmapped fields when flag is true" in {
      val transform = new MapTransform(MapConfig(
        mappings = Map("old" -> "new"),
        preserveUnmapped = true
      ))

      val input = Seq(
        DataRecord("1", Map("old" -> "value1", "keep" -> "value2", "also_keep" -> "value3"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 1
      val record = result.head
      record.data should have size 3
      record.data should contain key "new"
      record.data should contain key "keep"
      record.data should contain key "also_keep"
    }

    "not preserve unmapped fields when flag is false" in {
      val transform = new MapTransform(MapConfig(
        mappings = Map("old" -> "new"),
        preserveUnmapped = false
      ))

      val input = Seq(
        DataRecord("1", Map("old" -> "value1", "remove" -> "value2"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 1
      val record = result.head
      record.data should have size 1
      record.data should contain key "new"
      record.data should not contain key("remove")
    }

    "handle nested field extraction" in {
      val transform = new MapTransform(MapConfig(
        mappings = Map("user.email" -> "email"),
        preserveUnmapped = true
      ))

      val input = Seq(
        DataRecord("1", Map("user.email" -> "john@example.com", "user.name" -> "John"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 1
      val record = result.head
      record.data should contain key "email"
      record.data("email") shouldBe "john@example.com"
    }

    "handle multiple operations together" in {
      val transform = new MapTransform(MapConfig(
        mappings = Map(
          "firstName" -> "first_name",  // Rename
          "password" -> null,           // Delete
          "active" -> "status"          // Inject constant
        ),
        preserveUnmapped = true
      ))

      val input = Seq(
        DataRecord("1", Map(
          "firstName" -> "John",
          "password" -> "secret",
          "email" -> "john@example.com"
        ))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 1
      val record = result.head
      record.data should contain key "first_name"
      record.data should contain key "status"
      record.data should contain key "email"
      record.data should not contain key("firstName")
      record.data should not contain key("password")
      record.data("first_name") shouldBe "John"
      record.data("status") shouldBe "active"
    }

    "handle empty input stream" in {
      val transform = new MapTransform(MapConfig(
        mappings = Map("old" -> "new")
      ))

      val result = Source(Seq.empty[DataRecord])
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result shouldBe empty
    }
  }
}

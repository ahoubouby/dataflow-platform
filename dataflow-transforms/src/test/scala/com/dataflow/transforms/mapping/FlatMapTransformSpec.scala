package com.dataflow.transforms.mapping

import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.domain.FlatMapConfig
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.{Source, Sink}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

class FlatMapTransformSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

  "FlatMapTransform" should {

    "split comma-separated values into multiple records" in {
      val transform = new FlatMapTransform(FlatMapConfig(
        splitField = "items",
        targetField = Some("item"),
        preserveParent = true
      ))

      val input = Seq(
        DataRecord("1", Map("items" -> "apple,banana,cherry", "price" -> "10"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 3
      result.map(_.data("item")) should contain inOrderOnly ("apple", "banana", "cherry")
      result.foreach { record =>
        record.data should contain key "price"
        record.data("price") shouldBe "10"
        record.metadata should contain key "parentId"
        record.metadata("parentId") shouldBe "1"
      }
    }

    "auto-derive singular field name" in {
      val transform = new FlatMapTransform(FlatMapConfig(
        splitField = "users",
        targetField = None,  // Will auto-derive to "user"
        preserveParent = true
      ))

      val input = Seq(
        DataRecord("1", Map("users" -> "john,jane,bob"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 3
      result.foreach { record =>
        record.data should contain key "user"
      }
      result.map(_.data("user")) should contain inOrderOnly ("john", "jane", "bob")
    }

    "preserve parent fields when flag is true" in {
      val transform = new FlatMapTransform(FlatMapConfig(
        splitField = "tags",
        targetField = Some("tag"),
        preserveParent = true
      ))

      val input = Seq(
        DataRecord("1", Map(
          "tags" -> "scala,pekko,streams",
          "author" -> "john",
          "date" -> "2024-01-01"
        ))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 3
      result.foreach { record =>
        record.data should contain key "tag"
        record.data should contain key "author"
        record.data should contain key "date"
        record.data should not contain key("tags")  // Original split field removed
      }
    }

    "not preserve parent fields when flag is false" in {
      val transform = new FlatMapTransform(FlatMapConfig(
        splitField = "items",
        targetField = Some("item"),
        preserveParent = false
      ))

      val input = Seq(
        DataRecord("1", Map("items" -> "a,b,c", "other" -> "value"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 3
      result.foreach { record =>
        record.data should have size 1
        record.data should contain key "item"
        record.data should not contain key("other")
      }
    }

    "handle single value (no splitting)" in {
      val transform = new FlatMapTransform(FlatMapConfig(
        splitField = "items",
        targetField = Some("item"),
        preserveParent = true
      ))

      val input = Seq(
        DataRecord("1", Map("items" -> "single", "price" -> "10"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 1
      result.head.data("item") shouldBe "single"
      result.head.data("price") shouldBe "10"
    }

    "handle empty field value" in {
      val transform = new FlatMapTransform(FlatMapConfig(
        splitField = "items",
        targetField = Some("item"),
        preserveParent = true
      ))

      val input = Seq(
        DataRecord("1", Map("items" -> "", "price" -> "10"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result shouldBe empty
    }

    "handle missing split field" in {
      val transform = new FlatMapTransform(FlatMapConfig(
        splitField = "items",
        targetField = Some("item"),
        preserveParent = true
      ))

      val input = Seq(
        DataRecord("1", Map("price" -> "10"))  // No "items" field
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      // Should return original record when field not found
      result should have size 1
      result.head shouldBe input.head
    }

    "generate correct child record IDs" in {
      val transform = new FlatMapTransform(FlatMapConfig(
        splitField = "items",
        targetField = Some("item"),
        preserveParent = true
      ))

      val input = Seq(
        DataRecord("parent-1", Map("items" -> "a,b,c"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 3
      result.map(_.id) should contain inOrderOnly ("parent-1-1", "parent-1-2", "parent-1-3")
    }

    "add split metadata to child records" in {
      val transform = new FlatMapTransform(FlatMapConfig(
        splitField = "items",
        targetField = Some("item"),
        preserveParent = true
      ))

      val input = Seq(
        DataRecord("1", Map("items" -> "a,b,c"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 3
      result.zipWithIndex.foreach { case (record, index) =>
        record.metadata("parentId") shouldBe "1"
        record.metadata("splitIndex") shouldBe index.toString
      }
    }

    "handle whitespace in values" in {
      val transform = new FlatMapTransform(FlatMapConfig(
        splitField = "items",
        targetField = Some("item"),
        preserveParent = false
      ))

      val input = Seq(
        DataRecord("1", Map("items" -> " apple , banana , cherry "))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 3
      // Values should be trimmed
      result.map(_.data("item")) should contain inOrderOnly ("apple", "banana", "cherry")
    }

    "handle plural to singular conversion correctly" in {
      val transform1 = new FlatMapTransform(FlatMapConfig(
        splitField = "categories",  // "ies" ending
        targetField = None,
        preserveParent = false
      ))

      val input1 = Seq(DataRecord("1", Map("categories" -> "a,b")))
      val result1 = Source(input1).via(transform1.flow).runWith(Sink.seq).futureValue
      result1.head.data should contain key "category"

      val transform2 = new FlatMapTransform(FlatMapConfig(
        splitField = "classes",  // "es" ending
        targetField = None,
        preserveParent = false
      ))

      val input2 = Seq(DataRecord("1", Map("classes" -> "a,b")))
      val result2 = Source(input2).via(transform2.flow).runWith(Sink.seq).futureValue
      result2.head.data should contain key "class"
    }
  }
}

package com.dataflow.transforms

import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.domain.{FilterConfig, MapConfig, FlatMapConfig}
import com.dataflow.transforms.filters.FilterTransform
import com.dataflow.transforms.mapping.{MapTransform, FlatMapTransform}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.{Source, Sink}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

class TransformChainSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

  "TransformChain" should {

    "compose multiple transforms into a single flow" in {
      val filter = new FilterTransform(FilterConfig("age >= 18"))
      val map = new MapTransform(MapConfig(Map("firstName" -> "first_name")))

      val flow = TransformChain.compose(filter, map)

      val input = Seq(
        DataRecord("1", Map("firstName" -> "John", "age" -> "25")),
        DataRecord("2", Map("firstName" -> "Jane", "age" -> "15")),
        DataRecord("3", Map("firstName" -> "Bob", "age" -> "30"))
      )

      val result = Source(input)
        .via(flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 2
      result.foreach { record =>
        record.data should contain key "first_name"
        record.data should not contain key("firstName")
      }
    }

    "chain transforms from a sequence" in {
      val transforms = Seq(
        new FilterTransform(FilterConfig("status == active")),
        new MapTransform(MapConfig(Map("old" -> "new")))
      )

      val flow = TransformChain.chain(transforms)

      val input = Seq(
        DataRecord("1", Map("status" -> "active", "old" -> "value1")),
        DataRecord("2", Map("status" -> "inactive", "old" -> "value2"))
      )

      val result = Source(input)
        .via(flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 1
      result.head.data should contain key "new"
    }

    "create chain from configurations" in {
      val configs = Seq(
        FilterConfig("age >= 18"),
        MapConfig(Map("firstName" -> "first_name")),
        FlatMapConfig("interests", Some("interest"))
      )

      val flowTry = TransformChain.fromConfigs(configs)

      flowTry.isSuccess shouldBe true
      val flow = flowTry.get

      val input = Seq(
        DataRecord("1", Map("firstName" -> "John", "age" -> "25", "interests" -> "music,sports"))
      )

      val result = Source(input)
        .via(flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 2  // Split into 2 records
      result.foreach { record =>
        record.data should contain key "first_name"
        record.data should contain key "interest"
      }
    }

    "handle empty chain" in {
      val flow = TransformChain.compose()

      val input = Seq(
        DataRecord("1", Map("data" -> "value"))
      )

      val result = Source(input)
        .via(flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 1
      result.head shouldBe input.head
    }

    "apply transforms in correct order" in {
      // Order matters: filter first, then map
      val filter = new FilterTransform(FilterConfig("keep == true"))
      val map = new MapTransform(MapConfig(Map("keep" -> null)))  // Delete "keep" field

      val flow = TransformChain.compose(filter, map)

      val input = Seq(
        DataRecord("1", Map("keep" -> "true", "value" -> "1")),
        DataRecord("2", Map("keep" -> "false", "value" -> "2"))
      )

      val result = Source(input)
        .via(flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 1
      result.head.data should not contain key("keep")  // Deleted by map
      result.head.data("value") shouldBe "1"
    }
  }

  "TransformChainBuilder" should {

    "build chain with fluent API" in {
      val flow = TransformChain.builder()
        .add(new FilterTransform(FilterConfig("status == active")))
        .add(new MapTransform(MapConfig(Map("old" -> "new"))))
        .build()

      val input = Seq(
        DataRecord("1", Map("status" -> "active", "old" -> "value"))
      )

      val result = Source(input)
        .via(flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 1
      result.head.data should contain key "new"
    }

    "support addAll method" in {
      val transforms = Seq(
        new FilterTransform(FilterConfig("age >= 18")),
        new MapTransform(MapConfig(Map("name" -> "full_name")))
      )

      val flow = TransformChain.builder()
        .addAll(transforms: _*)
        .build()

      val input = Seq(
        DataRecord("1", Map("name" -> "John", "age" -> "25"))
      )

      val result = Source(input)
        .via(flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 1
      result.head.data should contain key "full_name"
    }

    "support addFromConfig method" in {
      val flow = TransformChain.builder()
        .addFromConfig(FilterConfig("status == active"))
        .addFromConfig(MapConfig(Map("old" -> "new")))
        .build()

      val input = Seq(
        DataRecord("1", Map("status" -> "active", "old" -> "value"))
      )

      val result = Source(input)
        .via(flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 1
      result.head.data should contain key "new"
    }

    "report correct size" in {
      val builder = TransformChain.builder()
        .add(new FilterTransform(FilterConfig("status == active")))
        .add(new MapTransform(MapConfig(Map("old" -> "new"))))

      builder.size shouldBe 2
      builder.isEmpty shouldBe false

      val emptyBuilder = TransformChain.builder()
      emptyBuilder.size shouldBe 0
      emptyBuilder.isEmpty shouldBe true
    }
  }
}

package com.dataflow.transforms.filters

import com.dataflow.domain.models.DataRecord
import com.dataflow.transforms.domain.FilterConfig
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.{Source, Sink}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class FilterTransformSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

  "FilterTransform" should {

    "filter records with equality comparison" in {
      val transform = new FilterTransform(FilterConfig("status == active"))

      val input = Seq(
        DataRecord("1", Map("status" -> "active", "name" -> "John")),
        DataRecord("2", Map("status" -> "inactive", "name" -> "Jane")),
        DataRecord("3", Map("status" -> "active", "name" -> "Bob"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 2
      result.map(_.id) should contain only ("1", "3")
    }

    "filter records with not-equal comparison" in {
      val transform = new FilterTransform(FilterConfig("type != test"))

      val input = Seq(
        DataRecord("1", Map("type" -> "prod", "value" -> "100")),
        DataRecord("2", Map("type" -> "test", "value" -> "50")),
        DataRecord("3", Map("type" -> "dev", "value" -> "75"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 2
      result.map(_.id) should contain only ("1", "3")
    }

    "filter records with greater than comparison" in {
      val transform = new FilterTransform(FilterConfig("age > 18"))

      val input = Seq(
        DataRecord("1", Map("age" -> "25", "name" -> "John")),
        DataRecord("2", Map("age" -> "15", "name" -> "Jane")),
        DataRecord("3", Map("age" -> "30", "name" -> "Bob")),
        DataRecord("4", Map("age" -> "18", "name" -> "Alice"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 2
      result.map(_.id) should contain only ("1", "3")
    }

    "filter records with greater than or equal comparison" in {
      val transform = new FilterTransform(FilterConfig("age >= 18"))

      val input = Seq(
        DataRecord("1", Map("age" -> "25", "name" -> "John")),
        DataRecord("2", Map("age" -> "15", "name" -> "Jane")),
        DataRecord("3", Map("age" -> "18", "name" -> "Alice"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 2
      result.map(_.id) should contain only ("1", "3")
    }

    "filter records with less than comparison" in {
      val transform = new FilterTransform(FilterConfig("priority < 5"))

      val input = Seq(
        DataRecord("1", Map("priority" -> "3", "task" -> "A")),
        DataRecord("2", Map("priority" -> "7", "task" -> "B")),
        DataRecord("3", Map("priority" -> "2", "task" -> "C"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 2
      result.map(_.id) should contain only ("1", "3")
    }

    "filter records with less than or equal comparison" in {
      val transform = new FilterTransform(FilterConfig("priority <= 5"))

      val input = Seq(
        DataRecord("1", Map("priority" -> "5", "task" -> "A")),
        DataRecord("2", Map("priority" -> "7", "task" -> "B")),
        DataRecord("3", Map("priority" -> "3", "task" -> "C"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 2
      result.map(_.id) should contain only ("1", "3")
    }

    "filter out records without the specified field" in {
      val transform = new FilterTransform(FilterConfig("status == active"))

      val input = Seq(
        DataRecord("1", Map("status" -> "active", "name" -> "John")),
        DataRecord("2", Map("name" -> "Jane")),  // Missing status field
        DataRecord("3", Map("status" -> "active", "name" -> "Bob"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result should have size 2
      result.map(_.id) should contain only ("1", "3")
    }

    "handle empty input stream" in {
      val transform = new FilterTransform(FilterConfig("status == active"))

      val result = Source(Seq.empty[DataRecord])
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      result shouldBe empty
    }

    "handle invalid expression format gracefully" in {
      val transform = new FilterTransform(FilterConfig("invalid expression"))

      val input = Seq(
        DataRecord("1", Map("status" -> "active"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      // Invalid expression should filter out all records
      result shouldBe empty
    }

    "handle non-numeric comparison gracefully" in {
      val transform = new FilterTransform(FilterConfig("name > test"))

      val input = Seq(
        DataRecord("1", Map("name" -> "John"))
      )

      val result = Source(input)
        .via(transform.flow)
        .runWith(Sink.seq)
        .futureValue

      // Non-numeric comparison should fail gracefully
      result shouldBe empty
    }
  }
}

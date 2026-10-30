package scalatest.dcbstore.examples

import dcbstore.{Tag, Tags}
import dcbstore.examples.{*, given}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final case class SomethingHappened(id: String) derives CanEqual

class ExampleConventionsSpec
  extends AnyFlatSpec
    with Matchers:

  // Tag does not derive CanEqual in dcb-store, so under strictEquality comparing Tags needs it given here.
  private given CanEqual[Tag, Tag] = CanEqual.derived

  "An event" should "get the simple class name of its data as event type" in :
    val tags = Tags("something", "s1")
    val somethingHappened = event(SomethingHappened("s1"), tags)
    val _ = somethingHappened.eventType shouldBe "SomethingHappened"
    val _ = somethingHappened.data shouldBe SomethingHappened("s1")
    somethingHappened.tags shouldBe tags

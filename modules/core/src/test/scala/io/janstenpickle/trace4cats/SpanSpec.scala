package io.janstenpickle.trace4cats

import java.util.concurrent.{ScheduledExecutorService, ScheduledThreadPoolExecutor}

import cats.effect.concurrent.Deferred
import cats.effect.laws.util.TestContext
import cats.effect.{ContextShift, ExitCase, IO, Timer}
import cats.implicits._
import io.janstenpickle.trace4cats.kernel.{SpanCompleter, SpanSampler}
import io.janstenpickle.trace4cats.model._
import io.janstenpickle.trace4cats.test.ArbitraryInstances
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import scala.concurrent.duration._
import scala.util.Try

class SpanSpec extends AnyFlatSpec with Matchers with ScalaCheckDrivenPropertyChecks with ArbitraryInstances {
  val ec: TestContext = TestContext()
  implicit val timer: Timer[IO] = ec.ioTimer
  implicit val ctx: ContextShift[IO] = ec.ioContextShift

  val sc: ScheduledExecutorService = new ScheduledThreadPoolExecutor(1)

  behavior.of("Span.root")

  it should "create a new ref span when not sampled" in forAll { (name: String, kind: SpanKind) =>
    assert(
      Span
        .root[IO](name, kind, SpanSampler.always, SpanCompleter.empty)
        .use(s => IO(s.isInstanceOf[RefSpan[IO]]))
        .unsafeRunSync()
    )
  }

  it should "create a new empty span when sampled" in forAll { (name: String, kind: SpanKind) =>
    assert(
      Span
        .root[IO](name, kind, SpanSampler.never, SpanCompleter.empty)
        .use(s => IO(s.isInstanceOf[EmptySpan[IO]]))
        .unsafeRunSync()
    )
  }

  it should "complete a new root span" in forAll { (name: String, kind: SpanKind) =>
    var span: CompletedSpan = null

    Span.root[IO](name, kind, SpanSampler.always, callbackCompleter(span = _)).use(_ => IO.unit).unsafeRunSync()

    span.context.parent should be(None)
    span.context.isRemote should be(false)
    span.context.traceFlags.sampled should be(false)
  }

  it should "not complete a sampled root span" in forAll { (name: String, kind: SpanKind, status: SpanStatus) =>
    var span: CompletedSpan = null
    Span.root[IO](name, kind, SpanSampler.never, callbackCompleter(span = _)).use(_.setStatus(status)).unsafeRunSync()

    span should be(null)
  }

  behavior.of("Span.child")

  it should "create a new ref span from a parent context" in forAll {
    (name: String, parentContext: SpanContext, kind: SpanKind) =>
      assert(
        Span
          .child[IO](
            name,
            parentContext.copy(traceFlags = TraceFlags(sampled = false)),
            kind,
            SpanSampler.always,
            SpanCompleter.empty
          )
          .use(s => IO(s.isInstanceOf[RefSpan[IO]]))
          .unsafeRunSync()
      )
  }

  it should "create a new empty span from a sampled parent context" in forAll {
    (name: String, parentContext: SpanContext, kind: SpanKind) =>
      assert(
        Span
          .child[IO](
            name,
            parentContext.copy(traceFlags = TraceFlags(sampled = true)),
            kind,
            SpanSampler.always,
            SpanCompleter.empty
          )
          .use(s => IO(s.isInstanceOf[EmptySpan[IO]]))
          .unsafeRunSync()
      )
  }

  it should "create a new span from a parent context" in forAll {
    (name: String, parentContext: SpanContext, kind: SpanKind) =>
      var span: CompletedSpan = null
      Span
        .child[IO](
          name,
          parentContext.copy(traceFlags = TraceFlags(sampled = false)),
          kind,
          SpanSampler.always,
          callbackCompleter(span = _)
        )
        .use(_ => IO.unit)
        .unsafeRunSync()

      span.name should be(name)
      span.kind should be(kind)
      span.context.parent should be(Some(Parent(parentContext.spanId, parentContext.isRemote)))
  }

  it should "not use the completer on a sampled span" in forAll {
    (name: String, parentContext: SpanContext, kind: SpanKind, status: SpanStatus) =>
      var span: CompletedSpan = null
      Span
        .child[IO](
          name,
          parentContext.copy(traceFlags = TraceFlags(sampled = true)),
          kind,
          SpanSampler.always,
          callbackCompleter(span = _)
        )
        .use(_.setStatus(status))
        .unsafeRunSync()

      span should be(null)
  }

  behavior.of("RefSpan")

  it should "set the span name and kind" in forAll { (name: String, kind: SpanKind) =>
    var span: CompletedSpan = null
    Span.root[IO](name, kind, SpanSampler.always, callbackCompleter(span = _)).use(_ => IO.unit).unsafeRunSync()

    span.name should be(name)
    span.kind should be(kind)
  }

  it should "create a child ref span" in forAll {
    (name: String, childName: String, kind: SpanKind, childKind: SpanKind) =>
      var spans: List[CompletedSpan] = List.empty

      Span
        .root[IO](name, kind, SpanSampler.always, callbackCompleter(span => spans = span :: spans))
        .flatTap(_.child(childName, childKind))
        .use(_ => IO.unit)
        .unsafeRunSync()

      spans.size should be(2)

      spans.head.context.parent should be(None)
      spans.head.context.isRemote should be(false)
      spans.head.context.traceFlags.sampled should be(false)
      spans.head.name should be(name)
      spans.head.kind should be(kind)

      spans(1).context.parent should be(Some(Parent(spans.head.context.spanId, isRemote = false)))
      spans(1).context.isRemote should be(false)
      spans(1).context.traceFlags.sampled should be(false)
      spans(1).name should be(childName)
      spans(1).kind should be(childKind)

  }

  it should "create a sampled child span" in forAll {
    (name: String, childName: String, kind: SpanKind, childKind: SpanKind) =>
      var spans: List[CompletedSpan] = List.empty

      val sampler = new SpanSampler[IO] {
        var callCount: Int = 0

        override def shouldSample(
          parentContext: Option[SpanContext],
          traceId: TraceId,
          spanName: String,
          spanKind: SpanKind
        ): IO[Boolean] =
          IO(if (callCount == 0) {
            callCount = callCount + 1
            false
          } else {
            true
          })
      }

      def assertSampled(span: Span[IO]) = assert(span.isInstanceOf[EmptySpan[IO]])

      Span
        .root[IO](name, kind, sampler, callbackCompleter(span => spans = span :: spans))
        .flatTap(_.child(childName, childKind).map(assertSampled))
        .use(_ => IO.unit)
        .unsafeRunSync()

      spans.size should be(1)

      spans.head.context.parent should be(None)
      spans.head.context.isRemote should be(false)
      spans.head.context.traceFlags.sampled should be(false)
      spans.head.name should be(name)
      spans.head.kind should be(kind)
  }

  it should "use the default status of OK when completed" in forAll { (name: String, kind: SpanKind) =>
    var span: CompletedSpan = null
    Span.root[IO](name, kind, SpanSampler.always, callbackCompleter(span = _)).use(_ => IO.unit).unsafeRunSync()

    span.status should be(SpanStatus.Ok)
  }

  it should "use the provided status when completed" in forAll { (name: String, kind: SpanKind, status: SpanStatus) =>
    var span: CompletedSpan = null
    Span.root[IO](name, kind, SpanSampler.always, callbackCompleter(span = _)).use(_.setStatus(status)).unsafeRunSync()

    span.status should be(status)
  }

  it should "override the status to cancelled when execution is cancelled" in forAll {
    (name: String, kind: SpanKind, status: SpanStatus) =>
      var span: CompletedSpan = null

      Deferred[IO, ExitCase[Throwable]]
        .flatMap { stop =>
          val r = Span
            .root[IO](name, kind, SpanSampler.always, callbackCompleter(span = _))
            .use(_.setStatus(status) >> IO.never: IO[Unit])
            .guaranteeCase(stop.complete)

          r.start.flatMap { fiber =>
            timer.sleep(200.millis) >> fiber.cancel >> stop.get
          }
        }
        .timeout(2.seconds)
        .unsafeToFuture()

      ec.tick(3.seconds)

      span.status should be(SpanStatus.Cancelled)
  }

  it should "override the status to internal when execution fails" in forAll {
    (name: String, kind: SpanKind, status: SpanStatus, errorMsg: String) =>
      var span: CompletedSpan = null

      Try(
        Span
          .root[IO](name, kind, SpanSampler.always, callbackCompleter(span = _))
          .use(_.setStatus(status) >> IO.raiseError[Unit](new RuntimeException(errorMsg)))
          .unsafeRunSync()
      )

      span.status should be(SpanStatus.Internal)
      (span.attributes should contain)
        .theSameElementsAs(Map[String, TraceValue]("error" -> true, "span.status.message" -> errorMsg))
  }

  it should "add a glob of attributes" in forAll {
    (name: String, kind: SpanKind, attributes: Map[String, TraceValue]) =>
      var span: CompletedSpan = null

      Span
        .root[IO](name, kind, SpanSampler.always, callbackCompleter(span = _))
        .use(_.putAll(attributes.toList: _*))
        .unsafeRunSync()

      (span.attributes should contain).theSameElementsAs(attributes)
  }

  it should "add individual attributes" in forAll {
    (name: String, kind: SpanKind, attributes: Map[String, TraceValue]) =>
      var span: CompletedSpan = null

      Span
        .root[IO](name, kind, SpanSampler.always, callbackCompleter(span = _))
        .use(span => attributes.toList.traverse { case (k, v) => span.put(k, v) })
        .unsafeRunSync()

      (span.attributes should contain).theSameElementsAs(attributes)
  }

  behavior.of("EmptySpan")

  it should "never complete" in forAll { (name: String, kind: SpanKind) =>
    var span: CompletedSpan = null
    Span.root[IO](name, kind, SpanSampler.never, callbackCompleter(span = _)).use(_ => IO.unit).unsafeRunSync()

    span should be(null)
  }

  it should "create a child empty span" in forAll {
    (name: String, childName: String, kind: SpanKind, childKind: SpanKind) =>
      var spans: List[CompletedSpan] = List.empty

      Span
        .root[IO](name, kind, SpanSampler.never, callbackCompleter(span => spans = span :: spans))
        .flatTap(_.child(childName, childKind))
        .use(_ => IO.unit)
        .unsafeRunSync()

      spans.size should be(0)
  }

  def callbackCompleter(callback: CompletedSpan => Any): SpanCompleter[IO] = new SpanCompleter[IO] {
    override def complete(span: CompletedSpan): IO[Unit] = IO(callback(span)).void
  }
}

package io.janstenpickle.trace4cats.test.jaeger

import java.util.concurrent.TimeUnit

import cats.data.NonEmptyList
import cats.effect.{Blocker, IO, Resource}
import cats.implicits._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.generic.auto._
import io.janstenpickle.trace4cats.kernel.{SpanCompleter, SpanExporter}
import io.janstenpickle.trace4cats.model.{Batch, CompletedSpan, SpanKind, SpanStatus, TraceProcess, TraceValue}
import io.janstenpickle.trace4cats.test.ArbitraryInstances
import org.http4s.circe.CirceEntityCodec._
import org.http4s.ember.client.EmberClientBuilder
import org.scalacheck.Shrink
import org.scalatest.Assertion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

trait BaseJaegerSpec extends AnyFlatSpec with ScalaCheckDrivenPropertyChecks with ArbitraryInstances {

  implicit val contextShift = IO.contextShift(ExecutionContext.global)
  implicit val timer = IO.timer(ExecutionContext.global)

  val blocker = Blocker.liftExecutionContext(ExecutionContext.global)

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 3, maxDiscardedFactor = 50.0)

  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  behavior.of("JaegerSpanExport")

  def batchToJaegerResponse(
    batch: Batch,
    kindToAttributes: SpanKind => Map[String, TraceValue],
    statusToAttributes: SpanStatus => Map[String, TraceValue]
  ): List[JaegerTraceResponse] = {
    def convertAttributes(attributes: Map[String, TraceValue]): List[JaegerTag] = attributes.toList.map {
      case (k, TraceValue.StringValue(value)) => JaegerTag.StringTag(k, value)
      case (k, TraceValue.BooleanValue(value)) => JaegerTag.BoolTag(k, value)
      case (k, TraceValue.DoubleValue(value)) => JaegerTag.FloatTag(k, value)
      case (k, TraceValue.LongValue(value)) => JaegerTag.LongTag(k, value)
    }

    batch.spans
      .groupBy(_.context.traceId)
      .toList
      .map {
        case (traceId, spans) =>
          JaegerTraceResponse(
            NonEmptyList
              .one(
                JaegerTrace(
                  traceID = traceId.show,
                  spans = spans
                    .map { span =>
                      JaegerSpan(
                        traceID = traceId.show,
                        spanID = span.context.spanId.show,
                        operationName = span.name,
                        startTime = TimeUnit.MILLISECONDS.toMicros(span.start.toEpochMilli),
                        duration = TimeUnit.MILLISECONDS.toMicros(span.end.toEpochMilli) - TimeUnit.MILLISECONDS
                          .toMicros(span.start.toEpochMilli),
                        tags = (JaegerTag.StringTag("internal.span.format", "proto") :: convertAttributes(
                          span.attributes ++ kindToAttributes(span.kind) ++ statusToAttributes(span.status)
                        )).sortBy(_.key),
                        references = span.context.parent.toList.map { parent =>
                          JaegerReference("CHILD_OF", traceId.show, parent.spanId.show)
                        }
                      )
                    }
                    .sortBy(_.operationName),
                  processes = Map(
                    "p1" -> JaegerProcess(
                      batch.process.serviceName,
                      convertAttributes(batch.process.attributes).sortBy(_.key)
                    )
                  )
                )
              )
          )
      }
      .sortBy(_.data.head.traceID)
  }

  def testExporter(
    exporter: Resource[IO, SpanExporter[IO]],
    batch: Batch,
    expectedResponse: List[JaegerTraceResponse]
  ): Assertion = {
    val res =
      EmberClientBuilder
        .default[IO]
        .withBlocker(blocker)
        .build
        .use { client =>
          exporter.use(_.exportBatch(batch)) >> timer
            .sleep(1.second) >> batch.spans
            .map(_.context.traceId)
            .distinct
            .traverse { traceId =>
              client.expect[JaegerTraceResponse](s"http://localhost:16686/api/traces/${traceId.show}")
            }

        }
        .unsafeRunSync()
        .sortBy(_.data.head.traceID)

    assert(res === expectedResponse)
  }

  def testCompleter(
    completer: Resource[IO, SpanCompleter[IO]],
    span: CompletedSpan,
    process: TraceProcess,
    expectedResponse: List[JaegerTraceResponse]
  ) = {
    val batch = Batch(process, List(span))

    val res =
      EmberClientBuilder
        .default[IO]
        .withBlocker(blocker)
        .build
        .use { client =>
          completer.use(_.complete(span)) >> timer
            .sleep(1.second) >> batch.spans
            .map(_.context.traceId)
            .distinct
            .traverse { traceId =>
              client.expect[JaegerTraceResponse](s"http://localhost:16686/api/traces/${traceId.show}")
            }

        }
        .unsafeRunSync()
        .sortBy(_.data.head.traceID)

    assert(res === expectedResponse)
  }
}

package io.janstenpickle.trace4cats.avro

import cats.ApplicativeError
import cats.syntax.either._
import io.janstenpickle.trace4cats.model._
import org.apache.avro.Schema
import vulcan.generic._
import vulcan.{AvroError, Codec}

object AvroInstances {
  implicit val spanIdCodec: Codec[SpanId] =
    Codec.bytes.imapError(SpanId(_).toRight(AvroError("Invalid Span ID")))(_.value)

  implicit val traceIdCodec: Codec[TraceId] =
    Codec.bytes.imapError(TraceId(_).toRight(AvroError("Invalid Trace ID")))(_.value)

  implicit val traceStateKeyCodec: Codec[TraceState.Key] =
    Codec.string.imapError(TraceState.Key(_).toRight(AvroError("Invalid trace state key")))(_.k)

  implicit val traceStateValueCodec: Codec[TraceState.Value] =
    Codec.string.imapError(TraceState.Value(_).toRight(AvroError("Invalid trace state value")))(_.v)

  implicit val traceStateCodec: Codec[TraceState] = Codec
    .map[TraceState.Value]
    .imap(_.flatMap { case (k, v) => TraceState.Key(k).map(_ -> v) })(_.map {
      case (k, v) => k.k -> v
    })
    .imapError[TraceState](TraceState(_).toRight(AvroError("Invalid trace state size")))(_.values)

  implicit val traceFlagsCodec: Codec[TraceFlags] = Codec.boolean.imap(TraceFlags(_))(_.sampled)

  implicit val parentCodec: Codec[Parent] = Codec.derive

  implicit val spanContextCodec: Codec[SpanContext] = Codec.derive

  implicit val traceValueCodec: Codec[TraceValue] = Codec.derive[TraceValue]

  implicit val attributesCodec: Codec[Map[String, TraceValue]] = Codec.map[TraceValue]

  implicit val spanKindCodec: Codec[SpanKind] = Codec.derive[SpanKind]

  implicit val spanStatusCodec: Codec[SpanStatus] = Codec.derive[SpanStatus]

  implicit val completedSpanCodec: Codec[CompletedSpan] = Codec.derive

  implicit val processCodec: Codec[TraceProcess] = Codec.derive

  implicit val batchCodec: Codec[Batch] = Codec.derive

  def completedSpanSchema[F[_]: ApplicativeError[*[_], Throwable]]: F[Schema] =
    ApplicativeError[F, Throwable].fromEither(completedSpanCodec.schema.leftMap(_.throwable))

  def batchSchema[F[_]: ApplicativeError[*[_], Throwable]]: F[Schema] =
    ApplicativeError[F, Throwable].fromEither(batchCodec.schema.leftMap(_.throwable))
}

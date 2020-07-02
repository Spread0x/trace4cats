package io.janstenpickle.trace4cats.opentelemetry

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import io.janstenpickle.trace4cats.completer.QueuedSpanCompleter
import io.janstenpickle.trace4cats.kernel.SpanCompleter
import io.janstenpickle.trace4cats.model.TraceProcess

import scala.concurrent.duration._

object OpenTelemetrySpanCompleter {
  def apply[F[_]: Concurrent: ContextShift: Timer](
    blocker: Blocker,
    process: TraceProcess,
    host: String = "localhost",
    port: Int = 55678,
    bufferSize: Int = 2000,
    batchSize: Int = 50,
    batchTimeout: FiniteDuration = 10.seconds
  ): Resource[F, SpanCompleter[F]] =
    for {
      exporter <- OpenTelemetrySpanExporter[F](blocker, host, port)
      completer <- QueuedSpanCompleter[F](process, exporter, bufferSize, batchSize, batchTimeout)
    } yield completer
}
package io.janstenpickle.trace4cats.avro

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import io.janstenpickle.trace4cats.completer.QueuedSpanCompleter
import io.janstenpickle.trace4cats.kernel.SpanCompleter
import io.janstenpickle.trace4cats.model.TraceProcess

import scala.concurrent.duration._

object AvroSpanCompleter {
  def udp[F[_]: Concurrent: ContextShift: Timer](
    blocker: Blocker,
    process: TraceProcess,
    host: String = agentHostname,
    port: Int = agentPort,
    bufferSize: Int = 2000,
    batchSize: Int = 50,
    batchTimeout: FiniteDuration = 10.seconds
  ): Resource[F, SpanCompleter[F]] =
    for {
      exporter <- AvroSpanExporter.udp[F](blocker, host, port, bufferSize)
      completer <- QueuedSpanCompleter[F](process, exporter, bufferSize, batchSize, batchTimeout)
    } yield completer

  def tcp[F[_]: Concurrent: ContextShift: Timer](
    blocker: Blocker,
    process: TraceProcess,
    host: String = agentHostname,
    port: Int = agentPort,
    bufferSize: Int = 2000,
    batchSize: Int = 50,
    batchTimeout: FiniteDuration = 10.seconds
  ): Resource[F, SpanCompleter[F]] = {
    for {
      exporter <- AvroSpanExporter.tcp[F](blocker, host, port, bufferSize)
      completer <- QueuedSpanCompleter[F](process, exporter, bufferSize, batchSize, batchTimeout)
    } yield completer
  }
}
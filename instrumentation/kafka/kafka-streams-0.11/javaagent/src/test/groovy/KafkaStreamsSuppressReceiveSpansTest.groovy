/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.semconv.incubating.MessagingIncubatingAttributes
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.ValueMapper

import java.time.Duration

import static io.opentelemetry.api.trace.SpanKind.CONSUMER
import static io.opentelemetry.api.trace.SpanKind.PRODUCER

class KafkaStreamsSuppressReceiveSpansTest extends KafkaStreamsBaseTest {

  def "test kafka produce and consume with streams in-between"() {
    setup:
    def config = new Properties()
    config.putAll(producerProps(KafkaStreamsBaseTest.kafka.bootstrapServers))
    config.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-application")
    config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.Integer().getClass().getName())
    config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName())

    // CONFIGURE PROCESSOR
    def builder
    try {
      // Different class names for test and latestDepTest.
      builder = Class.forName("org.apache.kafka.streams.kstream.KStreamBuilder").newInstance()
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      builder = Class.forName("org.apache.kafka.streams.StreamsBuilder").newInstance()
    }
    KStream<Integer, String> textLines = builder.stream(STREAM_PENDING)
    def values = textLines
      .mapValues(new ValueMapper<String, String>() {
        @Override
        String apply(String textLine) {
          Span.current().setAttribute("asdf", "testing")
          return textLine.toLowerCase()
        }
      })

    KafkaStreams streams
    try {
      // Different api for test and latestDepTest.
      values.to(Serdes.Integer(), Serdes.String(), STREAM_PROCESSED)
      streams = new KafkaStreams(builder, config)
    } catch (MissingMethodException e) {
      def producer = Class.forName("org.apache.kafka.streams.kstream.Produced")
        .with(Serdes.Integer(), Serdes.String())
      values.to(STREAM_PROCESSED, producer)
      streams = new KafkaStreams(builder.build(), config)
    }
    streams.start()

    when:
    String greeting = "TESTING TESTING 123!"
    KafkaStreamsBaseTest.producer.send(new ProducerRecord<>(STREAM_PENDING, 10, greeting))

    then:
    // check that the message was received
    def records = KafkaStreamsBaseTest.consumer.poll(Duration.ofSeconds(10).toMillis())
    Headers receivedHeaders = null
    for (record in records) {
      Span.current().setAttribute("testing", 123)

      assert record.key() == 10
      assert record.value() == greeting.toLowerCase()

      if (receivedHeaders == null) {
        receivedHeaders = record.headers()
      }
    }

    SpanData streamSendSpan

    assertTraces(1) {
      trace(0, 4) {
        // kafka-clients PRODUCER
        span(0) {
          name STREAM_PENDING + " publish"
          kind PRODUCER
          hasNoParent()
          attributes {
            "$MessagingIncubatingAttributes.MESSAGING_SYSTEM" "kafka"
            "$MessagingIncubatingAttributes.MESSAGING_DESTINATION_NAME" STREAM_PENDING
            "$MessagingIncubatingAttributes.MESSAGING_OPERATION" "publish"
            "messaging.client_id" "producer-1"
            "$MessagingIncubatingAttributes.MESSAGING_DESTINATION_PARTITION_ID" String
            "$MessagingIncubatingAttributes.MESSAGING_KAFKA_MESSAGE_OFFSET" 0
            "$MessagingIncubatingAttributes.MESSAGING_KAFKA_MESSAGE_KEY" "10"
          }
        }
        // kafka-stream CONSUMER
        span(1) {
          name STREAM_PENDING + " process"
          kind CONSUMER
          childOf span(0)
          attributes {
            "$MessagingIncubatingAttributes.MESSAGING_SYSTEM" "kafka"
            "$MessagingIncubatingAttributes.MESSAGING_DESTINATION_NAME" STREAM_PENDING
            "$MessagingIncubatingAttributes.MESSAGING_OPERATION" "process"
            "messaging.client_id" { it.endsWith("consumer") }
            "$MessagingIncubatingAttributes.MESSAGING_MESSAGE_BODY_SIZE" Long
            "$MessagingIncubatingAttributes.MESSAGING_DESTINATION_PARTITION_ID" String
            "$MessagingIncubatingAttributes.MESSAGING_KAFKA_MESSAGE_OFFSET" 0
            "$MessagingIncubatingAttributes.MESSAGING_KAFKA_MESSAGE_KEY" "10"
            "kafka.record.queue_time_ms" { it >= 0 }
            "asdf" "testing"
            if (Boolean.getBoolean("testLatestDeps")) {
              "$MessagingIncubatingAttributes.MESSAGING_KAFKA_CONSUMER_GROUP" "test-application"
            }
          }
        }

        streamSendSpan = span(2)

        // kafka-clients PRODUCER
        span(2) {
          name STREAM_PROCESSED + " publish"
          kind PRODUCER
          childOf span(1)
          attributes {
            "$MessagingIncubatingAttributes.MESSAGING_SYSTEM" "kafka"
            "$MessagingIncubatingAttributes.MESSAGING_DESTINATION_NAME" STREAM_PROCESSED
            "$MessagingIncubatingAttributes.MESSAGING_OPERATION" "publish"
            "messaging.client_id" String
            "$MessagingIncubatingAttributes.MESSAGING_DESTINATION_PARTITION_ID" String
            "$MessagingIncubatingAttributes.MESSAGING_KAFKA_MESSAGE_OFFSET" 0
          }
        }
        // kafka-clients CONSUMER process
        span(3) {
          name STREAM_PROCESSED + " process"
          kind CONSUMER
          childOf span(2)
          attributes {
            "$MessagingIncubatingAttributes.MESSAGING_SYSTEM" "kafka"
            "$MessagingIncubatingAttributes.MESSAGING_DESTINATION_NAME" STREAM_PROCESSED
            "$MessagingIncubatingAttributes.MESSAGING_OPERATION" "process"
            "messaging.client_id" { it.startsWith("consumer") }
            "$MessagingIncubatingAttributes.MESSAGING_MESSAGE_BODY_SIZE" Long
            "$MessagingIncubatingAttributes.MESSAGING_DESTINATION_PARTITION_ID" String
            "$MessagingIncubatingAttributes.MESSAGING_KAFKA_MESSAGE_OFFSET" 0
            "$MessagingIncubatingAttributes.MESSAGING_KAFKA_MESSAGE_KEY" "10"
            if (Boolean.getBoolean("testLatestDeps")) {
              "$MessagingIncubatingAttributes.MESSAGING_KAFKA_CONSUMER_GROUP" "test"
            }
            "kafka.record.queue_time_ms" { it >= 0 }
            "testing" 123
          }
        }
      }
    }

    receivedHeaders.iterator().hasNext()
    def traceparent = new String(receivedHeaders.headers("traceparent").iterator().next().value())
    Context context = W3CTraceContextPropagator.instance.extract(Context.root(), "", new TextMapGetter<String>() {
      @Override
      Iterable<String> keys(String carrier) {
        return Collections.singleton("traceparent")
      }

      @Override
      String get(String carrier, String key) {
        if (key == "traceparent") {
          return traceparent
        }
        return null
      }
    })
    def spanContext = Span.fromContext(context).getSpanContext()
    spanContext.traceId == streamSendSpan.traceId
    spanContext.spanId == streamSendSpan.spanId
  }
}

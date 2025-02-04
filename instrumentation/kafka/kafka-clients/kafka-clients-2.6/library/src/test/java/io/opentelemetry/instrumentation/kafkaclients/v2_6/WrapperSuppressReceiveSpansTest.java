/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.kafkaclients.v2_6;

import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.equalTo;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.satisfies;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.assertj.AttributeAssertion;
import io.opentelemetry.semconv.incubating.MessagingIncubatingAttributes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.assertj.core.api.AbstractLongAssert;
import org.assertj.core.api.AbstractStringAssert;

class WrapperSuppressReceiveSpansTest extends AbstractWrapperTest {

  @Override
  void configure(KafkaTelemetryBuilder builder) {
    builder.setMessagingReceiveInstrumentationEnabled(false);
  }

  @Override
  void assertTraces(boolean testHeaders) {
    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                span ->
                    span.hasName(SHARED_TOPIC + " publish")
                        .hasKind(SpanKind.PRODUCER)
                        .hasParent(trace.getSpan(0))
                        .hasAttributesSatisfyingExactly(sendAttributes(testHeaders)),
                span ->
                    span.hasName(SHARED_TOPIC + " process")
                        .hasKind(SpanKind.CONSUMER)
                        .hasParent(trace.getSpan(1))
                        .hasAttributesSatisfyingExactly(processAttributes(greeting, testHeaders)),
                span ->
                    span.hasName("process child")
                        .hasKind(SpanKind.INTERNAL)
                        .hasParent(trace.getSpan(2)),
                span ->
                    span.hasName("producer callback")
                        .hasKind(SpanKind.INTERNAL)
                        .hasParent(trace.getSpan(0))));
  }

  @SuppressWarnings("deprecation") // using deprecated semconv
  protected static List<AttributeAssertion> sendAttributes(boolean testHeaders) {
    List<AttributeAssertion> assertions =
        new ArrayList<>(
            Arrays.asList(
                equalTo(MessagingIncubatingAttributes.MESSAGING_SYSTEM, "kafka"),
                equalTo(MessagingIncubatingAttributes.MESSAGING_DESTINATION_NAME, SHARED_TOPIC),
                equalTo(MessagingIncubatingAttributes.MESSAGING_OPERATION, "publish"),
                satisfies(MESSAGING_CLIENT_ID, stringAssert -> stringAssert.startsWith("producer")),
                satisfies(
                    MessagingIncubatingAttributes.MESSAGING_DESTINATION_PARTITION_ID,
                    AbstractStringAssert::isNotEmpty),
                satisfies(
                    MessagingIncubatingAttributes.MESSAGING_KAFKA_MESSAGE_OFFSET,
                    AbstractLongAssert::isNotNegative)));
    if (testHeaders) {
      assertions.add(
          equalTo(
              AttributeKey.stringArrayKey("messaging.header.test_message_header"),
              Collections.singletonList("test")));
    }
    return assertions;
  }

  @SuppressWarnings("deprecation") // using deprecated semconv
  private static List<AttributeAssertion> processAttributes(String greeting, boolean testHeaders) {
    List<AttributeAssertion> assertions =
        new ArrayList<>(
            Arrays.asList(
                equalTo(MessagingIncubatingAttributes.MESSAGING_SYSTEM, "kafka"),
                equalTo(MessagingIncubatingAttributes.MESSAGING_DESTINATION_NAME, SHARED_TOPIC),
                equalTo(MessagingIncubatingAttributes.MESSAGING_OPERATION, "process"),
                equalTo(
                    MessagingIncubatingAttributes.MESSAGING_MESSAGE_BODY_SIZE,
                    greeting.getBytes(StandardCharsets.UTF_8).length),
                satisfies(
                    MessagingIncubatingAttributes.MESSAGING_DESTINATION_PARTITION_ID,
                    AbstractStringAssert::isNotEmpty),
                satisfies(
                    MessagingIncubatingAttributes.MESSAGING_KAFKA_MESSAGE_OFFSET,
                    AbstractLongAssert::isNotNegative),
                satisfies(
                    AttributeKey.longKey("kafka.record.queue_time_ms"),
                    AbstractLongAssert::isNotNegative),
                equalTo(MessagingIncubatingAttributes.MESSAGING_KAFKA_CONSUMER_GROUP, "test"),
                satisfies(
                    MESSAGING_CLIENT_ID, stringAssert -> stringAssert.startsWith("consumer"))));
    if (testHeaders) {
      assertions.add(
          equalTo(
              AttributeKey.stringArrayKey("messaging.header.test_message_header"),
              Collections.singletonList("test")));
    }
    return assertions;
  }
}

/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.spring.webflux.v5_3;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.api.incubator.semconv.http.HttpExperimentalAttributesExtractor;
import io.opentelemetry.instrumentation.api.incubator.semconv.http.HttpServerExperimentalMetrics;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.InstrumenterBuilder;
import io.opentelemetry.instrumentation.api.instrumenter.SpanNameExtractor;
import io.opentelemetry.instrumentation.api.semconv.http.HttpClientAttributesExtractorBuilder;
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerAttributesExtractor;
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerAttributesExtractorBuilder;
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerMetrics;
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRoute;
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRouteBuilder;
import io.opentelemetry.instrumentation.api.semconv.http.HttpSpanNameExtractor;
import io.opentelemetry.instrumentation.api.semconv.http.HttpSpanNameExtractorBuilder;
import io.opentelemetry.instrumentation.api.semconv.http.HttpSpanStatusExtractor;
import io.opentelemetry.instrumentation.spring.webflux.v5_3.internal.ClientInstrumenterFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.server.ServerWebExchange;

/** A builder of {@link SpringWebfluxTelemetry}. */
public final class SpringWebfluxTelemetryBuilder {
  private static final String INSTRUMENTATION_NAME = "io.opentelemetry.spring-webflux-5.3";

  private final OpenTelemetry openTelemetry;

  private final List<AttributesExtractor<ClientRequest, ClientResponse>>
      clientAdditionalExtractors = new ArrayList<>();
  private final List<AttributesExtractor<ServerWebExchange, ServerWebExchange>>
      serverAdditionalExtractors = new ArrayList<>();

  private final HttpServerAttributesExtractorBuilder<ServerWebExchange, ServerWebExchange>
      httpServerAttributesExtractorBuilder =
          HttpServerAttributesExtractor.builder(WebfluxServerHttpAttributesGetter.INSTANCE);
  private final HttpSpanNameExtractorBuilder<ServerWebExchange> httpServerSpanNameExtractorBuilder =
      HttpSpanNameExtractor.builder(WebfluxServerHttpAttributesGetter.INSTANCE);
  private final HttpServerRouteBuilder<ServerWebExchange> httpServerRouteBuilder =
      HttpServerRoute.builder(WebfluxServerHttpAttributesGetter.INSTANCE);

  private Function<
          SpanNameExtractor<ClientRequest>, ? extends SpanNameExtractor<? super ClientRequest>>
      clientSpanNameExtractorTransformer = Function.identity();
  private Function<
          SpanNameExtractor<ServerWebExchange>,
          ? extends SpanNameExtractor<? super ServerWebExchange>>
      serverSpanNameExtractorTransformer = Function.identity();

  private Consumer<HttpClientAttributesExtractorBuilder<ClientRequest, ClientResponse>>
      clientExtractorConfigurer = builder -> {};
  private Consumer<HttpSpanNameExtractorBuilder<ClientRequest>> clientSpanNameExtractorConfigurer =
      builder -> {};
  private boolean emitExperimentalHttpClientTelemetry = false;
  private boolean emitExperimentalHttpServerTelemetry = false;

  SpringWebfluxTelemetryBuilder(OpenTelemetry openTelemetry) {
    this.openTelemetry = openTelemetry;
  }

  /**
   * Adds an additional {@link AttributesExtractor} to invoke to set attributes to instrumented
   * items for WebClient.
   */
  @CanIgnoreReturnValue
  public SpringWebfluxTelemetryBuilder addClientAttributesExtractor(
      AttributesExtractor<ClientRequest, ClientResponse> attributesExtractor) {
    clientAdditionalExtractors.add(attributesExtractor);
    return this;
  }

  /**
   * Configures the HTTP WebClient request headers that will be captured as span attributes.
   *
   * @param requestHeaders A list of HTTP header names.
   */
  @CanIgnoreReturnValue
  public SpringWebfluxTelemetryBuilder setCapturedClientRequestHeaders(
      List<String> requestHeaders) {
    clientExtractorConfigurer =
        clientExtractorConfigurer.andThen(
            builder -> builder.setCapturedRequestHeaders(requestHeaders));
    return this;
  }

  /**
   * Configures the HTTP WebClient response headers that will be captured as span attributes.
   *
   * @param responseHeaders A list of HTTP header names.
   */
  @CanIgnoreReturnValue
  public SpringWebfluxTelemetryBuilder setCapturedClientResponseHeaders(
      List<String> responseHeaders) {
    clientExtractorConfigurer =
        clientExtractorConfigurer.andThen(
            builder -> builder.setCapturedResponseHeaders(responseHeaders));
    return this;
  }

  /**
   * Adds an additional {@link AttributesExtractor} to invoke to set attributes to instrumented
   * items.
   */
  @CanIgnoreReturnValue
  public SpringWebfluxTelemetryBuilder addServerAttributesExtractor(
      AttributesExtractor<ServerWebExchange, ServerWebExchange> attributesExtractor) {
    serverAdditionalExtractors.add(attributesExtractor);
    return this;
  }

  /**
   * Configures the HTTP request headers that will be captured as span attributes from server
   * instrumentation.
   *
   * @param requestHeaders A list of HTTP header names.
   */
  @CanIgnoreReturnValue
  public SpringWebfluxTelemetryBuilder setCapturedServerRequestHeaders(
      List<String> requestHeaders) {
    httpServerAttributesExtractorBuilder.setCapturedRequestHeaders(requestHeaders);
    return this;
  }

  /**
   * Configures the HTTP response headers that will be captured as span attributes from server
   * instrumentation.
   *
   * @param responseHeaders A list of HTTP header names.
   */
  @CanIgnoreReturnValue
  public SpringWebfluxTelemetryBuilder setCapturedServerResponseHeaders(
      List<String> responseHeaders) {
    httpServerAttributesExtractorBuilder.setCapturedResponseHeaders(responseHeaders);
    return this;
  }

  /**
   * Configures the instrumentation to recognize an alternative set of HTTP request methods.
   *
   * <p>By default, this instrumentation defines "known" methods as the ones listed in <a
   * href="https://www.rfc-editor.org/rfc/rfc9110.html#name-methods">RFC9110</a> and the PATCH
   * method defined in <a href="https://www.rfc-editor.org/rfc/rfc5789.html">RFC5789</a>.
   *
   * <p>Note: calling this method <b>overrides</b> the default known method sets completely; it does
   * not supplement it.
   *
   * @param knownMethods A set of recognized HTTP request methods.
   * @see HttpClientAttributesExtractorBuilder#setKnownMethods(Set)
   * @see HttpServerAttributesExtractorBuilder#setKnownMethods(Set)
   */
  @CanIgnoreReturnValue
  public SpringWebfluxTelemetryBuilder setKnownMethods(Set<String> knownMethods) {
    clientExtractorConfigurer =
        clientExtractorConfigurer.andThen(builder -> builder.setKnownMethods(knownMethods));
    clientSpanNameExtractorConfigurer =
        clientSpanNameExtractorConfigurer.andThen(builder -> builder.setKnownMethods(knownMethods));
    httpServerAttributesExtractorBuilder.setKnownMethods(knownMethods);
    httpServerSpanNameExtractorBuilder.setKnownMethods(knownMethods);
    httpServerRouteBuilder.setKnownMethods(knownMethods);
    return this;
  }

  /**
   * Configures the instrumentation to emit experimental HTTP client metrics.
   *
   * @param emitExperimentalHttpClientTelemetry {@code true} if the experimental HTTP client metrics
   *     are to be emitted.
   */
  @CanIgnoreReturnValue
  public SpringWebfluxTelemetryBuilder setEmitExperimentalHttpClientTelemetry(
      boolean emitExperimentalHttpClientTelemetry) {
    this.emitExperimentalHttpClientTelemetry = emitExperimentalHttpClientTelemetry;
    return this;
  }

  /**
   * Configures the instrumentation to emit experimental HTTP server metrics.
   *
   * @param emitExperimentalHttpServerTelemetry {@code true} if the experimental HTTP server metrics
   *     are to be emitted.
   */
  @CanIgnoreReturnValue
  public SpringWebfluxTelemetryBuilder setEmitExperimentalHttpServerTelemetry(
      boolean emitExperimentalHttpServerTelemetry) {
    this.emitExperimentalHttpServerTelemetry = emitExperimentalHttpServerTelemetry;
    return this;
  }

  /** Sets custom client {@link SpanNameExtractor} via transform function. */
  @CanIgnoreReturnValue
  public SpringWebfluxTelemetryBuilder setClientSpanNameExtractor(
      Function<SpanNameExtractor<ClientRequest>, ? extends SpanNameExtractor<? super ClientRequest>>
          clientSpanNameExtractor) {
    this.clientSpanNameExtractorTransformer = clientSpanNameExtractor;
    return this;
  }

  /** Sets custom server {@link SpanNameExtractor} via transform function. */
  @CanIgnoreReturnValue
  public SpringWebfluxTelemetryBuilder setServerSpanNameExtractor(
      Function<
              SpanNameExtractor<ServerWebExchange>,
              ? extends SpanNameExtractor<? super ServerWebExchange>>
          serverSpanNameExtractor) {
    this.serverSpanNameExtractorTransformer = serverSpanNameExtractor;
    return this;
  }

  /**
   * Returns a new {@link SpringWebfluxTelemetry} with the settings of this {@link
   * SpringWebfluxTelemetryBuilder}.
   */
  public SpringWebfluxTelemetry build() {
    Instrumenter<ClientRequest, ClientResponse> clientInstrumenter =
        ClientInstrumenterFactory.create(
            openTelemetry,
            clientExtractorConfigurer,
            clientSpanNameExtractorConfigurer,
            clientSpanNameExtractorTransformer,
            clientAdditionalExtractors,
            emitExperimentalHttpClientTelemetry);

    Instrumenter<ServerWebExchange, ServerWebExchange> serverInstrumenter =
        buildServerInstrumenter();

    return new SpringWebfluxTelemetry(
        clientInstrumenter, serverInstrumenter, openTelemetry.getPropagators());
  }

  private Instrumenter<ServerWebExchange, ServerWebExchange> buildServerInstrumenter() {
    WebfluxServerHttpAttributesGetter getter = WebfluxServerHttpAttributesGetter.INSTANCE;
    SpanNameExtractor<? super ServerWebExchange> spanNameExtractor =
        serverSpanNameExtractorTransformer.apply(httpServerSpanNameExtractorBuilder.build());

    InstrumenterBuilder<ServerWebExchange, ServerWebExchange> builder =
        Instrumenter.<ServerWebExchange, ServerWebExchange>builder(
                openTelemetry, INSTRUMENTATION_NAME, spanNameExtractor)
            .setSpanStatusExtractor(HttpSpanStatusExtractor.create(getter))
            .addAttributesExtractor(httpServerAttributesExtractorBuilder.build())
            .addAttributesExtractors(serverAdditionalExtractors)
            .addContextCustomizer(httpServerRouteBuilder.build())
            .addOperationMetrics(HttpServerMetrics.get());
    if (emitExperimentalHttpServerTelemetry) {
      builder
          .addAttributesExtractor(HttpExperimentalAttributesExtractor.create(getter))
          .addOperationMetrics(HttpServerExperimentalMetrics.get());
    }
    return builder.buildServerInstrumenter(WebfluxTextMapGetter.INSTANCE);
  }
}

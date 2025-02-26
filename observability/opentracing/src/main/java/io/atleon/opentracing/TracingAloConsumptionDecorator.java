package io.atleon.opentracing;

import io.atleon.core.Alo;
import io.atleon.core.AloDecorator;
import io.opentracing.References;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapAdapter;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Templated implementation of {@link Alo} tracing decoration for consumed data.
 *
 * @param <T> The type of data being consumed
 */
public abstract class TracingAloConsumptionDecorator<T> implements AloDecorator<T> {

    private final TracerFacade tracerFacade;

    protected TracingAloConsumptionDecorator() {
        this(GlobalTracer.get());
    }

    protected TracingAloConsumptionDecorator(Tracer tracer) {
        this.tracerFacade = TracerFacade.wrap(tracer);
    }

    @Override
    public final Alo<T> decorate(Alo<T> alo) {
        T t = alo.get();
        Tracer.SpanBuilder spanBuilder = newSpanBuilder(tracerFacade::newSpanBuilder, t)
            .withTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CONSUMER);
        deduceSpanContextToLink(t)
            .ifPresent(it -> spanBuilder.addReference(References.FOLLOWS_FROM, it));
        return TracingAlo.start(alo, tracerFacade, spanBuilder);
    }

    protected abstract Tracer.SpanBuilder newSpanBuilder(SpanBuilderFactory spanBuilderFactory, T t);

    protected final Optional<SpanContext> deduceSpanContextToLink(T t) {
        Optional<SpanContext> extractedSpanContext = extractSpanContext(t);
        Optional<SpanContext> activeSpanContext = tracerFacade.activeSpanContext();
        if (!activeSpanContext.isPresent()) {
            return extractedSpanContext;
        } else if (!extractedSpanContext.isPresent()) {
            return activeSpanContext;
        } else {
            String extractedTraceId = extractedSpanContext.get().toTraceId();
            String activeTraceId = activeSpanContext.get().toTraceId();
            return Objects.equals(extractedTraceId, activeTraceId) ? activeSpanContext : extractedSpanContext;
        }
    }

    protected final Optional<SpanContext> extractSpanContext(T t) {
        Map<String, String> headerMap = extractHeaderMap(t);
        return tracerFacade.extract(Format.Builtin.TEXT_MAP, new TextMapAdapter(headerMap));
    }

    protected abstract Map<String, String> extractHeaderMap(T t);

    @FunctionalInterface
    protected interface SpanBuilderFactory {
        Tracer.SpanBuilder newSpanBuilder(String operationName);
    }
}

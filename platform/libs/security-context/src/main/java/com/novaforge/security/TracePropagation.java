package com.novaforge.security;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

/**
 * W3C trace-context propagation across the Kafka spine (ARCHITECTURE.md §6:
 * "Kafka headers carry trace context"; PHASE-3 §4 — "via the {@code security-context}
 * constants staged in Phase 1", i.e. {@link EventHeaders#TRACEPARENT}).
 *
 * <p>The mechanics fit the transactional outbox: producers <em>capture</em> the
 * calling request's active span into a {@code traceparent} value when the outbox row
 * is appended (the request thread is where the trace lives — the relay's scheduler
 * thread has none), the value rides the payload to the relay, which lifts it into
 * the Kafka record header; consumers parse the header and open a consumer span
 * parented on it, so the consuming service's spans (and logs, through the tracing
 * bridge's MDC listener) chain onto the trace that caused the event. Every leg is
 * null-tolerant: no active span at capture, or no/invalid header at consume, simply
 * skips the link — the spine's delivery semantics never depend on tracing.</p>
 */
public final class TracePropagation {

    private TracePropagation() {
    }

    /**
     * Captures the current span as a W3C {@code traceparent} value, or {@code null}
     * when no span is active (or its context is malformed) — scheduler/engine
     * threads legitimately carry no trace.
     */
    public static String capture(Tracer tracer) {
        Span current = tracer == null ? null : tracer.currentSpan();
        if (current == null) {
            return null;
        }
        TraceContext context = current.context();
        if (context == null || !isTraceId(context.traceId()) || !isSpanId(context.spanId())) {
            return null;
        }
        return format(context.traceId(), context.spanId(),
                Boolean.TRUE.equals(context.sampled()));
    }

    /** Formats a W3C traceparent: {@code 00-&lt;traceId&gt;-&lt;spanId&gt;-&lt;flags&gt;}. */
    public static String format(String traceId, String spanId, boolean sampled) {
        return "00-" + traceId + "-" + spanId + "-" + (sampled ? "01" : "00");
    }

    /** A parsed {@code traceparent}; the parent span id links the consumer span. */
    public record RemoteParent(String traceId, String spanId, boolean sampled) {
    }

    /**
     * Parses a W3C {@code traceparent} header value; {@code null} for absent, blank,
     * or malformed input (wrong version, lengths, or non-hex digits) — a bad header
     * never fails the consume.
     */
    public static RemoteParent parse(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String value = traceparent.trim();
        if (value.length() != 55 || value.charAt(2) != '-' || value.charAt(35) != '-'
                || value.charAt(52) != '-') {
            return null;
        }
        String version = value.substring(0, 2);
        String traceId = value.substring(3, 35);
        String spanId = value.substring(36, 52);
        String flags = value.substring(53);
        if (!isHex(version) || "ff".equalsIgnoreCase(version)) {
            return null;
        }
        if (traceId.chars().allMatch(c -> c == '0') || spanId.chars().allMatch(c -> c == '0')) {
            return null;   // all-zero ids are invalid per the W3C spec
        }
        if (!isHex(traceId) || !isHex(spanId) || !isHex(flags)) {
            return null;
        }
        boolean sampled = (Integer.parseInt(flags, 16) & 0x01) == 0x01;
        return new RemoteParent(traceId, spanId, sampled);
    }

    /**
     * Opens a consumer span parented on the parsed header — the consuming service's
     * work chains onto the trace that caused the event — or returns {@code null}
     * when no link is possible. Callers hold the returned scope in
     * try-with-resources and end the span in {@code finally}.
     */
    public static ConsumerSpan startConsumerSpan(Tracer tracer, String traceparent, String name) {
        RemoteParent parent = parse(traceparent);
        if (tracer == null || parent == null) {
            return null;
        }
        TraceContext parentContext = tracer.traceContextBuilder()
                .traceId(parent.traceId())
                .spanId(parent.spanId())
                .sampled(parent.sampled())
                .build();
        Span span = tracer.spanBuilder()
                .setParent(parentContext)
                .name(name)
                .kind(Span.Kind.CONSUMER)
                .start();
        return new ConsumerSpan(tracer, span);
    }

    /** Runs {@code body} inside a consumer span linked to the header, when one parses. */
    public static <T> T inConsumerSpan(Tracer tracer, String traceparent, String name,
                                       java.util.function.Supplier<T> body) {
        ConsumerSpan linked = startConsumerSpan(tracer, traceparent, name);
        if (linked == null) {
            return body.get();
        }
        try (linked) {
            return body.get();
        } catch (Throwable t) {
            linked.span.error(t);
            throw t;
        } finally {
            linked.span.end();
        }
    }

    /** The void twin — consumers whose processing returns nothing. */
    public static void inConsumerSpan(Tracer tracer, String traceparent, String name,
                                      Runnable body) {
        inConsumerSpan(tracer, traceparent, name, () -> {
            body.run();
            return null;
        });
    }

    /** The opened span plus its scope — {@linkplain AutoCloseable} for try-with-resources. */
    public static final class ConsumerSpan implements AutoCloseable {
        private final Tracer tracer;
        final Span span;

        private ConsumerSpan(Tracer tracer, Span span) {
            this.tracer = tracer;
            this.span = span;
        }

        @Override
        public void close() {
            tracer.withSpan(span).close();
        }
    }

    private static boolean isTraceId(String id) {
        return id != null && id.length() == 32 && isHex(id);
    }

    private static boolean isSpanId(String id) {
        return id != null && id.length() == 16 && isHex(id);
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return !s.isEmpty();
    }
}

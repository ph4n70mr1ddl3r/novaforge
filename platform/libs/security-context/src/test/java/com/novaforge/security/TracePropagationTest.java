package com.novaforge.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The W3C traceparent helpers (ARCHITECTURE.md §6 / PHASE-3 §4): capture formats the
 * active span, parse accepts exactly the well-formed header shape, and anything else
 * is null — a missing or malformed header never reaches the consumer's processing as
 * an error.
 */
class TracePropagationTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @Test
    @DisplayName("format + parse round-trip, sampled flag both ways")
    void roundTrip() {
        String sampled = TracePropagation.format(TRACE_ID, SPAN_ID, true);
        String unsampled = TracePropagation.format(TRACE_ID, SPAN_ID, false);
        assertThat(sampled).isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
        assertThat(unsampled).endsWith("-00");

        TracePropagation.RemoteParent parent = TracePropagation.parse(sampled);
        assertThat(parent.traceId()).isEqualTo(TRACE_ID);
        assertThat(parent.spanId()).isEqualTo(SPAN_ID);
        assertThat(parent.sampled()).isTrue();
        assertThat(TracePropagation.parse(unsampled).sampled()).isFalse();
    }

    @Test
    @DisplayName("capture: null without an active span, null on malformed contexts")
    void captureWithoutSpan() {
        assertThat(TracePropagation.capture(null)).isNull();
        assertThat(TracePropagation.capture(io.micrometer.tracing.Tracer.NOOP)).isNull();
    }

    @Test
    @DisplayName("parse rejects absent, malformed, and W3C-invalid headers")
    void parseRejects() {
        assertThat(TracePropagation.parse(null)).isNull();
        assertThat(TracePropagation.parse("")).isNull();
        assertThat(TracePropagation.parse("   ")).isNull();
        // wrong total length
        assertThat(TracePropagation.parse("00-" + TRACE_ID + "-" + SPAN_ID)).isNull();
        // non-hex digits
        assertThat(TracePropagation.parse("00-" + TRACE_ID.replace('a', 'z') + "-" + SPAN_ID + "-01")).isNull();
        // uppercase hex is legal per the W3C spec
        assertThat(TracePropagation.parse("00-" + TRACE_ID.toUpperCase() + "-" + SPAN_ID + "-01"))
                .isNotNull();
        // all-zero ids are invalid
        assertThat(TracePropagation.parse("00-00000000000000000000000000000000-" + SPAN_ID + "-01")).isNull();
        assertThat(TracePropagation.parse("00-" + TRACE_ID + "-0000000000000000-01")).isNull();
        // forbidden version ff
        assertThat(TracePropagation.parse("ff-" + TRACE_ID + "-" + SPAN_ID + "-01")).isNull();
    }

    @Test
    @DisplayName("consumer span: no tracer or no header means no span — never a failure")
    void consumerSpanGuards() {
        assertThat(TracePropagation.startConsumerSpan(null, TracePropagation.format(
                TRACE_ID, SPAN_ID, true), "consume")).isNull();
        assertThat(TracePropagation.startConsumerSpan(
                io.micrometer.tracing.Tracer.NOOP, null, "consume")).isNull();
        assertThat(TracePropagation.startConsumerSpan(
                io.micrometer.tracing.Tracer.NOOP, "garbage", "consume")).isNull();
        // body runs unwrapped when no link is possible
        assertThat(TracePropagation.inConsumerSpan(null, null, "consume", () -> "ok")).isEqualTo("ok");
    }
}

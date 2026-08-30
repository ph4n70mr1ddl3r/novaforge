package com.novaforge.runtime.engine.sequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novaforge.metadata.SequenceDefinition;
import com.novaforge.runtime.storage.sequence.SequenceStore;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Anti-regression (found in the 2026-08-31 hunt): the cached sequence's
 * first-block anchoring clamped {@code first} up to the authored start —
 * manufacturing a born-exhausted block whenever {@code start > blockSize}
 * (the production default is 100). With {@code start: 500} every re-allocation
 * served 500 again until the Redis counter climbed past the start: duplicate
 * document numbers, the exact invariant a sequence exists to guarantee.
 *
 * <p>These tests pin that draws are strictly increasing from the authored
 * start, that concurrent claimers (two service instances sharing the counter)
 * never overlap, and that the counter only ever moves forward. The Redis
 * template is a Mockito fake backed by a real {@link AtomicLong}, so the
 * allocation arithmetic runs exactly as in production.</p>
 */
class SequenceServiceTests {

    private static final java.util.UUID TENANT =
            java.util.UUID.fromString("11111111-1111-4111-8111-111111111111");

    private SequenceStore store;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private final Map<String, AtomicLong> counters = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = mock(SequenceStore.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        // A faithful INCRBY: atomic add-and-return over a per-key counter (Redis keys
        // are independent; one shared AtomicLong would not be Redis).
        when(values.increment(anyString(), anyLong())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            assertThat(key).startsWith("novaforge:seq:");
            return counters.computeIfAbsent(key, ignored -> new AtomicLong())
                    .addAndGet(invocation.getArgument(1));
        });
    }

    private SequenceService service(long blockSize) {
        return new SequenceService(store, redis, blockSize);
    }

    private static SequenceDefinition cached(String apiName, long start) {
        return new SequenceDefinition(apiName, null, start, null, null, null);
    }

    @Test
    @DisplayName("start above the block size: draws increase strictly from start, no duplicates")
    void startAboveBlockSizeDrawsStrictlyIncreasing() {
        SequenceService service = service(100);
        SequenceDefinition sequence = cached("docNumber", 500);
        Set<String> seen = new HashSet<>();
        long previous = 0;
        for (int i = 0; i < 250; i++) {
            long drawn = Long.parseLong(service.draw(TENANT, "App", sequence));
            assertThat(drawn).as("draw %d", i).isGreaterThanOrEqualTo(500);
            assertThat(drawn).as("draw %d must advance", i).isGreaterThan(previous);
            assertThat(seen.add(String.valueOf(drawn))).as("draw %d duplicated", i).isTrue();
            previous = drawn;
        }
        // A fresh sequence (own Redis key) anchors its first draw exactly at start.
        assertThat(Long.parseLong(service.draw(TENANT, "App", cached("fresh", 500))))
                .isEqualTo(500);
    }

    @Test
    @DisplayName("two claimers sharing the counter never serve overlapping numbers")
    void concurrentClaimersNeverOverlap() {
        SequenceService a = service(100);
        SequenceService b = service(100);
        SequenceDefinition sequence = cached("docNumber", 500);
        Set<Long> served = new HashSet<>();
        for (int i = 0; i < 150; i++) {
            // Interleave the two instances (two replicas each holding their own block).
            assertThat(served.add(Long.parseLong(a.draw(TENANT, "App", sequence)))).isTrue();
            assertThat(served.add(Long.parseLong(b.draw(TENANT, "App", sequence)))).isTrue();
        }
        assertThat(served).allMatch(value -> value >= 500);
    }

    @Test
    @DisplayName("start below the block size keeps serving 1..blockSize as before")
    void smallStartUnchanged() {
        SequenceService service = service(100);
        SequenceDefinition sequence = cached("small", 1);
        assertThat(Long.parseLong(service.draw(TENANT, "App", sequence))).isEqualTo(1);
        assertThat(Long.parseLong(service.draw(TENANT, "App", sequence))).isEqualTo(2);
        assertThat(Long.parseLong(service.draw(TENANT, "App", sequence))).isEqualTo(3);
    }

    @Test
    @DisplayName("a start raised past the counter straddles without moving the counter backward")
    void raisedStartNeverServesBelowStartOrBackward() {
        // A prior life left the counter at 480 (a block [381,480] was claimed and served).
        counters.put("novaforge:seq:" + TENANT + ":App:raised", new AtomicLong(480));
        SequenceService service = service(100);
        SequenceDefinition sequence = cached("raised", 500);
        for (int i = 0; i < 120; i++) {
            long drawn = Long.parseLong(service.draw(TENANT, "App", sequence));
            assertThat(drawn).isGreaterThanOrEqualTo(500);
        }
        // The counter never moved backward past what had already been claimed.
        assertThat(counters.get("novaforge:seq:" + TENANT + ":App:raised").get())
                .isGreaterThanOrEqualTo(580);
    }
}

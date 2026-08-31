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
    @DisplayName("threads racing the last slot of a block never overlap or exceed it")
    void lastSlotRaceNeverDuplicates() throws Exception {
        // Anti-regression (2026-08-31, twelfth pass): checking exhaustion BEFORE the
        // increment let two threads both pass at current == max; the loser returned
        // max+1 — outside the window — and the next allocation served max+1 again
        // as a duplicate. The check now happens after the increment.
        SequenceService service = service(8);   // tiny blocks: constant last-slot races
        SequenceDefinition sequence = cached("racy", 1);
        int threads = 8;
        int drawsEach = 64;
        java.util.List<java.util.concurrent.Future<java.util.List<Long>>> results =
                new java.util.ArrayList<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            results.add(pool.submit(() -> {
                java.util.List<Long> mine = new java.util.ArrayList<>();
                for (int i = 0; i < drawsEach; i++) {
                    mine.add(Long.parseLong(service.draw(TENANT, "App", sequence)));
                }
                return mine;
            }));
        }
        Set<Long> all = new java.util.HashSet<>();
        long expectedMax = (long) threads * drawsEach;
        for (var future : results) {
            all.addAll(future.get());
        }

        pool.shutdown();
        assertThat(all).hasSize(threads * drawsEach);   // no duplicates across threads
        // gaps are cached mode's contract: a thread losing the last-slot race
        // discards its overflow draw and racing allocations claim whole windows, so
        // the served maximum legitimately exceeds threads×draws — uniqueness is the
        // invariant, not density. It stays bounded by the windows actually claimed.
        assertThat(java.util.Collections.max(all))
                .isLessThanOrEqualTo(expectedMax + (long) threads * drawsEach * 8);
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

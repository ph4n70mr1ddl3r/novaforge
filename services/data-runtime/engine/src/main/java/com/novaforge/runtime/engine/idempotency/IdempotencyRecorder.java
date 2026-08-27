package com.novaforge.runtime.engine.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotency-Key handling for create and batch (PHASE-1 §5, ARCHITECTURE.md §6's
 * phased bar): the first outcome is recorded in Redis keyed (tenant, actor, key)
 * with a 24 h TTL; replay returns the original outcome — and never re-draws a
 * sequence.
 *
 * <p>The claim fence (the 2025-08-27 review closed the check-then-act gap): the
 * old replay → execute → record sequence let two concurrent requests with one key
 * both execute — double writes, double sequence draws. {@link #claim} now takes the
 * key with a Redis {@code SETNX} pending marker before execution: exactly one
 * caller wins (an {@link Claim.Acquired}), a settled marker replays, and a live
 * pending marker answers {@link Claim.InFlight} (the caller renders 409 — the
 * duplicate should wait, not race). The pending marker carries a bounded TTL: a
 * crashed executor frees the key for the next attempt, and {@link #release} frees
 * it immediately on an execution failure so the client can retry without waiting
 * out the fence.</p>
 */
@Service
public class IdempotencyRecorder {

    /** The stored replay: status + serialized body of the original response. */
    public record Recorded(int status, String body) {
    }

    /** One claim's verdict — the controller branches on exactly this shape. */
    public sealed interface Claim permits Claim.Acquired, Claim.Replay, Claim.InFlight {

        /** The caller won the fence — execute, then {@link #record} (or {@link #release} on failure). */
        record Acquired() implements Claim {
        }

        /** A settled prior outcome — replay it verbatim, never re-execute. */
        record Replay(Recorded recorded) implements Claim {
        }

        /** Another request holds the pending fence — the key is in flight right now. */
        record InFlight() implements Claim {
        }
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The pre-execution fence marker — never parseable as a settled {@link Recorded}. */
    private static final String PENDING = "__pending__";

    /** Settled outcomes replay for 24 h (the §5 bar). */
    private static final Duration SETTLED_TTL = Duration.ofHours(24);

    /**
     * The execution fence: bounded so a crashed executor (OOM kill, node loss)
     * frees the key for the next attempt. Ten minutes covers the longest legal
     * create/batch (500 items with hooks) with margin.
     */
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    public IdempotencyRecorder(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Takes the key for execution (set-if-absent fence), replays a settled
     * outcome, or reports an in-flight duplicate.
     */
    public Claim claim(UUID tenantId, UUID actorId, String key) {
        String redisKey = key(tenantId, actorId, key);
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, PENDING, PENDING_TTL))) {
            return new Claim.Acquired();
        }
        String stored = redis.opsForValue().get(redisKey);
        if (stored == null) {
            // the marker expired between SETNX and GET — the fence is free again
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, PENDING, PENDING_TTL))) {
                return new Claim.Acquired();
            }
            stored = redis.opsForValue().get(redisKey);
        }
        if (stored == null || PENDING.equals(stored)) {
            return new Claim.InFlight();
        }
        return new Claim.Replay(MAPPER.readValue(stored, Recorded.class));
    }

    /** Settles the fence with the outcome — 24 h of replay, replacing the marker. */
    public void record(UUID tenantId, UUID actorId, String key, int status, String body) {
        redis.opsForValue().set(key(tenantId, actorId, key),
                MAPPER.writeValueAsString(new Recorded(status, body)), SETTLED_TTL);
    }

    /**
     * Frees the fence after a failed execution — the client may retry the same key
     * immediately instead of waiting out the pending TTL.
     */
    public void release(UUID tenantId, UUID actorId, String key) {
        redis.delete(key(tenantId, actorId, key));
    }

    private static String key(UUID tenantId, UUID actorId, String key) {
        return "novaforge:idem:" + tenantId + ":" + actorId + ":" + key;
    }
}

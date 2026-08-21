package com.novaforge.runtime.engine.idempotency;

import java.time.Duration;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotency-Key handling for create and batch (PHASE-1 §5, ARCHITECTURE.md §6's
 * phased bar): the first outcome is recorded in Redis keyed (tenant, actor, key) with a
 * 24 h TTL; replay returns the original outcome — and never re-draws a sequence.
 */
@Service
public class IdempotencyRecorder {

    /** The stored replay: status + serialized body of the original response. */
    public record Recorded(int status, String body) {
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final StringRedisTemplate redis;

    public IdempotencyRecorder(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<Recorded> replay(UUID tenantId, UUID actorId, String key) {
        String stored = redis.opsForValue().get(key(tenantId, actorId, key));
        if (stored == null) {
            return Optional.empty();
        }
        return Optional.of(MAPPER.readValue(stored, Recorded.class));
    }

    public void record(UUID tenantId, UUID actorId, String key, int status, String body) {
        redis.opsForValue().set(key(tenantId, actorId, key),
                MAPPER.writeValueAsString(new Recorded(status, body)), Duration.ofHours(24));
    }

    private static String key(UUID tenantId, UUID actorId, String key) {
        return "novaforge:idem:" + tenantId + ":" + actorId + ":" + key;
    }
}

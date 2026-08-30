package com.novaforge.runtime.engine.sequence;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.SequenceDefinition;
import com.novaforge.metadata.SequenceMode;
import com.novaforge.runtime.storage.sequence.SequenceStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Sequence execution (PHASE-1 §5): definitions are Settings metadata; execution lives
 * here (PLAN.md §3). Two modes:
 * <ul>
 *   <li>{@code cached} (default) — Redis block allocation, gaps allowed: INCRBY claims a
 *       block and an in-memory cursor serves it; Redis restarts may skip numbers, never
 *       reuse them (the counter only moves forward).
 *   <li>{@code gapless} — drawn inside the creating record's transaction via a locked
 *       counter row (serializes writers on that sequence; rollback reverts the draw —
 *       no number lost, no gap).
 * </ul>
 * The only authored surface that draws is a field {@code default} sequence reference
 * (§5 binding) — drawn once at create before validations; Idempotency-Key replay returns
 * the original outcome and never re-draws.
 */
@Service
public class SequenceService {

    private static final class Block {
        final AtomicLong current;
        final long max;

        Block(long first, long last) {
            this.current = new AtomicLong(first);
            this.max = last;
        }

        boolean exhausted() {
            return current.get() > max;
        }
    }

    private final SequenceStore store;
    private final StringRedisTemplate redis;
    private final long blockSize;

    private final Map<String, Block> blocks = new ConcurrentHashMap<>();

    public SequenceService(SequenceStore store, StringRedisTemplate redis,
                           @Value("${novaforge.sequences.block-size:100}") long blockSize) {
        this.store = store;
        this.redis = redis;
        this.blockSize = blockSize;
    }

    /** Draws the next formatted value for the sequence. */
    public String draw(UUID tenantId, String appApiName, SequenceDefinition sequence) {
        long value = switch (sequence.modeOrDefault()) {
            case GAPLESS -> store.drawGapless(tenantId, appApiName, sequence.apiName(),
                    sequence.startOrOne());
            case CACHED -> drawCached(tenantId, appApiName, sequence);
        };
        return sequence.format(value);
    }

    private long drawCached(UUID tenantId, String appApiName, SequenceDefinition sequence) {
        String key = "novaforge:seq:" + tenantId + ":" + appApiName + ":" + sequence.apiName();
        Block block = blocks.get(key);
        if (block == null || block.exhausted()) {
            long start = sequence.startOrOne();
            long last = redis.opsForValue().increment(key, blockSize);
            long first = last - blockSize + 1;
            if (last < start) {
                // The whole claimed window sits below the authored start (fresh key, or
                // the start was raised past the counter): advance the counter to
                // start-1, then claim a fresh full block there. Every served window is
                // the range of one atomic increment, so concurrent claimers never
                // overlap; the numbers skipped below start are gaps — which cached mode
                // allows — never re-served duplicates. The counter only ever moves
                // forward.
                redis.opsForValue().increment(key, start - 1 - last);
                last = redis.opsForValue().increment(key, blockSize);
                first = last - blockSize + 1;
            } else if (first < start) {
                // The window straddles the start — serve from start upward; the few
                // numbers below it inside this claim are gaps, never served.
                first = start;
            }
            if (first > last) {
                throw new IllegalStateException(
                        "sequence block " + first + "-" + last + " is empty (start " + start + ")");
            }
            block = new Block(first, last);
            blocks.put(key, block);
        }
        return block.current.getAndIncrement();
    }
}

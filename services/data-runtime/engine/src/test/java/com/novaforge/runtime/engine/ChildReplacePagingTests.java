package com.novaforge.runtime.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.runtime.authorization.RoleMatrix;
import com.novaforge.runtime.authorization.SharingGate;
import com.novaforge.runtime.engine.event.DomainEventPublisher;
import com.novaforge.runtime.engine.hook.HookExecutor;
import com.novaforge.runtime.engine.metadata.EntityResolver;
import com.novaforge.runtime.engine.metadata.EntityResolver.EntityHandle;
import com.novaforge.runtime.engine.sequence.SequenceService;
import com.novaforge.runtime.storage.record.RecordStore;
import com.novaforge.runtime.storage.record.RecordStore.PageResult;
import com.novaforge.runtime.storage.record.RecordStore.StoredRecord;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Anti-regression (found in the 2026-08-28 hunt): the engine's child walk
 * ({@code currentChildren}) rode the query DSL's default page (50 rows) — but inline
 * children are legal to 100 per request and standalone/batch writes grow a parent's
 * child set without bound. Replace-children (the update path's inline-array semantics)
 * and cascade-delete soft-deleted only page one and silently orphaned every row past
 * it, while the roll-ups kept counting the orphans (they aggregate in SQL, unwindowed).
 *
 * <p>These tests pin the walk to exhaustion: a parent with 250 children — past one
 * MAX_PAGE_SIZE (200) page, far past the 50-row default — must have every child
 * soft-deleted, both when the update path replaces the inline array and when the
 * delete path cascades. The store is a Mockito fake that honors the lowered
 * {@code LIMIT ? OFFSET ?} bind params, so the test fails exactly when the walk
 * stops at a page boundary (50 rows against the bug, 200+50 after the fix).</p>
 */
class ChildReplacePagingTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTOR = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID APP_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID PARENT = UUID.fromString("55555555-5555-4555-8555-555555555555");

    /** 250 children: past the 50-row default page and past one MAX_PAGE_SIZE page. */
    private static final int CHILD_COUNT = 250;

    private static final String APP_JSON = """
            { "apiName": "Ledger",
              "entities": [
                { "apiName": "Invoice",
                  "displayField": "ref",
                  "fields": [ { "apiName": "ref", "type": "text", "required": true } ],
                  "relationships": [
                    { "apiName": "lines", "type": "child", "target": "Line",
                      "cascadeDelete": true } ] },
                { "apiName": "Line",
                  "fields": [
                    { "apiName": "invoiceId", "type": "lookup", "target": "Invoice",
                      "required": true },
                    { "apiName": "qty", "type": "decimal", "precision": 18, "scale": 4 } ] } ] }
            """;

    private EntityResolver resolver;
    private RecordStore records;
    private RecordEngine engine;

    /** The fake projection table's live child rows, in stable (insertion) order. */
    private List<Map<String, Object>> childRows;

    /** Ids soft-deleted by the engine under test, in call order. */
    private List<UUID> deletedIds;

    /** (id, data) pairs inserted by the engine under test, in call order. */
    private List<Map.Entry<UUID, Map<String, Object>>> inserted;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        AppDefinition app = DefinitionParser.parseApp(APP_JSON);
        EntityHandle handle = new EntityHandle(APP_ID, "Ledger", 1,
                app.entity("Invoice").orElseThrow(), "Ledger.Invoice");

        resolver = Mockito.mock(EntityResolver.class);
        Mockito.when(resolver.resolve(TENANT, "Invoice")).thenReturn(handle);
        Mockito.when(resolver.bundle(TENANT, APP_ID)).thenReturn(app);

        records = Mockito.mock(RecordStore.class);

        // The fake table: the parent plus CHILD_COUNT children bound to it.
        Map<UUID, StoredRecord> table = new LinkedHashMap<>();
        table.put(PARENT, stored(PARENT, 3, Map.of("ref", "INV-1")));
        childRows = new ArrayList<>();
        for (int i = 0; i < CHILD_COUNT; i++) {
            UUID id = UUID.nameUUIDFromBytes(("line-" + i).getBytes());
            table.put(id, stored(id, 1, Map.of(
                    "invoiceId", PARENT.toString(), "qty", new BigDecimal(i + 1))));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("version", 1);
            row.put("invoiceId", PARENT.toString());
            row.put("qty", new BigDecimal(i + 1));
            childRows.add(row);
        }

        Mockito.when(records.find(any(), anyString(), any(UUID.class), anyBoolean()))
                .thenAnswer(inv -> Optional.ofNullable(table.get(inv.getArgument(2))));

        // Honors the lowered LIMIT ? OFFSET ? — the list statement's last two binds.
        Mockito.when(records.list(anyString(), anyList(), anyString(), anyList()))
                .thenAnswer(inv -> {
                    List<?> params = inv.getArgument(3);
                    int size = ((Number) params.get(params.size() - 2)).intValue();
                    long offset = ((Number) params.get(params.size() - 1)).longValue();
                    List<Map<String, Object>> window = new ArrayList<>();
                    for (long i = offset; i < Math.min(offset + size, childRows.size()); i++) {
                        window.add(childRows.get((int) i));
                    }
                    return new PageResult(window, childRows.size());
                });

        Mockito.when(records.update(any(), anyString(), any(), any(), anyInt(), any()))
                .thenReturn(4);
        Mockito.when(records.targetExists(any(), anyString(), any())).thenReturn(true);

        deletedIds = new ArrayList<>();
        Mockito.doAnswer(inv -> {
            deletedIds.add(inv.getArgument(2));
            return null;
        }).when(records).softDelete(any(), anyString(), any(), anyInt(), any());

        inserted = new ArrayList<>();
        Mockito.doAnswer(inv -> {
            inserted.add(Map.entry(inv.getArgument(2), inv.getArgument(3)));
            return null;
        }).when(records).insert(any(), anyString(), any(), any(), any());

        engine = new RecordEngine(resolver, Mockito.mock(RoleMatrix.class),
                Mockito.mock(SharingGate.class), records,
                Mockito.mock(SequenceService.class), Mockito.mock(DomainEventPublisher.class),
                Mockito.mock(HookExecutor.class),
                Mockito.mock(ObjectProvider.class));
    }

    private static StoredRecord stored(UUID id, int version, Map<String, Object> data) {
        return new StoredRecord(id, TENANT, "keyed.by.find", version,
                "2026-08-28T00:00:00.000Z", "2026-08-28T00:00:00.000Z",
                ACTOR, ACTOR, false, data);
    }

    @Test
    @DisplayName("update's inline-array replace soft-deletes every child past the page boundary")
    void replaceReplacesAllChildren() {
        Map<String, Object> body = Map.of("lines", List.of(Map.of("qty", new BigDecimal("9"))));

        engine.update(TENANT, ACTOR, "Invoice", PARENT, 3, body);

        // Replace semantics: the whole prior set goes, not just page one — 250 old
        // children must be gone, then the one new line inserted.
        assertThat(deletedIds).hasSize(CHILD_COUNT);
        assertThat(deletedIds).containsExactlyInAnyOrderElementsOf(
                childRows.stream().map(row -> (UUID) row.get("id")).toList());
        assertThat(deletedIds).doesNotContain(PARENT);
        assertThat(inserted).hasSize(1);
        assertThat(inserted.getFirst().getValue())
                .containsEntry("invoiceId", PARENT.toString())
                .containsEntry("qty", new BigDecimal("9"));
    }

    @Test
    @DisplayName("delete's cascade soft-deletes every child past the page boundary")
    void cascadeDeletesAllChildren() {
        engine.delete(TENANT, ACTOR, "Invoice", PARENT, 3);

        // The parent plus all 250 children — an orphaned child would keep matching
        // the binding filter and stay counted by every roll-up over the relationship.
        assertThat(deletedIds).hasSize(CHILD_COUNT + 1);
        assertThat(deletedIds).contains(PARENT);
        assertThat(deletedIds).containsExactlyInAnyOrderElementsOf(
                java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(PARENT),
                        childRows.stream().map(row -> (UUID) row.get("id")))
                        .toList());
        assertThat(inserted).isEmpty();
    }
}

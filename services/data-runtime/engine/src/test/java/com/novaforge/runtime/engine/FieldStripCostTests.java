package com.novaforge.runtime.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

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
 * Anti-regression (found at the 2026-08-28 PHASE-3 §11 at-scale measurement,
 * docs/loadtests/results-2026-08-28-hook-perf.md): the list path's hidden-field
 * predicate evaluated {@link RoleMatrix#fieldAccess} lazily per row × field — each
 * call a platform-store role lookup wrapped in the RLS {@code set_config} dance —
 * so a 50-row page over a 5-field entity issued ~250 role queries (~450 ms on the
 * measured box) and blew the ARCHITECTURE.md §9 list target at 1M rows. Field
 * access is row-independent: it must resolve once per request.
 *
 * <p>Pinned twice: the call count (at most once per entity field, never per row)
 * and the behavior (hidden fields still strip from every projected row; visible
 * fields survive).</p>
 */
class FieldStripCostTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTOR = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID APP_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private static final int ROWS = 50;

    /** Three fields plus a fieldSecurity entry hiding one of them from a role. */
    private static final String APP_JSON = """
            { "apiName": "Desk",
              "permissionSet": {
                "roles": [ { "name": "clerk" } ],
                "objectPermissions": [
                  { "role": "clerk", "entity": "Doc", "create": true, "read": true } ],
                "fieldSecurity": [
                  { "role": "clerk", "entity": "Doc", "field": "secret", "access": "hidden" } ] },
              "entities": [
                { "apiName": "Doc",
                  "displayField": "name",
                  "fields": [
                    { "apiName": "name", "type": "text", "required": true },
                    { "apiName": "secret", "type": "text" },
                    { "apiName": "amount", "type": "money" } ] } ] }
            """;

    private EntityResolver resolver;
    private RoleMatrix roleMatrix;
    private RecordStore records;
    private RecordEngine engine;

    private List<Map<String, Object>> rows;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        AppDefinition app = DefinitionParser.parseApp(APP_JSON);
        EntityHandle handle = new EntityHandle(APP_ID, "Desk", 1,
                app.entity("Doc").orElseThrow(), "Desk.Doc");

        resolver = Mockito.mock(EntityResolver.class);
        Mockito.when(resolver.resolve(TENANT, "Doc")).thenReturn(handle);
        Mockito.when(resolver.bundle(TENANT, APP_ID)).thenReturn(app);

        roleMatrix = Mockito.mock(RoleMatrix.class);
        Mockito.doNothing().when(roleMatrix).require(any(), any(), any(), anyString(),
                anyString(), any());
        // The shape under test: fieldAccess answers per field (hidden for "secret",
        // visible otherwise) — the regression made this run per row × field.
        Mockito.when(roleMatrix.fieldAccess(any(), any(), anyString(), any(), anyString(),
                anyString()))
                .thenAnswer(inv -> "secret".equals(inv.getArgument(5))
                        ? com.novaforge.metadata.PermissionSet.FieldSecurity.HIDDEN
                        : "visible");

        rows = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", UUID.nameUUIDFromBytes(("doc-" + i).getBytes()));
            row.put("version", 1);
            row.put("name", "doc-" + i);
            row.put("secret", "hush-" + i);
            row.put("amount", "1.00");
            rows.add(row);
        }

        records = Mockito.mock(RecordStore.class);
        Mockito.when(records.list(anyString(), anyList(), anyString(), anyList()))
                .thenReturn(new PageResult(new ArrayList<>(rows), ROWS));
        Mockito.when(records.find(any(), anyString(), any(UUID.class), anyBoolean()))
                .thenAnswer(inv -> Optional.of(new StoredRecord(
                        (UUID) inv.getArgument(2), TENANT, "Desk.Doc", 1,
                        "2026-08-28T00:00:00.000Z", "2026-08-28T00:00:00.000Z",
                        ACTOR, ACTOR, false, Map.of())));

        engine = new RecordEngine(resolver, roleMatrix,
                Mockito.mock(SharingGate.class), records,
                Mockito.mock(SequenceService.class), Mockito.mock(DomainEventPublisher.class),
                Mockito.mock(HookExecutor.class),
                Mockito.mock(ObjectProvider.class));
    }

    @Test
    @DisplayName("list resolves field security once per request, never per row × field")
    void fieldSecurityResolvesOncePerRequest() {
        var result = engine.list(TENANT, ACTOR, "Doc", "{}");

        assertThat(result.rows()).hasSize(ROWS);
        // 3 entity fields → at most 3 fieldAccess calls. Against the bug this was
        // ROWS × 3 (150) — every projected key tested through a fresh role lookup.
        Mockito.verify(roleMatrix, Mockito.atMost(3)).fieldAccess(any(), any(), anyString(),
                any(), anyString(), anyString());
    }

    @Test
    @DisplayName("hidden fields still strip from every row; visible fields survive")
    void hiddenFieldsStillStrip() {
        var result = engine.list(TENANT, ACTOR, "Doc", "{}");

        assertThat(result.rows()).hasSize(ROWS);
        for (Map<String, Object> row : result.rows()) {
            assertThat(row).doesNotContainKey("secret");
            assertThat(row).containsKey("name");
            assertThat(row).containsKey("amount");
        }
        // The hidden set is precomputed per request — the row count never enters
        // the verification shape, but the strip must hold for every row.
        Mockito.verify(roleMatrix, Mockito.atMost(3)).fieldAccess(any(), any(), anyString(),
                any(), eq("Doc"), anyString());
    }
}

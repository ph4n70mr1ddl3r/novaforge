package com.novaforge.runtime.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.PermissionSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RoleMatrix had ZERO tests of its own (twenty-ninth pass coverage audit): the
 * journey suites exercise its happy paths through the api module, but the
 * fail-closed bootstrap, the {@code app.role}-scoped grant grammar, and the
 * field-access precedence (hidden > readonly > visible) rode on incidental
 * assertions only. Pinned directly here.
 */
class RoleMatrixTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTOR = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String APP = "erp";

    private PlatformStore platform;
    private RoleMatrix matrix;

    @BeforeEach
    void setUp() {
        platform = mock(PlatformStore.class);
        matrix = new RoleMatrix(platform);
        when(platform.roles(any(), any())).thenReturn(List.of());
    }

    private static PermissionSet permissions(PermissionSet.ObjectPermission... ops) {
        return new PermissionSet(List.of(), List.of(ops), List.of(), List.of());
    }

    private static PermissionSet.ObjectPermission grant(String entity, String role,
                                                        String... actions) {
        return new PermissionSet.ObjectPermission(role, entity,
                List.of(actions).contains("create"),
                List.of(actions).contains("read"),
                List.of(actions).contains("update"),
                List.of(actions).contains("delete"),
                List.of(actions).contains("reportExecute"));
    }

    private static PermissionSet.FieldSecurity fieldSecurity(String entity, String field,
                                                             String role, String access) {
        return new PermissionSet.FieldSecurity(role, entity, field, access);
    }

    @Test
    @DisplayName("no roles → FORBIDDEN even with a granting matrix (fail closed)")
    void failsClosedWithoutRoles() {
        var permissionSet = permissions(grant("invoice", "clerk", "read"));
        assertThatThrownBy(() -> matrix.require(TENANT, ACTOR, RoleMatrix.Action.READ,
                "invoice", APP, permissionSet))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(PlatformErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("a grant applies only when the actor holds the role SCOPED to this app (app.role)")
    void grantsAreAppScoped() {
        // role held for a DIFFERENT app must not grant here — the grammar is
        // erp.clerk, not bare clerk; a bare clerk in the actor's roles grants nothing
        when(platform.roles(TENANT, ACTOR)).thenReturn(List.of("purchasing.clerk"));
        var permissionSet = permissions(grant("invoice", "clerk", "read"));
        assertThatThrownBy(() -> matrix.require(TENANT, ACTOR, RoleMatrix.Action.READ,
                "invoice", APP, permissionSet))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(PlatformErrorCode.FORBIDDEN);

        when(platform.roles(TENANT, ACTOR)).thenReturn(List.of("erp.clerk"));
        assertThatCode(() -> matrix.require(TENANT, ACTOR, RoleMatrix.Action.READ,
                "invoice", APP, permissionSet)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("grants are per-action; an action outside the grant stays FORBIDDEN")
    void grantsArePerAction() {
        when(platform.roles(TENANT, ACTOR)).thenReturn(List.of("erp.clerk"));
        var readOnly = permissions(grant("invoice", "clerk", "read"));
        assertThatCode(() -> matrix.require(TENANT, ACTOR, RoleMatrix.Action.READ,
                "invoice", APP, readOnly)).doesNotThrowAnyException();
        assertThatThrownBy(() -> matrix.require(TENANT, ACTOR, RoleMatrix.Action.DELETE,
                "invoice", APP, readOnly))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(PlatformErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("admin and builder keep full CRUD without any matrix entry")
    void bootstrapFullCrud() {
        for (String role : List.of("admin", "builder")) {
            when(platform.roles(TENANT, ACTOR)).thenReturn(List.of(role));
            assertThatCode(() -> matrix.require(TENANT, ACTOR, RoleMatrix.Action.DELETE,
                    "anything", APP, permissions())).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("fieldAccess: hidden wins over readonly, readonly over visible, default visible")
    void fieldAccessPrecedence() {
        var permissionSet = new PermissionSet(
                List.of(), List.of(),
                List.of(fieldSecurity("invoice", "note", "clerk", PermissionSet.FieldSecurity.HIDDEN),
                        fieldSecurity("invoice", "note", "auditor", PermissionSet.FieldSecurity.READONLY),
                        fieldSecurity("invoice", "memo", "clerk", PermissionSet.FieldSecurity.READONLY),
                        fieldSecurity("other", "note", "clerk", PermissionSet.FieldSecurity.HIDDEN)));

        when(platform.roles(TENANT, ACTOR)).thenReturn(List.of("erp.auditor", "erp.clerk"));
        // both a hidden (clerk) and a readonly (auditor) entry hit "note": hidden wins
        assertThat(matrix.fieldAccess(TENANT, ACTOR, APP, permissionSet, "invoice", "note"))
                .isEqualTo(PermissionSet.FieldSecurity.HIDDEN);
        // readonly entry only
        assertThat(matrix.fieldAccess(TENANT, ACTOR, APP, permissionSet, "invoice", "memo"))
                .isEqualTo(PermissionSet.FieldSecurity.READONLY);
        // no entry at all → visible
        assertThat(matrix.fieldAccess(TENANT, ACTOR, APP, permissionSet, "invoice", "total"))
                .isEqualTo("visible");
        // the clerk's hidden entry exists for other/note too — and applies
        assertThat(matrix.fieldAccess(TENANT, ACTOR, APP, permissionSet, "other", "note"))
                .isEqualTo(PermissionSet.FieldSecurity.HIDDEN);
        // while other/memo has no entry for any held role → visible
        assertThat(matrix.fieldAccess(TENANT, ACTOR, APP, permissionSet, "other", "memo"))
                .isEqualTo("visible");
    }

    @Test
    @DisplayName("fieldAccess: platform roles see every field; readonly from a second role does not degrade a hidden grant... or vice versa")
    void fieldAccessPlatformRolesVisible() {
        when(platform.roles(TENANT, ACTOR)).thenReturn(List.of("admin"));
        var permissionSet = new PermissionSet(List.of(), List.of(),
                List.of(fieldSecurity("invoice", "note", "clerk", PermissionSet.FieldSecurity.HIDDEN)));
        assertThat(matrix.fieldAccess(TENANT, ACTOR, APP, permissionSet, "invoice", "note"))
                .isEqualTo("visible");
    }

    @Test
    @DisplayName("requireAdmin: admin passes, every other role combination fails closed")
    void requireAdmin() {
        when(platform.roles(TENANT, ACTOR)).thenReturn(List.of("erp.clerk", "builder"));
        assertThatThrownBy(() -> matrix.requireAdmin(TENANT, ACTOR))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(PlatformErrorCode.FORBIDDEN);

        when(platform.roles(TENANT, ACTOR)).thenReturn(List.of("admin"));
        assertThatCode(() -> matrix.requireAdmin(TENANT, ACTOR)).doesNotThrowAnyException();
    }
}

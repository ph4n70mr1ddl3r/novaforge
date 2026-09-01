package com.novaforge.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.EntityDefinition;
import com.novaforge.metadata.FieldDefinition;
import com.novaforge.metadata.FieldType;
import com.novaforge.metadata.ReportDefinition;
import com.novaforge.reporting.export.ReportExporter;
import com.novaforge.reporting.notify.DeliveryClient;
import com.novaforge.reporting.run.ReportRunner;
import com.novaforge.reporting.source.PublishedApps.PublishedApp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The Scheduler's delivery surface had ZERO tests (twenty-ninth pass coverage
 * audit): {@code /api/v1/reports/internal} is the one controller URL family no
 * suite ever hit, so the whole scheduled-report pipeline — recipient role
 * expansion to {@code app.role}, the runAsActor-vs-runAsRole scoping split,
 * format and recipient validation — was invisible to CI. A regression here
 * ships silently: scheduled reports run under the wrong scope, or notifications
 * go to nobody because the app prefix never joined the role.
 */
class InternalDeliveryControllerTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTOR = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private ReportRunner runner;
    private DeliveryClient delivery;
    private InternalDeliveryController controller;

    @BeforeEach
    void setUp() {
        runner = mock(ReportRunner.class);
        delivery = mock(DeliveryClient.class);
        controller = new InternalDeliveryController(runner, new ReportExporter("USD"), delivery);

        var entity = new EntityDefinition("e1", "invoice", "Invoice", null, null, null,
                null, null,
                List.of(FieldDefinition.of("total", FieldType.MONEY)),
                null, null, null, null);
        var app = new AppDefinition("app-1", "erp", "ERP", null, null,
                List.of(entity), null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        var report = new ReportDefinition("r1", "invoice", "Revenue", null, null, null,
                List.of(new ReportDefinition.AggregateField("sum", "total", null)), null);
        var resolved = new ReportRunner.Resolved(
                new PublishedApp(TENANT, "app-1", "erp", 1, app), report);
        when(runner.resolve(TENANT, "erp", "r1")).thenReturn(resolved);
        when(runner.runScheduled(eq(TENANT), eq("erp"), eq("r1"), anyString(), any()))
                .thenReturn(Map.of("columns", List.of("sum_total"),
                "rows", List.of(Map.of("sum_total", "10.00"),
                        Map.of("sum_total", "20.00"))));
        when(runner.runAsActor(eq(TENANT), eq(ACTOR), eq("erp"), eq("r1"), any()))
                .thenReturn(Map.of("columns", List.of("sum_total"),
                "rows", List.of(Map.of("sum_total", "30.00"))));
        when(delivery.deliver(any(), anyString(), anyString(), any(), any(), anyString(), any(),
                any())).thenReturn(Map.of("notificationId", "n-1"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAsServiceClient() {
        Jwt jwt = new Jwt("token", Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                Map.of("alg", "none"), Map.of("azp", "novaforge-runtime"));
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("SCOPE_x")));
    }

    private static InternalDeliveryController.DeliveryRequest request(Map<String, Object> over) {
        return new InternalDeliveryController.DeliveryRequest(
                (String) over.getOrDefault("tenantId", TENANT.toString()),
                (String) over.getOrDefault("app", "erp"),
                (String) over.getOrDefault("reportId", "r1"),
                (Map<String, Object>) over.getOrDefault("params", Map.of()),
                (String) over.get("runAsRole"),
                (String) over.get("runAsActor"),
                (Map<String, Object>) over.getOrDefault("recipients",
                        Map.of("roles", List.of("clerk"))),
                (String) over.get("format"),
                (String) over.get("deliveryId"));
    }

    @Test
    @DisplayName("deliver: bare authored roles expand to app.role; users pass through; csv default")
    void deliverExpandsRoles() {
        authenticateAsServiceClient();
        when(delivery.deliver(eq(TENANT), eq("r1"), eq("erp"),
                eq(List.of("erp.clerk")), eq(List.of("u-1")), eq("csv"), any(), eq("d-1")))
                .thenReturn(Map.of("notificationId", "n-1"));

        Map<String, Object> summary = controller.deliver(request(Map.of(
                "recipients", Map.of("roles", List.of("clerk"), "users", List.of("u-1")),
                "deliveryId", "d-1")));

        assertThat(summary).containsEntry("status", "delivered")
                .containsEntry("rows", 2)
                .containsEntry("format", "csv")
                .containsEntry("runAsRole", "reporting");
        verify(delivery).deliver(eq(TENANT), eq("r1"), eq("erp"), eq(List.of("erp.clerk")),
                eq(List.of("u-1")), eq("csv"), any(), eq("d-1"));
    }

    @Test
    @DisplayName("deliver: an already-qualified role (app.role) is NOT double-prefixed")
    void deliverKeepsQualifiedRoles() {
        authenticateAsServiceClient();
        when(delivery.deliver(any(), anyString(), anyString(), eq(List.of("erp.clerk")),
                any(), anyString(), any(), any())).thenReturn(Map.of());

        controller.deliver(request(Map.of(
                "recipients", Map.of("roles", List.of("erp.clerk")))));
        verify(delivery).deliver(any(), anyString(), anyString(), eq(List.of("erp.clerk")),
                any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("deliver: missing recipients, bad format, or a malformed tenantId all VALIDATION_FAILED")
    void deliverValidation() {
        authenticateAsServiceClient();
        assertThatThrownBy(() -> controller.deliver(request(Map.of(
                "recipients", Map.of("roles", List.of())))))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(PlatformErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> controller.deliver(request(Map.of("format", "pdf"))))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(PlatformErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> controller.deliver(request(Map.of("tenantId", "not-a-uuid"))))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(PlatformErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> controller.deliver(new InternalDeliveryController.DeliveryRequest(
                        TENANT.toString(), null, "r1", Map.of(), null, null,
                        Map.of("roles", List.of("clerk")), null, null)))
                .isInstanceOf(PlatformException.class);
        verify(delivery, never()).deliver(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("export with runAsActor: runs under the ACTOR's scopes, never a re-scoped role")
    void exportUnderActorScopes() {
        authenticateAsServiceClient();
        Map<String, Object> summary = controller.export(request(Map.of(
                "runAsActor", ACTOR.toString(), "recipients", Map.of())));

        verify(runner).runAsActor(eq(TENANT), eq(ACTOR), eq("erp"), eq("r1"), any());
        verify(runner, never()).runScheduled(any(), any(), any(), any(), any());
        // bytes back, no delivery
        verify(delivery, never()).deliver(any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(summary).containsEntry("runAsRole", "actor:" + ACTOR)
                .containsEntry("rows", 1)
                .containsEntry("format", "csv");
        byte[] bytes = java.util.Base64.getDecoder()
                .decode((String) summary.get("contentBase64"));
        assertThat(bytes).isNotEmpty();
        assertThat(new String(bytes)).contains("30.00");
    }

    @Test
    @DisplayName("export without runAsActor: the job's runAsRole scopes the run")
    void exportUnderRoleScopes() {
        authenticateAsServiceClient();
        Map<String, Object> summary = controller.export(request(Map.of(
                "runAsRole", "auditor", "recipients", Map.of())));

        verify(runner).runScheduled(eq(TENANT), eq("erp"), eq("r1"), eq("auditor"), any());
        assertThat(summary).containsEntry("runAsRole", "auditor");
    }

    @Test
    @DisplayName("export: a malformed runAsActor VALIDATION_FAILEDs instead of scoping")
    void exportValidatesActor() {
        authenticateAsServiceClient();
        assertThatThrownBy(() -> controller.export(request(Map.of("runAsActor", "bob"))))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(PlatformErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("both surfaces are service-client only: a user context is FORBIDDEN before any run")
    void gateRejectsUserTraffic() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", "n", AuthorityUtils.createAuthorityList("ROLE_x")));
        assertThatThrownBy(() -> controller.deliver(request(Map.of())))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(PlatformErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> controller.export(request(Map.of())))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(PlatformErrorCode.FORBIDDEN);
        verify(runner, never()).resolve(any(), any(), any());
    }
}

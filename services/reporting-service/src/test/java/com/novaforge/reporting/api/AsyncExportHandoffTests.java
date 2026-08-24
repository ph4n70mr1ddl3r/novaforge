package com.novaforge.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novaforge.common.context.TenantContext;
import com.novaforge.metadata.ReportDefinition;
import com.novaforge.reporting.export.AsyncExportClient;
import com.novaforge.reporting.export.ReportExporter;
import com.novaforge.reporting.run.ReportRunner;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * PHASE-6 §7 activates PHASE-5 §6's designed handoff: an export over the 10k cap
 * no longer rejects with guidance — the endpoint creates the async export job
 * (service-gated surface on the Integration Service) and answers 202 with the
 * job link, rows, and completion-notification detail.
 */
class AsyncExportHandoffTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTOR = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private final ReportRunner runner = mock(ReportRunner.class);
    private final ReportExporter exporter = mock(ReportExporter.class);
    private final AsyncExportClient asyncExports = mock(AsyncExportClient.class);

    @BeforeEach
    void bindCaller() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer caller-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        TenantContext.set(new TenantContext.Context(TENANT.toString(), ACTOR.toString()));
    }

    @AfterEach
    void unbind() {
        RequestContextHolder.resetRequestAttributes();
        TenantContext.clear();
    }

    /** A minimal resolved bundle: an app with no entities, a report with no aggregates. */
    private static ReportRunner.Resolved resolved() {
        com.novaforge.metadata.AppDefinition app = com.novaforge.metadata.DefinitionParser
                .parseApp("{ \"apiName\": \"Erp\" }");
        com.novaforge.reporting.source.PublishedApps.PublishedApp published =
                new com.novaforge.reporting.source.PublishedApps.PublishedApp(TENANT,
                        "appId", "Erp", 1, app);
        return new ReportRunner.Resolved(published,
                com.novaforge.metadata.DefinitionParser.parse(
                        "{ \"id\": \"arAging\", \"entity\": \"Customer\" }",
                        ReportDefinition.class));
    }

    private ReportController controller(long cap) {
        return new ReportController(runner, exporter, asyncExports, cap);
    }

    @Test
    @DisplayName("over-cap exports answer 202 with the async job link (the §6 handoff, wired)")
    void overCapCreatesAsyncJob() {
        ReportRunner.Resolved resolved = resolved();
        when(runner.resolve(any(UUID.class), anyString(), anyString())).thenReturn(resolved);
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(Map.of("customer", "acme-" + i));
        }
        when(runner.exportRows(any(UUID.class), any(UUID.class), anyString(), anyString(),
                any(), anyString())).thenReturn(Map.of("rows", rows));
        UUID job = UUID.randomUUID();
        when(asyncExports.create(eq(TENANT), anyString(), anyString(), anyString(), any(),
                eq(ACTOR))).thenReturn(new AsyncExportClient.AsyncJob(job,
                        "/api/v1/integrations/jobs/" + job));

        var response = controller(2).export("arAging", "Erp", "csv", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getLocation().toString())
                .isEqualTo("/api/v1/integrations/jobs/" + job);
        Map<String, Object> body = tools.jackson.databind.json.JsonMapper.builder()
                .build().readValue(response.getBody(), Map.class);
        assertThat(body.get("status")).isEqualTo("accepted");
        assertThat(((Number) body.get("rows")).longValue()).isEqualTo(3L);
        assertThat(String.valueOf(body.get("jobId"))).isEqualTo(job.toString());
        assertThat(String.valueOf(body.get("detail"))).contains("capped at 2 rows");
    }

    @Test
    @DisplayName("within-cap exports still stream synchronously")
    void withinCapStreams() {
        ReportRunner.Resolved resolved = resolved();
        when(runner.resolve(any(UUID.class), anyString(), anyString())).thenReturn(resolved);
        List<Map<String, Object>> rows = List.of(Map.of("customer", "acme"));
        when(runner.exportRows(any(UUID.class), any(UUID.class), anyString(), anyString(),
                any(), anyString())).thenReturn(Map.of("rows", rows));

        var response = controller(10_000).export("arAging", "Erp", "csv", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("arAging.csv");
    }
}

package com.novaforge.workflow.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The workflow service's process-side bindings had ZERO tests (twenty-ninth pass
 * coverage audit). RestPublishedWorkflowSource: the cross-tenant index fans out to
 * per-app bundles, parsing workflows and SKIPPING malformed rows defensively.
 * RestRecordFieldsSource: the event-start's record read — 404 means "record gone,
 * evaluation skips" (null), everything else fails audibly.
 */
class WorkflowProcessRestAdapterTests {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID APP_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID RECORD = UUID.fromString("33333333-3333-4333-8333-333333333333");

    static volatile int recordStatus = 200;
    static volatile int syncStatus = 200;
    static volatile String indexBody = "[{\"tenantId\": \"" + TENANT + "\", \"appId\": \""
            + APP_ID + "\", \"apiName\": \"erp\", \"version\": 1}]";
    static volatile String bundleBody = "{\"version\": 1, \"app\": {\"apiName\": \"erp\","
            + " \"workflows\": [{\"id\": \"wf-1\", \"bpmn\": \"<xml/>\"}]}}";
    static volatile String recordBody = "{\"name\": \"Acme\"}";
    static final AtomicReference<String> lastAuth = new AtomicReference<>();
    static final AtomicReference<String> lastPath = new AtomicReference<>();

    private static HttpServer stub;

    @BeforeAll
    static void stubUpstreams() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/v1/metadata/published-apps", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPath.set(exchange.getRequestURI().getPath());
            respond(exchange, indexBody, syncStatus);
        });
        stub.createContext("/api/v1/metadata/apps/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            respond(exchange, bundleBody, syncStatus);
        });
        stub.createContext("/api/v1/hooks/records/", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPath.set(exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getQuery());
            respond(exchange, recordStatus == 404 ? "{\"detail\": \"gone\"}" : recordBody,
                    recordStatus);
        });
        stub.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body,
                                int status) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, Math.max(0, bytes.length));
        try (OutputStream out = exchange.getResponseBody()) {
            if (bytes.length > 0) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @AfterAll
    static void stop() {
        stub.stop(0);
    }

    private static ServiceTokenClient serviceToken() {
        ServiceTokenClient token = mock(ServiceTokenClient.class);
        when(token.token()).thenReturn("svc-token-1");
        return token;
    }

    private String base() {
        return "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    @Test
    @DisplayName("the workflow sync fans the index out to bundles and parses workflows")
    void workflowSyncParses() {
        RestPublishedWorkflowSource source = new RestPublishedWorkflowSource(base(), serviceToken());
        List<PublishedWorkflowSource.AppWorkflows> all = source.all();

        assertThat(lastAuth.get()).isEqualTo("Bearer svc-token-1");
        assertThat(all).hasSize(1);
        assertThat(all.get(0).tenantId()).isEqualTo(TENANT);
        assertThat(all.get(0).appApiName()).isEqualTo("erp");
        assertThat(all.get(0).workflows()).hasSize(1);
        assertThat(all.get(0).workflows().get(0).id()).isEqualTo("wf-1");
    }

    @Test
    @DisplayName("a broken upstream fails the workflow sync audibly")
    void workflowSyncFailureSurfaces() {
        syncStatus = 500;
        try {
            RestPublishedWorkflowSource source =
                    new RestPublishedWorkflowSource(base(), serviceToken());
            assertThatThrownBy(source::all)
                    .isInstanceOf(PlatformException.class)
                    .satisfies(e -> assertThat(((PlatformException) e).errorCode())
                            .isEqualTo(PlatformErrorCode.INTERNAL));
        } finally {
            syncStatus = 200;
        }
    }

    @Test
    @DisplayName("the record-fields read splits the entity key and rides the service client")
    void recordFieldsRead() {
        RestRecordFieldsSource source = new RestRecordFieldsSource(base(), serviceToken());
        Map<String, Object> fields = source.fields(TENANT, "erp", "invoice", RECORD);

        assertThat(lastPath.get()).isEqualTo("/api/v1/hooks/records/" + RECORD
                + "?tenantId=" + TENANT + "&app=erp&entity=invoice");
        assertThat(fields).containsEntry("name", "Acme");
    }

    @Test
    @DisplayName("a gone record reads as null — the evaluation skips, no audible failure")
    void goneRecordSkips() {
        recordStatus = 404;
        try {
            RestRecordFieldsSource source = new RestRecordFieldsSource(base(), serviceToken());
            assertThat(source.fields(TENANT, "erp", "invoice", RECORD)).isNull();
        } finally {
            recordStatus = 200;
        }
    }

    @Test
    @DisplayName("a non-404 record-fetch failure fails audibly")
    void recordFetchFailureSurfaces() {
        recordStatus = 500;
        try {
            RestRecordFieldsSource source = new RestRecordFieldsSource(base(), serviceToken());
            assertThatThrownBy(() -> source.fields(TENANT, "erp", "invoice", RECORD))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(e -> assertThat(((PlatformException) e).errorCode())
                            .isEqualTo(PlatformErrorCode.INTERNAL));
        } finally {
            recordStatus = 200;
        }
    }
}

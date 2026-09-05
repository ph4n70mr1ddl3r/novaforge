package com.novaforge.e2e;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole-platform e2e stack (one per test JVM): Testcontainers bring up the same
 * infrastructure images the compose stack pins (Postgres, Redis, Kafka, Keycloak with
 * the realm import), and every service boots from its packaged Spring Boot jar exactly
 * as the README's host-JVM bring-up runs it — same defaults, same ports, same
 * cross-service URLs. The cycles then drive the real topology through the public APIs
 * only: the admin surface, the runtime write path, the workflow inbox, the report run
 * surface, and the builder's suite-run API.
 *
 * <p>Podman rootless: export {@code DOCKER_HOST=unix:///run/user/<uid>/podman/podman.sock}
 * and {@code TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/<uid>/podman/podman.sock}
 * (the README documents the same contract for the per-module Testcontainers suites).
 */
public final class NovaForgeStack {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static volatile NovaForgeStack instance;

    /** The stack singleton — booted once per test JVM, reaped by Ryuk + shutdown hooks. */
    public static NovaForgeStack stack() {
        if (instance == null) {
            synchronized (NovaForgeStack.class) {
                if (instance == null) {
                    instance = start();
                }
            }
        }
        return instance;
    }

    // --- infrastructure (image pins mirror deploy/compose/novaforge.yaml) ---

    private final PostgreSQLContainer<?> postgres;
    private final GenericContainer<?> redis;
    private final KafkaContainer kafka;
    private final GenericContainer<?> keycloak;
    private final Map<String, Process> services = new LinkedHashMap<>();
    private final Path repoRoot = repoRoot();
    private final Path logDir;

    /** The init/probe connection to the container's postgres (novaforge superuser). */
    private java.sql.Connection db;
    /** The data runtime's database — where the tenant-shared projections live. */
    private java.sql.Connection dataDb;

    private NovaForgeStack(PostgreSQLContainer<?> postgres, GenericContainer<?> redis,
                           KafkaContainer kafka, GenericContainer<?> keycloak) {
        this.postgres = postgres;
        this.redis = redis;
        this.kafka = kafka;
        this.keycloak = keycloak;
        this.logDir = repoRoot.resolve("e2e-tests").resolve("target").resolve("e2e-logs");
    }

    // --- service coordinates (the documented defaults) ---

    private static final String METADATA = "metadata-service";
    private static final String RUNTIME = "data-runtime";
    private static final String SCRIPT = "script-engine";
    private static final String AUDIT = "audit-service";
    private static final String WORKFLOW = "workflow-service";
    private static final String NOTIFICATION = "notification-service";
    private static final String REPORTING = "reporting-service";
    private static final String INTEGRATION = "integration-service";

    /** module dir under services/ (data-runtime is a nested aggregator), port. */
    private static final Map<String, String> SERVICE_MODULES = Map.of(
            METADATA, "metadata-service",
            RUNTIME, "data-runtime/api",
            SCRIPT, "script-engine",
            AUDIT, "audit-service",
            WORKFLOW, "workflow-service",
            NOTIFICATION, "notification-service",
            REPORTING, "reporting-service",
            INTEGRATION, "integration-service");

    private static final Map<String, Integer> SERVICE_PORTS = Map.of(
            METADATA, 8081,
            RUNTIME, 8083,
            SCRIPT, 8084,
            AUDIT, 8085,
            WORKFLOW, 8086,
            NOTIFICATION, 8088,
            REPORTING, 8089,
            INTEGRATION, 8090);

    private static NovaForgeStack start() {
        preflightPorts(SERVICE_PORTS.values());
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                        DockerImageName.parse("docker.io/library/postgres:16.15")
                                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("novaforge_e2e")
                .withUsername("novaforge")
                .withPassword("novaforge")
                .withCommand("postgres", "-c", "max_connections=400");
        postgres.start();
        GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("docker.io/library/redis:7.4.11"))
                .withExposedPorts(6379);
        redis.start();
        KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.1");
        kafka.start();
        GenericContainer<?> keycloak = new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.7.2"))
                .withCommand("start-dev", "--import-realm")
                .withEnv("KC_HOSTNAME_STRICT", "false")
                .withEnv("KC_HTTP_ENABLED", "true")
                .withExposedPorts(8080)
                // the realm import minus the novaforge-auth event listener (that
                // provider ships in the auth-listener deploy artifact and only feeds
                // the auth.* spine — no e2e cycle reads it)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("e2e/novaforge-realm.json"),
                        "/opt/keycloak/data/import/novaforge-realm.json");
        keycloak.start();

        NovaForgeStack stack = new NovaForgeStack(postgres, redis, kafka, keycloak);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stack.stopServices()));
        stack.initPostgresDatabases();
        stack.awaitKeycloak();
        stack.startServices();
        return stack;
    }

    /** The per-service databases + roles (deploy/postgres-init/01-databases.sh's shape). */
    private void initPostgresDatabases() {
        String[] statements = {
                "CREATE DATABASE novaforge_metadata OWNER novaforge;",
                "CREATE DATABASE novaforge_data OWNER novaforge;",
                "CREATE DATABASE novaforge_audit OWNER novaforge;",
                "CREATE DATABASE novaforge_workflow OWNER novaforge;",
                "CREATE DATABASE novaforge_notification OWNER novaforge;",
                "CREATE DATABASE novaforge_integration OWNER novaforge;",
                "ALTER ROLE novaforge BYPASSRLS;",
                "CREATE ROLE novaforge_audit_app WITH LOGIN PASSWORD 'novaforge';",
                "GRANT CONNECT ON DATABASE novaforge_audit TO novaforge_audit_app;"};
        try {
            org.testcontainers.containers.wait.strategy.Wait.forListeningPort()
                    .waitUntilReady(postgres);
            db = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
            for (String statement : statements) {
                try (var stmt = db.createStatement()) {
                    stmt.execute(statement);
                }
            }
            // only after the init loop has created the data runtime's database
            String dataUrl = postgres.getJdbcUrl().replaceFirst("/[^/?]+($|\\?.*)", "/novaforge_data");
            dataDb = java.sql.DriverManager.getConnection(dataUrl,
                    postgres.getUsername(), postgres.getPassword());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("postgres init failed", e);
        }
    }

    private void awaitKeycloak() {
        String realm = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080)
                + "/realms/novaforge";
        await(240, "Keycloak realm import", () -> get200(realm));
    }

    // --- service processes ---

    private void startServices() {
        Map<String, String> common = new LinkedHashMap<>();
        common.put("NOVAFORGE_POSTGRES_HOST", "127.0.0.1");
        common.put("NOVAFORGE_POSTGRES_PORT", String.valueOf(postgres.getMappedPort(5432)));
        common.put("NOVAFORGE_KAFKA", kafka.getBootstrapServers());
        common.put("NOVAFORGE_REDIS_HOST", "127.0.0.1");
        common.put("NOVAFORGE_REDIS_PORT", String.valueOf(redis.getMappedPort(6379)));
        common.put("NOVAFORGE_AUTH_ISSUER", issuer());
        common.put("NOVAFORGE_LOG_DIR", logDir.toString());
        // snappier spines/scans than the dev defaults — the cycles stay fully
        // deterministic (no sleeps), these only shrink the async latency the
        // Awaitility polls already tolerate
        common.put("NOVAFORGE_RELAY_INTERVAL_MS", "250");
        common.put("NOVAFORGE_HOOK_RETRY_SCAN_MS", "1000");
        common.put("NOVAFORGE_METADATA_INDEX_TTL_MS", "5000");
        common.put("JAVA_TOOL_OPTIONS", "-Xmx512m -XX:MaxMetaspaceSize=384m -XX:+ExitOnOutOfMemoryError");

        // boot order matters only for warm-up latency: metadata first (everything
        // resolves through it), then the runtime, then the rest
        List<String> order = List.of(METADATA, RUNTIME, SCRIPT, AUDIT, WORKFLOW,
                NOTIFICATION, REPORTING, INTEGRATION);
        for (String service : order) {
            Path jar = serviceJar(SERVICE_MODULES.get(service));
            Map<String, String> env = new LinkedHashMap<>(common);
            if (WORKFLOW.equals(service)) {
                env.put("NOVAFORGE_PROCESS_SYNC_MS", "5000");
                env.put("NOVAFORGE_SLA_SCAN_MS", "2000");
            }
            services.put(service, spawn(service, jar, env));
        }
        for (Map.Entry<String, Integer> entry : SERVICE_PORTS.entrySet()) {
            String service = entry.getKey();
            String health = "http://127.0.0.1:" + entry.getValue() + "/actuator/health";
            try {
                await(300, service + " health", () -> get200(health));
            } catch (IllegalStateException timeout) {
                Process process = services.get(service);
                throw new IllegalStateException(timeout.getMessage()
                        + (process != null && !process.isAlive()
                        ? " — the process EXITED" : " — the process is still alive")
                        + "\n--- last log lines (" + service + ") ---\n"
                        + logTail(service), timeout);
            }
        }
    }

    private String logTail(String service) {
        Path log = logDir.resolve(service + ".log");
        try {
            List<String> lines = Files.readAllLines(log);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 60), lines.size()));
        } catch (IOException e) {
            return "(no log at " + log + ")";
        }
    }

    private Process spawn(String name, Path jar, Map<String, String> env) {
        try {
            Path log = logDir.resolve(name + ".log");
            Files.createDirectories(log.getParent());
            ProcessBuilder pb = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar", jar.toString())
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .redirectErrorStream(true);
            pb.environment().putAll(env);
            Process process = pb.start();
            return process;
        } catch (IOException e) {
            throw new IllegalStateException("could not start " + name, e);
        }
    }

    /** The packaged Boot jar of a service module — the same artifact `java -jar` runs. */
    private Path serviceJar(String module) {
        Path target = repoRoot.resolve("services").resolve(module).resolve("target");
        if (!Files.isDirectory(target)) {
            throw new IllegalStateException("service " + module + " is not packaged yet ("
                    + target + " missing) — run `./mvnw -DskipTests package` first, or run the build from the repo root");
        }
        try (Stream<Path> jars = Files.list(target)) {
            List<Path> matches = jars
                    .filter(p -> p.getFileName().toString().startsWith("novaforge-")
                            && p.getFileName().toString().endsWith(".jar"))
                    .toList();
            if (matches.size() != 1) {
                throw new IllegalStateException("expected exactly one packaged jar in " + target
                        + ", found " + matches);
            }
            return matches.getFirst();
        } catch (IOException e) {
            throw new IllegalStateException("could not list " + target, e);
        }
    }

    private void stopServices() {
        for (Map.Entry<String, Process> entry : services.entrySet()) {
            Process process = entry.getValue();
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(15, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // --- preflight + await helpers ---

    private static void preflightPorts(Iterable<Integer> ports) {
        for (int port : ports) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(new InetSocketAddress("127.0.0.1", port));
            } catch (IOException occupied) {
                throw new IllegalStateException("port " + port + " is already in use — the "
                        + "e2e stack needs the services' default ports free (is the dev "
                        + "stack running? see README 'Full local stack').", occupied);
            }
        }
    }

    private static void await(int timeoutSeconds, String what, BooleanProbe probe) {
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                if (probe.probe()) {
                    return;
                }
            } catch (RuntimeException e) {
                last = e;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for " + what);
            }
        }
        throw new IllegalStateException("timed out after " + timeoutSeconds + "s waiting for "
                + what + (last == null ? "" : " — last error: " + last.getMessage()));
    }

    private interface BooleanProbe {
        boolean probe();
    }

    /** True when the URL answers 200 (any body). */
    private boolean get200(String url) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<Void> response = client.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // --- endpoints ---

    public String issuer() {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080)
                + "/realms/novaforge";
    }

    private String baseUrl(String service) {
        return "http://127.0.0.1:" + SERVICE_PORTS.get(service);
    }

    public String metadataUrl() {
        return baseUrl(METADATA);
    }

    public String runtimeUrl() {
        return baseUrl(RUNTIME);
    }

    public String workflowUrl() {
        return baseUrl(WORKFLOW);
    }

    public String reportingUrl() {
        return baseUrl(REPORTING);
    }

    // --- HTTP plumbing ---

    /** status + body for BOTH success and failure statuses — a 4xx is a result, not an exception. */
    public record HttpResult(int status, String body) {

        public JsonNode json() {
            return MAPPER.readTree(body == null || body.isBlank() ? "{}" : body);
        }

        public void assertOk(String what) {
            if (status < 200 || status >= 300) {
                throw new AssertionError(what + " failed: HTTP " + status + " — " + body);
            }
        }
    }

    public HttpResult http(String method, String url, String bearerToken, String jsonBody) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(10))
                    .header("Authorization", "Bearer " + bearerToken);
            if (jsonBody != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + url + " transport failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " " + url + " interrupted");
        }
    }

    // --- auth ---

    /** The trusted service client (client credentials) — the admin surface's other door. */
    public String serviceToken() {
        // the token endpoint takes a form body, not JSON — sent raw
        String form = "grant_type=client_credentials&client_id=novaforge-runtime"
                + "&client_secret=novaforge-runtime-secret";
        try {
            HttpClient client = HttpClient.newBuilder().build();
            HttpResponse<String> response = client.send(HttpRequest
                            .newBuilder(URI.create(issuer() + "/protocol/openid-connect/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("service grant failed: " + response.body());
            }
            return MAPPER.readTree(response.body()).get("access_token").asString();
        } catch (IOException e) {
            throw new IllegalStateException("service grant transport failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("service grant interrupted");
        }
    }

    /** A synthetic actor's password grant through the public {@code novaforge-api} client. */
    public String passwordGrant(String username, String password) {
        String form = "grant_type=password&client_id=novaforge-api"
                + "&username=" + java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8)
                + "&password=" + java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8);
        try {
            HttpClient client = HttpClient.newBuilder().build();
            HttpResponse<String> response = client.send(HttpRequest
                            .newBuilder(URI.create(issuer() + "/protocol/openid-connect/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("password grant failed for " + username
                        + ": " + response.body());
            }
            return MAPPER.readTree(response.body()).get("access_token").asString();
        } catch (IOException e) {
            throw new IllegalStateException("password grant transport failed for " + username, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("password grant interrupted for " + username);
        }
    }

    // --- tenants, users, roles (the admin surface, PHASE-2 §10) ---

    public record Tenant(String tenantId, String adminUsername, String adminPassword) {

        public String adminToken() {
            return stack().passwordGrant(adminUsername, adminPassword);
        }
    }

    public Tenant createTenant(String apiName) {
        HttpResult result = http("POST", runtimeUrl() + "/api/v1/admin/tenants",
                serviceToken(), MAPPER.writeValueAsString(Map.of(
                        "apiName", apiName,
                        "displayName", "E2E " + apiName,
                        "adminUsername", apiName + "-admin",
                        "adminEmail", apiName + "-admin@e2e.novaforge.local",
                        "adminPassword", "e2e-" + apiName + "-secret")));
        result.assertOk("createTenant " + apiName);
        return new Tenant(result.json().get("tenantId").asString(),
                apiName + "-admin", "e2e-" + apiName + "-secret");
    }

    /** Provisions a synthetic actor and assigns one app-scoped role; returns the userId. */
    public String provisionActor(Tenant tenant, String username, String appRole) {
        HttpResult created = http("POST",
                runtimeUrl() + "/api/v1/admin/tenants/" + tenant.tenantId() + "/users",
                serviceToken(), MAPPER.writeValueAsString(Map.of(
                        "username", username, "password", "e2e-" + username + "-secret")));
        created.assertOk("createUser " + username);
        String userId = created.json().get("userId").asString();
        HttpResult assigned = http("POST",
                runtimeUrl() + "/api/v1/admin/tenants/" + tenant.tenantId() + "/role-assignments",
                serviceToken(), MAPPER.writeValueAsString(Map.of("userId", userId, "role", appRole)));
        assigned.assertOk("assignRole " + appRole + " to " + username);
        return userId;
    }

    // --- apps + suites (the builder surface, ADR-010) ---

    public UUID publishApp(Tenant tenant, String appJsonText) {
        HttpResult created = http("POST", metadataUrl() + "/api/v1/metadata/apps",
                tenant.adminToken(), appJsonText);
        created.assertOk("createApp");
        UUID appId = UUID.fromString(created.json().get("id").asString());
        HttpResult published = http("POST",
                metadataUrl() + "/api/v1/metadata/apps/" + appId + "/publish",
                tenant.adminToken(), null);
        published.assertOk("publish " + created.json().get("apiName").asString());
        awaitProjections(appJsonText);
        return appId;
    }

    /**
     * Waits for the publish-driven projection materializer to catch up (PHASE-1 §4:
     * DDL rides the metadata.published spine on a background executor — never the hot
     * path). The projections are tenant-shared per entity apiName, so waiting once
     * after this publish also covers the harness's scratch-tenant candidate, whose
     * query/report legs would otherwise outrun the DDL on a fresh app.
     */
    private void awaitProjections(String appJsonText) {
        JsonNode app = MAPPER.readTree(appJsonText);
        for (JsonNode entity : app.path("entities")) {
            String table = projectionTable(entity.get("apiName").asString());
            await(90, "projection " + table, () -> projectionExists(table));
        }
    }

    /** The materializer's projection naming — the exact Snake.caseName port. */
    private static String projectionTable(String entityApiName) {
        StringBuilder name = new StringBuilder();
        for (char c : entityApiName.toCharArray()) {
            if (Character.isUpperCase(c)) {
                name.append('_').append(Character.toLowerCase(c));
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                name.append(c);
            } else {
                name.append('_');
            }
        }
        String table = name.toString();
        if (table.startsWith("_")) {
            table = table.substring(1);
        }
        if (table.isEmpty() || Character.isDigit(table.charAt(0))) {
            table = "c" + table;
        }
        return "rec_" + table;
    }

    private boolean projectionExists(String table) {
        try (var stmt = dataDb.createStatement();
             var rs = stmt.executeQuery("SELECT to_regclass('public." + table
                     + "') IS NOT NULL")) {
            return rs.next() && rs.getBoolean(1);
        } catch (java.sql.SQLException probeFailure) {
            // a connection hiccup answers "not yet" — the poll retries
            return false;
        }
    }

    public void putSuite(Tenant tenant, UUID appId, String suiteApiName, String suiteJsonText) {
        JsonNode suite = MAPPER.readTree(suiteJsonText);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("label", suite.has("label") ? suite.get("label").asString() : suiteApiName);
        List<Object> cases = new ArrayList<>();
        for (JsonNode testCase : suite.get("cases")) {
            cases.add(MAPPER.convertValue(testCase, Object.class));
        }
        body.put("cases", cases);
        HttpResult result = http("PUT", metadataUrl() + "/api/v1/metadata/apps/" + appId
                        + "/test-suites/" + suiteApiName,
                tenant.adminToken(), MAPPER.writeValueAsString(body));
        result.assertOk("putSuite " + suiteApiName);
    }

    /** Runs a suite through the headless API and fails the test with the run artifact if red. */
    public void runSuiteGreen(Tenant tenant, UUID appId, String suiteApiName) {
        HttpResult result = http("POST", metadataUrl() + "/api/v1/metadata/apps/" + appId
                + "/test-suites/" + suiteApiName + "/run", tenant.adminToken(), null);
        result.assertOk("runSuite " + suiteApiName);
        JsonNode artifact = result.json();
        if (!artifact.has("green") || !artifact.get("green").asBoolean()) {
            StringBuilder message = new StringBuilder("suite " + suiteApiName + " ran RED:\n");
            for (JsonNode testCase : artifact.path("cases")) {
                message.append("  case ").append(testCase.path("name").asString())
                        .append(": ").append(testCase.path("passed").asBoolean() ? "green" : "RED")
                        .append("\n");
                for (JsonNode failure : testCase.path("failures")) {
                    message.append("    - ").append(failure.asString()).append("\n");
                }
            }
            throw new AssertionError(message.toString());
        }
    }

    // --- helpers for the direct-API journeys ---

    public String runtimeCreate(Tenant tenant, String actorToken, String entity, String body) {
        HttpResult result = http("POST", runtimeUrl() + "/api/v1/runtime/" + entity,
                actorToken, body);
        result.assertOk("create " + entity);
        return result.json().get("id").asString();
    }

    public HttpResult runtimeCreateRaw(String actorToken, String entity, String body) {
        return http("POST", runtimeUrl() + "/api/v1/runtime/" + entity, actorToken, body);
    }

    public HttpResult runtimeUpdateRaw(String actorToken, String entity, String recordId,
                                       String body) {
        return http("PATCH", runtimeUrl() + "/api/v1/runtime/" + entity + "/" + recordId,
                actorToken, body);
    }

    public JsonNode runtimeGet(String actorToken, String entity, String recordId) {
        HttpResult result = http("GET", runtimeUrl() + "/api/v1/runtime/" + entity + "/"
                + recordId, actorToken, null);
        result.assertOk("read " + entity + "/" + recordId);
        return result.json();
    }

    /** The workflow inbox as the actor — OPEN tasks (optionally filtered by role). */
    public List<JsonNode> openTasks(String actorToken, String roleFilter) {
        HttpResult result = http("GET", workflowUrl()
                + "/api/v1/workflow/tasks?status=OPEN&size=200", actorToken, null);
        result.assertOk("open tasks");
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode task : result.json().path("rows")) {
            if (roleFilter == null || roleFilter.equals(task.path("role").asString())) {
                matches.add(task);
            }
        }
        return matches;
    }

    public void resolveTask(String actorToken, String taskId, String action) {
        HttpResult result = http("POST", workflowUrl() + "/api/v1/workflow/tasks/" + taskId
                + "/" + action, actorToken, null);
        result.assertOk("resolve task " + taskId + " " + action);
    }

    public JsonNode runReport(String actorToken, String reportId, String appApiName) {
        HttpResult result = http("POST", reportingUrl() + "/api/v1/reports/" + reportId + "/run",
                actorToken, MAPPER.writeValueAsString(Map.of(
                        "app", appApiName, "params", Map.of(), "fresh", true)));
        result.assertOk("runReport " + reportId);
        return result.json();
    }

    /** Repo-file loader — anchors at the repository root regardless of the working dir. */
    public String readRepoFile(String first, String... rest) {
        Path path = repoRoot.resolve(first);
        for (String segment : rest) {
            path = path.resolve(segment);
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }

    public String readAppJson(String app, String file) {
        try {
            return Files.readString(repoRoot.resolve("apps").resolve(app).resolve(file));
        } catch (IOException e) {
            throw new IllegalStateException("could not read apps/" + app + "/" + file, e);
        }
    }

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("apps").resolve("erp").resolve("erp-app.json"))
                    && Files.isDirectory(p.resolve("services").resolve("metadata-service"))) {
                return p;
            }
        }
        throw new IllegalStateException("repo root not found upward from " + cwd);
    }
}

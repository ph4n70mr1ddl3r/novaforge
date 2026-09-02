package com.novaforge.script.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.DefinitionValidator;
import com.novaforge.metadata.HookRule;
import com.novaforge.metadata.ScriptDefinition;
import com.novaforge.security.ServiceClientGate;
import com.novaforge.script.engine.ScriptBudgetExceededException;
import com.novaforge.script.engine.ScriptSandbox;
import com.novaforge.script.telemetry.ScriptMetrics;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal execution surface (PHASE-3 §6): the Data Runtime's script hooks call
 * here. The script runs under the calling user's tenant and actor (ARCHITECTURE.md
 * §5 item 4) — the token that arrives is the one {@code $data.query} relays, so the
 * sandbox cannot bypass the single data path. Executions are stateless (ADR-003 #3):
 * the script body is a versioned artifact carried by the caller, never stored here.
 */
@RestController
@RequestMapping("/api/v1/scripts")
public class ScriptController {

    /** What a hook asks the engine to run (the script artifact travels with the call). */
    public record ExecutionRequest(String app, Integer appVersion, String hook, String trigger,
                                   String language, String script, String sandbox,
                                   Map<String, Object> record) {
    }

    /**
     * The Scheduler's leg (PHASE-4 §7): the Data Runtime relays a recordless firing —
     * tenant and the per-app system principal ride the body because the caller is the
     * trusted service client, never a user (no token exists to relay).
     */
    public record ScheduledRequest(String tenantId, String app, Integer appVersion,
                                   String hook, String language, String script, String sandbox) {
    }

    private final ScriptSandbox sandbox;
    private final ScriptMetrics metrics;
    private final com.novaforge.script.security.ServiceAttestationGate attestations;

    public ScriptController(ScriptSandbox sandbox, ScriptMetrics metrics,
                            com.novaforge.script.security.ServiceAttestationGate attestations) {
        this.sandbox = sandbox;
        this.metrics = metrics;
        this.attestations = attestations;
    }

    @PostMapping("/execute")
    public ScriptSandbox.ScriptResult execute(@RequestBody ExecutionRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = com.novaforge.script.security.ServiceAttestationGate.HEADER,
                    required = false) String serviceAttestation) {
        // An internal surface (no gateway route — the Data Runtime relays hook
        // execution here), and the request carries an arbitrary script BODY plus a
        // sandbox opt-in: with the connector sandbox set, $http executes connector
        // operations under tenant credentials. The engine is the only legitimate
        // caller — user tokens reaching pod-network must not turn it into an
        // egress primitive. PHASE-3 §6's reconciled shape: the PRIMARY credential is
        // either the service client itself or the relayed calling user (caller-
        // context, §13 Q1) — and in the user case the runtime's service-client
        // attestation must accompany it.
        if (!ServiceClientGate.isServiceClient() && !attestations.attested(serviceAttestation)) {
            throw new PlatformException(PlatformErrorCode.FORBIDDEN,
                    "the script-execute surface requires the platform service client "
                            + "(primary, or the Data Runtime's attestation beside the "
                            + "relayed caller token)");
        }
        validateAuthoring(request.app(), request.hook(), request.language(), request.script());
        if (request.trigger() == null || !HookRule.TRIGGERS.contains(request.trigger())) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "trigger must be one of " + HookRule.TRIGGERS);
        }
        var context = TenantContext.current().orElseThrow(() ->
                new PlatformException(PlatformErrorCode.TENANT_MISSING,
                        "no tenant context bound"));
        return run(request.app(), request.appVersion(), request.hook(), request.trigger(),
                () -> sandbox.execute(request.script(), request.record(), context,
                        ScriptDefinition.SANDBOX_CONNECTOR.equals(request.sandbox())
                                ? request.app() : null));
    }

    /**
     * The scheduled execution (PHASE-4 §7): service-client gated — the Data Runtime's
     * scheduled-hook surface is the only caller. Tenant and actor arrive in the body
     * (the per-app system principal, {@code system:<app>}); {@code $record} is absent
     * and {@code $data.query} rides the internal system-principal leg. Telemetry joins
     * the same series the write path feeds, under the {@code scheduled} trigger label.
     */
    @PostMapping("/scheduled")
    public ScriptSandbox.ScriptResult scheduled(@RequestBody ScheduledRequest request) {
        ServiceClientGate.require("scheduled-script");
        validateAuthoring(request.app(), request.hook(), request.language(), request.script());
        if (request.tenantId() == null) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "tenantId is required — the scheduled leg binds its own context");
        }
        var principal = new TenantContext.Context(request.tenantId(),
                UUID.nameUUIDFromBytes(("system:" + request.app()).getBytes()).toString());
        var result = new ScriptSandbox.ScriptResult[] {null};
        return run(request.app(), request.appVersion(), request.hook(),
                ScriptSandbox.SCHEDULED_TRIGGER, () -> {
                    TenantContext.with(principal, () -> result[0] =
                            sandbox.executeScheduled(request.script(), request.app(), principal,
                                    ScriptDefinition.SANDBOX_CONNECTOR.equals(request.sandbox())
                                            ? request.app() : null));
                    return result[0];
                });
    }

    /** The shared authoring checks (the telemetry dimensions are label values). */
    private static void validateAuthoring(String app, String hook, String language,
                                          String script) {
        if (script == null || script.isBlank()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "script must not be blank");
        }
        if (!ScriptDefinition.LANGUAGES.contains(language == null
                ? "js" : language)) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "unsupported script language: " + language
                    + " — v0 is " + ScriptDefinition.LANGUAGES);
        }
        if (script.length() > ScriptDefinition.MAX_SOURCE_CHARS) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "script exceeds " + ScriptDefinition.MAX_SOURCE_CHARS + " characters");
        }
        if (app == null
                || !DefinitionValidator.PASCAL_CASE.matcher(app).matches()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "app must be the published app's apiName: " + app);
        }
        if (hook == null || !hook.matches("[a-zA-Z][A-Za-z0-9_-]*")) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "hook must be the hook rule's name: " + hook);
        }
    }

    /** The shared telemetry envelope both surfaces ride. */
    private ScriptSandbox.ScriptResult run(String app, Integer appVersion, String hook,
                                           String trigger,
                                           java.util.function.Supplier<ScriptSandbox.ScriptResult> body) {
        long start = System.nanoTime();
        String outcome = ScriptMetrics.OK;
        try {
            return body.get();
        } catch (ScriptBudgetExceededException e) {
            outcome = ScriptMetrics.CAPPED;
            throw e;
        } catch (PlatformException e) {
            outcome = ScriptMetrics.ERROR;
            throw e;
        } finally {
            metrics.executed(app, appVersion, trigger, outcome);
            metrics.duration(trigger, (System.nanoTime() - start) / 1_000_000);
        }
    }
}

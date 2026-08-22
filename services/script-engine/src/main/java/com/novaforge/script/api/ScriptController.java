package com.novaforge.script.api;

import com.novaforge.common.context.TenantContext;
import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.DefinitionValidator;
import com.novaforge.metadata.HookRule;
import com.novaforge.metadata.ScriptDefinition;
import com.novaforge.script.engine.ScriptBudgetExceededException;
import com.novaforge.script.engine.ScriptSandbox;
import com.novaforge.script.telemetry.ScriptMetrics;
import java.util.Map;
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
                                   String language, String script,
                                   Map<String, Object> record) {
    }

    private final ScriptSandbox sandbox;
    private final ScriptMetrics metrics;

    public ScriptController(ScriptSandbox sandbox, ScriptMetrics metrics) {
        this.sandbox = sandbox;
        this.metrics = metrics;
    }

    @PostMapping("/execute")
    public ScriptSandbox.ScriptResult execute(@RequestBody ExecutionRequest request) {
        if (request.script() == null || request.script().isBlank()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "script must not be blank");
        }
        if (!ScriptDefinition.LANGUAGES.contains(request.language() == null
                ? "js" : request.language())) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "unsupported script language: " + request.language()
                            + " — v0 is " + ScriptDefinition.LANGUAGES);
        }
        if (request.script().length() > ScriptDefinition.MAX_SOURCE_CHARS) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "script exceeds " + ScriptDefinition.MAX_SOURCE_CHARS + " characters");
        }
        // the telemetry dimensions double as label values — validate what arrives
        if (request.trigger() == null || !HookRule.TRIGGERS.contains(request.trigger())) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "trigger must be one of " + HookRule.TRIGGERS);
        }
        if (request.app() == null
                || !DefinitionValidator.PASCAL_CASE.matcher(request.app()).matches()) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "app must be the published app's apiName: " + request.app());
        }
        if (request.hook() == null || !request.hook().matches("[a-zA-Z][A-Za-z0-9_-]*")) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "hook must be the hook rule's name: " + request.hook());
        }
        var context = TenantContext.current().orElseThrow(() ->
                new PlatformException(PlatformErrorCode.TENANT_MISSING,
                        "no tenant context bound"));
        long start = System.nanoTime();
        String outcome = ScriptMetrics.OK;
        try {
            return sandbox.execute(request.script(), request.record(), context);
        } catch (ScriptBudgetExceededException e) {
            outcome = ScriptMetrics.CAPPED;
            throw e;
        } catch (PlatformException e) {
            outcome = ScriptMetrics.ERROR;
            throw e;
        } finally {
            metrics.executed(request.app(), request.appVersion(), request.trigger(), outcome);
            metrics.duration(request.trigger(), (System.nanoTime() - start) / 1_000_000);
        }
    }
}

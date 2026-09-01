package com.novaforge.metadata.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.common.error.ProblemErrors;
import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.metadata.DefinitionValidator;
import com.novaforge.metadata.EntityDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The perf fixture is a CI-gated artifact too (the twenty-eighth pass closed the
 * gap — the ERP and Purchasing artifacts were gated, this one was not): the
 * recorded ARCHITECTURE.md §9 numbers (write p95 < 150 ms with one synchronous
 * beforeSave hook + one record validation; filtered list < 300 ms over the
 * status/dueDate indexes) are measured against exactly this shape. A silent
 * fixture edit — a second hook, a dropped index, a renamed field — invalidates
 * every recorded number while the load scripts keep "passing", so the shape the
 * methodology depends on is pinned here, on top of the save/compile checks the
 * builder would run.
 */
class PerfAppArtifactTests {

    private static final Path APP = Path.of("..", "..", "apps", "perf");

    private static AppDefinition app() throws Exception {
        return DefinitionParser.parseApp(
                Files.readString(APP.resolve("perfhook-app.json")));
    }

    @Test
    @DisplayName("the PerfHook app definition is save-clean and compile-clean")
    void saveAndCompileClean() throws Exception {
        AppDefinition app = app();
        ProblemErrors errors = DefinitionValidator.validate(app);
        assertThat(errors.isEmpty())
                .as("save validation findings: %s", errors.errors())
                .isTrue();
        // the hook expression (upper(name)) and the validation expression
        // (amount >= 0) must keep compiling against the expression engine
        DefinitionService.compileCheckExpressions(app, new ProblemErrors(List.of(), List.of()));
        FlowCompiler.compile(app);
    }

    @Test
    @DisplayName("the §9-measured shape: one beforeSave hook, one record validation, the measured fields/indexes")
    void measuredShapeIsPinned() throws Exception {
        AppDefinition app = app();
        EntityDefinition doc = app.entity("PerfDoc").orElseThrow();

        // exactly ONE synchronous beforeSave hook — the write-path overhead the
        // hook-perf run isolates (PHASE-3 §11 / PHASE-8 §8); a second hook would
        // double-count, zero would under-count, either silently re-baselines §9
        var hooks = doc.hooks();
        assertThat(hooks).hasSize(1);
        assertThat(hooks.get(0).trigger()).isEqualTo("beforeSave");
        assertThat(hooks.get(0).flow().op()).isEqualTo("setField");
        assertThat(hooks.get(0).flow().param("field")).isEqualTo("stamp");
        assertThat(hooks.get(0).flow().param("expression")).isEqualTo("upper(name)");

        // exactly ONE record-scope validation, the amount non-negativity check
        var validations = doc.validations();
        assertThat(validations).hasSize(1);
        assertThat(validations.get(0).scope()).isEqualTo("record");
        assertThat(validations.get(0).expression()).isEqualTo("amount >= 0");

        // the money field rides the write path (the decimal round-trip the perf
        // writes exercise) and the filtered-list indexes stay authored
        assertThat(doc.field("amount").orElseThrow().type()).isEqualTo(com.novaforge.metadata.FieldType.MONEY);
        assertThat(doc.indexes()).extracting(i -> i.fields().toString())
                .containsExactlyInAnyOrder("[status]", "[dueDate]");
    }
}

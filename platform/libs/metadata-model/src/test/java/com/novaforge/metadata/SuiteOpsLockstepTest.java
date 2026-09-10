package com.novaforge.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The suite step vocabulary's twin-parity gate: {@link TestSuiteDefinition.Step#OPS}
 * (the save gate and the runner's dispatch switch) and the TS twin's
 * {@code SUITE_OPS} (frontend/shared/src/metadata.ts — the builder suite editor's
 * op dropdown) must name exactly the same ops. The vocabulary grows in pairs; it
 * grew once in one — {@code awaitTasks} shipped server-side (the G-11 harvest)
 * while the twin lagged, so the builder could not author a step the backend's own
 * save gate accepted, with no test the wiser. This pin makes the next half-growth
 * loud on the side that moves first.
 *
 * <p>Mirrored by the TS side's own comments (frontend/shared/src/metadata.ts);
 * the expression DSL's parity travels the same mirrored-pin road, with the
 * shared conformance corpus as its lockstep — the vocabulary's corpus is this
 * file's read of the twin's source.</p>
 */
class SuiteOpsLockstepTest {

    /** Repo root relative to the module dir (the surefire working directory). */
    private static final Path METADATA_TS =
            Path.of("..", "..", "..", "frontend", "shared", "src", "metadata.ts");

    @Test
    @DisplayName("SUITE_OPS (the TS twin) names exactly the ops TestSuiteDefinition.OPS knows")
    void twinsNameTheSameOps() throws Exception {
        String source = Files.readString(METADATA_TS);

        Matcher array = Pattern.compile(
                        "export const SUITE_OPS = \\[(.*?)] as const;", Pattern.DOTALL)
                .matcher(source);
        assertThat(array.find())
                .as("frontend/shared/src/metadata.ts still declares SUITE_OPS as an "
                        + "array literal — the twin moved; re-point this parser")
                .isTrue();
        Set<String> tsOps = new LinkedHashSet<>();
        Matcher quoted = Pattern.compile("\"([A-Za-z]+)\"").matcher(array.group(1));
        while (quoted.find()) {
            tsOps.add(quoted.group(1));
        }

        assertThat(tsOps)
                .as("the TS twin's suite step vocabulary (frontend/shared/src/metadata.ts)")
                .containsExactlyInAnyOrderElementsOf(TestSuiteDefinition.Step.OPS);
    }
}

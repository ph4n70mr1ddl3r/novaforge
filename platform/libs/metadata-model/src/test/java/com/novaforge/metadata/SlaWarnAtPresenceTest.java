package com.novaforge.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PHASE-4 §6's {@code warnAt: null} pin is presence-sensitive: an absent
 * {@code warnAt} authors the 0.8 default while an explicit {@code null} disables
 * the warn timer outright — indistinguishable after a plain record binding, so
 * {@link SlaDefinition} deserializes presence-aware and always serializes the
 * field: the authored disable survives every definition round-trip.
 */
class SlaWarnAtPresenceTest {

    @Test
    @DisplayName("an absent warnAt authors the 0.8 default; an explicit null disables")
    void warnAtPresence() {
        var app = DefinitionParser.parseApp("""
                { "apiName": "Purch",
                  "slas": [
                    { "id": "absent", "target": "PT2H",
                      "scope": { "taskType": "approval" } },
                    { "id": "disabled", "target": "PT2H", "warnAt": null,
                      "scope": { "taskType": "approval" } },
                    { "id": "explicit", "target": "PT2H", "warnAt": 0.5,
                      "scope": { "taskType": "approval" } } ] }
                """);
        assertThat(app.slas()).extracting(SlaDefinition::id, SlaDefinition::warnAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("absent", 0.8),
                        org.assertj.core.groups.Tuple.tuple("disabled", null),
                        org.assertj.core.groups.Tuple.tuple("explicit", 0.5));
    }

    @Test
    @DisplayName("the authored disable round-trips — write→parse keeps warnAt null")
    void disableRoundTrips() {
        var authored = DefinitionParser.parseApp("""
                { "apiName": "Purch",
                  "slas": [ { "id": "disabled", "target": "PT2H", "warnAt": null,
                    "scope": { "taskType": "approval" } } ] }
                """);
        var roundTripped = DefinitionParser.parseApp(DefinitionParser.writeApp(authored));
        assertThat(roundTripped.slas().getFirst().warnAt()).isNull();

        // and the default survives its own round-trip as the authored fraction
        var defaulted = DefinitionParser.parseApp("""
                { "apiName": "Purch",
                  "slas": [ { "id": "absent", "target": "PT2H",
                    "scope": { "taskType": "approval" } } ] }
                """);
        assertThat(DefinitionParser.parseApp(DefinitionParser.writeApp(defaulted))
                .slas().getFirst().warnAt()).isEqualTo(0.8);
    }
}

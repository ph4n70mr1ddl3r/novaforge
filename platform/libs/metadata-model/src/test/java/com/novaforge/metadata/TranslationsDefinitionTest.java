package com.novaforge.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The i18n fallback chain + missing-translation report (PHASE-8 §7): the chain is
 * pinned — {@code label_i18n[locale] → label → apiName}, never a blank label — and
 * pure, so the runtime renderer, the builder, and the suites resolve identically.
 */
class TranslationsDefinitionTest {

    private static AppDefinition app() {
        return DefinitionParser.parseApp("""
                { "apiName": "Shop",
                  "entities": [ { "apiName": "Order", "label": "Order",
                    "fields": [ { "apiName": "total", "type": "money" } ] } ] }
                """);
    }

    @Test
    @DisplayName("fallback chain: label_i18n[locale] → label → apiName — never blank")
    void fallbackChain() {
        assertThat(TranslationsDefinition.resolve("de",
                Map.of("de", "Bestellung"), "Order", "Order")).isEqualTo("Bestellung");
        // locale missing from label_i18n falls to the label
        assertThat(TranslationsDefinition.resolve("fr",
                Map.of("de", "Bestellung"), "Order", "Order")).isEqualTo("Order");
        // a blank localized value falls through too (never a blank label)
        assertThat(TranslationsDefinition.resolve("de",
                Map.of("de", "  "), "Order", "Order")).isEqualTo("Order");
        // no label at all: the apiName — deterministic, never blank
        assertThat(TranslationsDefinition.resolve("de", Map.of(), null, "Order")).isEqualTo("Order");
        assertThat(TranslationsDefinition.resolve(null, Map.of("de", "x"), "Order", "Order"))
                .isEqualTo("Order");
    }

    @Test
    @DisplayName("the missing-translation report: translatable keys with no entry per locale")
    void missingReport() {
        AppDefinition app = app();
        List<String> universe = TranslationsDefinition.translatableKeys(app);
        assertThat(universe).contains("app.label", "Order.label", "Order.total.label");

        assertThat(TranslationsDefinition.missing(null, app)).containsAll(universe);

        TranslationsDefinition partial = new TranslationsDefinition("de",
                Map.of("app.label", "Shop (DE)"));
        assertThat(TranslationsDefinition.missing(partial, app))
                .doesNotContain("app.label")
                .contains("Order.label", "Order.total.label");

        TranslationsDefinition blank = new TranslationsDefinition("de",
                Map.of("app.label", " "));
        assertThat(TranslationsDefinition.missing(blank, app)).contains("app.label");
    }

    @Test
    @DisplayName("locale shape: ll or ll-CC tags pass, malformed reject")
    void localeShape() {
        assertThat(TranslationsDefinition.LOCALE.matcher("de").matches()).isTrue();
        assertThat(TranslationsDefinition.LOCALE.matcher("pt-BR").matches()).isTrue();
        assertThat(TranslationsDefinition.LOCALE.matcher("zh-Hans-CN").matches()).isTrue();
        assertThat(TranslationsDefinition.LOCALE.matcher("DE").matches()).isFalse();
        assertThat(TranslationsDefinition.LOCALE.matcher("de_").matches()).isFalse();
        assertThat(TranslationsDefinition.LOCALE.matcher("").matches()).isFalse();
    }

    @Test
    @DisplayName("translations round-trip as app metadata (versioned, promoted)")
    void roundTrip() {
        AppDefinition with = DefinitionParser.parseApp("""
                { "apiName": "Shop",
                  "entities": [],
                  "translations": [ { "locale": "de", "entries": { "app.label": "Shop (DE)" } } ] }
                """);
        assertThat(with.translations("de")).isPresent();
        assertThat(with.translations("de").orElseThrow().entries()).containsEntry("app.label", "Shop (DE)");
        String json = DefinitionParser.writeApp(with);
        assertThat(DefinitionParser.parseApp(json).translations("de")).isPresent();
    }
}

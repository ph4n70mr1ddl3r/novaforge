package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * The Translations branch (PHASE-8 §7, the PHASE-2 §13 Q3 deferral landing): one
 * workspace per app × locale — versioned metadata promoted with the app and gated by
 * the same suites as everything else.
 *
 * <p>Entries key translatable label slots by their metadata address:
 * {@code app.label}, {@code <Entity>.label}, {@code <Entity>.<field>.label},
 * {@code report.<id>.label}. The fallback chain is pinned —
 * {@code label_i18n[activeLocale] → label → apiName} (never a blank label) — and the
 * in-place {@code label_i18n} maps on definitions stay the runtime-resolution
 * surface; this branch is the translator's workspace whose import/export feeds it.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TranslationsDefinition(
        String locale,
        Map<String, String> entries) {

    public TranslationsDefinition {
        entries = entries == null ? Map.of() : Map.copyOf(entries);
    }

    /** Locale shape: a BCP-47-ish {@code ll} or {@code ll-CC} tag. */
    public static final java.util.regex.Pattern LOCALE =
            java.util.regex.Pattern.compile("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$");

    /** The translatable label addresses of an app (the missing-translation universe). */
    public static List<String> translatableKeys(AppDefinition app) {
        List<String> keys = new java.util.ArrayList<>();
        keys.add("app.label");
        for (EntityDefinition entity : app.entities()) {
            keys.add(entity.apiName() + ".label");
            for (FieldDefinition field : entity.fields()) {
                keys.add(entity.apiName() + "." + field.apiName() + ".label");
            }
        }
        for (ReportDefinition report : app.reports()) {
            keys.add("report." + report.id() + ".label");
        }
        return keys;
    }

    /**
     * The pinned fallback chain (§7): {@code label_i18n[locale]} → {@code label} →
     * {@code apiName} — never a blank label. Pure, so the runtime renderer, the
     * builder, and the conformance suites all resolve identically.
     */
    public static String resolve(String locale, Map<String, String> labelI18n,
                                 String label, String apiName) {
        if (labelI18n != null && locale != null) {
            String localized = labelI18n.get(locale);
            if (localized != null && !localized.isBlank()) {
                return localized;
            }
        }
        if (label != null && !label.isBlank()) {
            return label;
        }
        return apiName == null ? "" : apiName;
    }

    /** The per-locale missing-translation report: translatable keys with no entry. */
    public static List<String> missing(TranslationsDefinition translations,
                                       AppDefinition app) {
        if (translations == null) {
            return translatableKeys(app);
        }
        return translatableKeys(app).stream()
                .filter(key -> {
                    String value = translations.entries().get(key);
                    return value == null || value.isBlank();
                })
                .toList();
    }
}

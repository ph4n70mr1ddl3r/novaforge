package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * A dashboard definition (PHASE-5 §5): a grid of widgets bound to report refs plus
 * role visibility — the composition PHASE-2 §13/Q4 deferred here. Dashboards are
 * metadata only in v1: loading is a definition fetch through the Metadata Service's
 * published read plus client-issued report runs (no dashboard-scoped API exists —
 * §2's reserved-but-unrouted {@code /api/v1/dashboards} prefix).
 *
 * <p>{@code roles} governs composition only (who may load the dashboard); every
 * widget's report run still executes under the requesting actor with the
 * {@code report: execute} grant and the actor's sharing-rule row filters — a
 * dashboard never widens what its viewer may see (§8).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardDefinition(
        String id,
        String label,
        @JsonProperty("label_i18n") Map<String, String> labelI18n,
        List<Widget> widgets,
        List<String> roles) {

    public DashboardDefinition {
        labelI18n = labelI18n == null ? Map.of() : Map.copyOf(labelI18n);
        widgets = widgets == null ? List.of() : List.copyOf(widgets);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /** The v1 widget vocabulary (§5) — catalog components render each kind. */
    public static final java.util.Set<String> WIDGET_TYPES =
            java.util.Set.of("kpi", "chart", "table");

    /** Dashboard ids are stable API names, like report ids. */
    public static final java.util.regex.Pattern DASHBOARD_KEY =
            java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /**
     * One grid cell: a widget of a {@link #WIDGET_TYPES} kind bound to a report of
     * the same app ({@code reportRef}), with run-param overrides and a grid span.
     * {@code refreshSeconds} is the §5 client-timer auto-refresh interval — null
     * (the default) keeps the widget static; the server never pushes in v1.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Widget(String widget, String reportRef, Map<String, Object> params,
                         Integer span, Integer refreshSeconds) {

        public Widget {
            params = params == null ? Map.of() : Map.copyOf(params);
        }

        /** The §5 auto-refresh bounds: a floor against timer churn, a ceiling against drift. */
        public static final int REFRESH_FLOOR_SECONDS = 5;
        public static final int REFRESH_CEILING_SECONDS = 3600;
    }
}

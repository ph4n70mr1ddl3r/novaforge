import { useCallback, useEffect, useState, type ReactNode } from "react";
import {
    DashboardGrid,
    DashboardCell,
    KpiTile,
    ReportTable,
    ChartWidget,
    resolveLabel,
    type AppDefinition,
    type DashboardDefinition,
    type DashboardWidget,
    type DrillThroughBinding,
    type PlatformClient,
    type QueryFilter,
    type ReportDefinition,
    type ReportRun,
} from "@novaforge/shared";

/**
 * Dashboards (PHASE-5 §5): composition from published dashboard metadata — every
 * widget's report run executes under the requesting actor; a dashboard never
 * widens what its viewer may see (§8 — role visibility filters composition only).
 * Auto-refresh is a per-widget client timer (§5 — configurable, default off; the
 * server never pushes in v1), and ReportTable widgets drill through to the bound
 * entity's list page carrying the row's filters as a query-DSL payload (§5/§10
 * item 2's round-trip).
 */
export function Dashboards({
    client,
    appApiName,
    app,
    roles,
    onDrill,
}: {
    client: PlatformClient;
    appApiName: string;
    app: AppDefinition;
    /** Every app role the viewer holds — composition matches any of them (§8). */
    roles: string[];
    /** Deep-links a drill-through: the entity's list plus the row's filter payload. */
    onDrill?: (entity: string, filter: QueryFilter) => void;
}): ReactNode {
    const visible = app.dashboards.filter(
        (dashboard) =>
            !dashboard.roles?.length || dashboard.roles.some((role) => roles.includes(role)),
    );
    const [selected, setSelected] = useState<DashboardDefinition | undefined>(visible[0]);
    return (
        <section className="nf-dashboards" aria-label="Dashboards">
            <h2>Dashboards</h2>
            {visible.length === 0 ? (
                <p role="status">No dashboards available for your roles.</p>
            ) : (
                <>
                    <div role="tablist" aria-label="Dashboard selection">
                        {visible.map((dashboard) => (
                            <button
                                key={dashboard.id}
                                role="tab"
                                aria-selected={selected?.id === dashboard.id}
                                type="button"
                                onClick={() => setSelected(dashboard)}
                            >
                                {resolveLabel(dashboard, undefined, dashboard.id)}
                            </button>
                        ))}
                    </div>
                    {selected ? (
                        <DashboardView client={client} appApiName={appApiName} app={app} dashboard={selected} onDrill={onDrill} />
                    ) : null}
                </>
            )}
        </section>
    );
}

function DashboardView({
    client,
    appApiName,
    app,
    dashboard,
    onDrill,
}: {
    client: PlatformClient;
    appApiName: string;
    app: AppDefinition;
    dashboard: DashboardDefinition;
    onDrill?: (entity: string, filter: QueryFilter) => void;
}): ReactNode {
    const drillBinding = useCallback(
        (reportRef: string) => {
            const report: ReportDefinition | undefined =
                app.reports.find((candidate) => candidate.id === reportRef);
            if (!report?.drillThrough || !onDrill) {
                return undefined;
            }
            const entity = report.drillThrough.entity;
            return {
                entity,
                carryFilters: report.drillThrough.carryFilters,
                // the JVM validator pins saved-filter ops to the closed leaf set at
                // save — the wire type is wider than the list page's vocabulary
                filters: report.filters as DrillThroughBinding["filters"],
                groupFields: (report.groupBy ?? [])
                    .filter((group) => !group.buckets?.length)
                    .map((group) => group.field),
                drill: (filter: QueryFilter) => onDrill(entity, filter),
            };
        },
        [app.reports, onDrill],
    );

    return (
        <DashboardGrid>
            {dashboard.widgets.map((widget, index) => (
                <WidgetCell
                    key={index}
                    client={client}
                    appApiName={appApiName}
                    widget={widget}
                    drill={drillBinding(widget.reportRef)}
                />
            ))}
        </DashboardGrid>
    );
}

/**
 * One widget's cell: its report run under the requesting actor, refreshed on its
 * own client timer when `refreshSeconds` is authored (§5 — absent means static).
 */
function WidgetCell({
    client,
    appApiName,
    widget,
    drill,
}: {
    client: PlatformClient;
    appApiName: string;
    widget: DashboardWidget;
    drill?: DrillThroughBinding & { drill: (filter: QueryFilter) => void };
}): ReactNode {
    const [run, setRun] = useState<ReportRun | null>(null);
    const [failed, setFailed] = useState(false);
    const [tick, setTick] = useState(0);

    useEffect(() => {
        let cancelled = false;
        // params are REPORT RUN PARAMS (filter overrides + asOf) — display config
        // (the KPI's metric, chart axes) rides `options` and never reaches the
        // run: unknown params compile into filter leaves the runtime rejects
        client
            .runReport(appApiName, widget.reportRef, widget.params ?? {})
            .then((result) => {
                if (!cancelled) {
                    setRun(result as unknown as ReportRun);
                }
            })
            .catch(() => {
                /* a failed run renders the empty state — never "Loading…" forever */
                if (!cancelled) {
                    setFailed(true);
                }
            });
        return () => {
            cancelled = true;
        };
    }, [client, appApiName, widget.reportRef, widget.params, tick]);

    useEffect(() => {
        if (!widget.refreshSeconds) {
            return;
        }
        const timer = setInterval(
            () => setTick((current) => current + 1),
            widget.refreshSeconds * 1000,
        );
        return () => clearInterval(timer);
    }, [widget.refreshSeconds]);

    // the KPI's metric is authored display config: the named aggregate, else the
    // first totals key (an aggregate-only report's totals are its single row)
    const metric =
        typeof widget.options?.metric === "string" && widget.options.metric
            ? widget.options.metric
            : String(Object.keys(run?.totals ?? {})[0] ?? "");

    return (
        <DashboardCell span={widget.span ?? 6}>
            {failed && !run ? (
                <p role="status">{widget.reportRef} unavailable — the run failed.</p>
            ) : !run ? (
                <p role="status">Loading {widget.reportRef}…</p>
            ) : widget.widget === "kpi" ? (
                <KpiTile reportRef={widget.reportRef} totals={run.totals} metric={metric} label={widget.reportRef} />
            ) : widget.widget === "chart" ? (
                <ChartWidget reportRef={widget.reportRef} chart={run.chart} title={widget.reportRef} />
            ) : (
                <ReportTable
                    reportRef={widget.reportRef}
                    run={run}
                    title={widget.reportRef}
                    drillThrough={drill}
                    onDrill={drill ? (_row, filter) => drill.drill(filter) : undefined}
                />
            )}
        </DashboardCell>
    );
}

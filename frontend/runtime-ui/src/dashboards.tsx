import { useEffect, useState, type ReactNode } from "react";
import {
    DashboardGrid,
    DashboardCell,
    KpiTile,
    ReportTable,
    ChartWidget,
    resolveLabel,
    type AppDefinition,
    type DashboardDefinition,
    type PlatformClient,
    type ReportRun,
} from "@novaforge/shared";

/**
 * Dashboards (PHASE-5 §5): composition from published dashboard metadata — every
 * widget's report run executes under the requesting actor; a dashboard never
 * widens what its viewer may see (§8 — role visibility filters composition only).
 */
export function Dashboards({
    client,
    appApiName,
    app,
    role,
}: {
    client: PlatformClient;
    appApiName: string;
    app: AppDefinition;
    role?: string;
}): ReactNode {
    const visible = app.dashboards.filter(
        (dashboard) => !dashboard.roles?.length || (role && dashboard.roles.includes(role)),
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
                    {selected ? <DashboardView client={client} appApiName={appApiName} dashboard={selected} /> : null}
                </>
            )}
        </section>
    );
}

function DashboardView({
    client,
    appApiName,
    dashboard,
}: {
    client: PlatformClient;
    appApiName: string;
    dashboard: DashboardDefinition;
}): ReactNode {
    const [runs, setRuns] = useState<Record<string, ReportRun>>({});
    useEffect(() => {
        let cancelled = false;
        (async () => {
            const results: Record<string, ReportRun> = {};
            for (const widget of dashboard.widgets) {
                results[widget.reportRef] = (await client.runReport(appApiName, widget.reportRef, widget.params ?? {})) as unknown as ReportRun;
            }
            if (!cancelled) {
                setRuns(results);
            }
        })().catch(() => {
            /* run failures render per-widget empty states */
        });
        return () => {
            cancelled = true;
        };
    }, [client, appApiName, dashboard]);
    return (
        <DashboardGrid>
            {dashboard.widgets.map((widget, index) => {
                const run = runs[widget.reportRef];
                return (
                    <DashboardCell key={index} span={widget.span ?? 6}>
                        {!run ? (
                            <p role="status">Loading {widget.reportRef}…</p>
                        ) : widget.widget === "kpi" ? (
                            <KpiTile reportRef={widget.reportRef} totals={run.totals} metric={String(Object.keys(run.totals)[0] ?? "")} label={widget.reportRef} />
                        ) : widget.widget === "chart" ? (
                            <ChartWidget reportRef={widget.reportRef} chart={run.chart} title={widget.reportRef} />
                        ) : (
                            <ReportTable reportRef={widget.reportRef} run={run} title={widget.reportRef} />
                        )}
                    </DashboardCell>
                );
            })}
        </DashboardGrid>
    );
}

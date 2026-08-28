import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
    ApiError,
    PlatformClient,
    type AppDefinition,
    type EntityDefinition,
} from "@novaforge/shared";
import { EntityBuilder } from "./entity-builder.tsx";
import { PageBuilder } from "./page-builder.tsx";
import { RbacEditor } from "./rbac-editor.tsx";
import { Onboarding } from "./onboarding.tsx";
import { I18nEditor } from "./i18n-editor.tsx";
import { ReportBuilder } from "./report-builder.tsx";
import { DashboardComposer } from "./dashboard-composer.tsx";
import { GapLogEditor, Lifecycle, SuiteRuns } from "./lifecycle.tsx";
import { LogicEditor } from "./logic-editor.tsx";
import { SuitesEditor } from "./suites-editor.tsx";
import { Automation } from "./automation.tsx";
import { Integrations } from "./integrations.tsx";
import { Templates } from "./templates.tsx";

/**
 * The builder shell (PHASE-2 §8): design-time surface over the Metadata draft
 * APIs — entity builder, page builder, RBAC editors, tenant onboarding, plus the
 * Phase 4 automation screen (state machines, SLAs, scheduled jobs — §11), and
 * the Phase 8 lifecycle/i18n screens riding the same builder-gated APIs.
 */

export type BuilderScreen =
    | "entities"
    | "pages"
    | "logic"
    | "suites"
    | "automation"
    | "rbac"
    | "reports"
    | "dashboards"
    | "integrations"
    | "i18n"
    | "lifecycle"
    | "templates"
    | "onboarding";

export interface BuilderShellProps {
    client: PlatformClient;
    role?: string;
}

export function BuilderShell({ client, role }: BuilderShellProps): ReactNode {
    const [screen, setScreen] = useState<BuilderScreen>("entities");
    const [app, setApp] = useState<AppDefinition | null>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        client
            .listApps()
            .then(async (apps) => {
                const first = apps[0] as { id?: string } | undefined;
                if (!first?.id) throw new Error("No apps yet — onboard a tenant and create one.");
                const loaded = (await client.getApp(first.id)) as AppDefinition;
                if (!cancelled) setApp({ ...loaded, id: first.id });
            })
            .catch((caught: unknown) => {
                if (!cancelled) setError(caught instanceof Error ? caught.message : String(caught));
            });
        return () => {
            cancelled = true;
        };
    }, [client, app === null]);

    const reload = useMemo(
        () => async (): Promise<void> => {
            if (!app?.id) return;
            const fresh = (await client.getApp(app.id)) as AppDefinition;
            setApp({ ...fresh, id: app.id });
        },
        [client, app?.id],
    );

    return (
        <div className="nf-builder">
            <header className="nf-topbar">
                <h1>NovaForge Builder</h1>
                <nav aria-label="Builder sections">
                    {(["entities", "pages", "logic", "suites", "automation", "rbac", "reports", "dashboards", "integrations", "i18n", "lifecycle", "templates", "onboarding"] as BuilderScreen[]).map((name) => (
                        <button key={name} type="button" aria-current={screen === name} onClick={() => setScreen(name)} id={name === "entities" ? "entities" : undefined}>
                            {name}
                        </button>
                    ))}
                </nav>
                {role ? <span className="nf-user">{role}</span> : null}
            </header>
            <main>
                {error ? <p role="alert">{error}</p> : null}
                {!app && !error ? (
                    <p role="status">Loading…</p>
                ) : app ? (
                    <>
                        {screen === "entities" ? (
                            <EntityBuilder
                                key={String(app.id)}
                                app={app}
                                appId={app.id ?? ""}
                                onSave={async (entity: EntityDefinition) => {
                                    await client.putEntity(app.id ?? "", entity as unknown as Record<string, unknown>);
                                    await reload();
                                }}
                                onDelete={async (apiName) => {
                                    await client.deleteEntity(app.id ?? "", apiName);
                                    await reload();
                                }}
                            />
                        ) : null}
                        {screen === "pages" ? (
                            <PageBuilder
                                app={app}
                                role={role}
                                savePage={async (page) => {
                                    try {
                                        return await client.putPage(app.id ?? "", page);
                                    } catch (caught) {
                                        if (caught instanceof ApiError && caught.status === 409) {
                                            // refresh the revision base, then surface the rebase prompt
                                            const fresh = (await client.getApp(app.id ?? "")) as AppDefinition;
                                            setApp({ ...fresh, id: app.id });
                                        }
                                        throw caught;
                                    }
                                }}
                            />
                        ) : null}
                        {screen === "logic" ? (
                            <LogicEditor
                                app={app}
                                onSaveEntity={async (entity: EntityDefinition) => {
                                    await client.putEntity(app.id ?? "", entity as unknown as Record<string, unknown>);
                                    await reload();
                                }}
                            />
                        ) : null}
                        {screen === "suites" ? (
                            <SuitesEditor
                                app={app}
                                onSaveSuite={async (suite) => {
                                    await client.putSuite(app.id ?? "", suite.apiName, suite as unknown as Record<string, unknown>);
                                    await reload();
                                }}
                                onRunSuite={async (apiName) => await client.runSuite(app.id ?? "", apiName)}
                            />
                        ) : null}
                        {screen === "automation" ? (
                            <Automation
                                app={app}
                                client={client}
                                onSave={async (patch) => {
                                    // state machines, SLAs, and jobs ride the app document
                                    // (versioned, promoted — §3/§6/§7)
                                    await client.patchApp(app.id ?? "", patch);
                                    await reload();
                                }}
                            />
                        ) : null}
                        {screen === "rbac" ? (
                            <RbacEditor
                                app={app}
                                onSave={async (permissionSet) => {
                                    // PermissionSet rides the app document (versioned, promoted — §9)
                                    await client.patchApp(app.id ?? "", { permissionSet } as Record<string, unknown>);
                                    await reload();
                                }}
                            />
                        ) : null}
                        {screen === "reports" ? (
                            <ReportBuilder
                                app={app}
                                saveReports={async (reports) => {
                                    await client.patchApp(app.id ?? "", { reports } as Record<string, unknown>);
                                    await reload();
                                }}
                            />
                        ) : null}
                        {screen === "dashboards" ? (
                            <DashboardComposer
                                app={app}
                                saveDashboards={async (dashboards) => {
                                    await client.patchApp(app.id ?? "", { dashboards } as Record<string, unknown>);
                                    await reload();
                                }}
                            />
                        ) : null}
                        {screen === "integrations" ? (
                            <Integrations
                                app={app}
                                client={client}
                                onSave={async (patch) => {
                                    await client.patchApp(app.id ?? "", patch);
                                    await reload();
                                }}
                            />
                        ) : null}
                        {screen === "i18n" ? (
                            <I18nEditor
                                app={app}
                                loadWorkspace={async (locale) => {
                                    const translations = await client.translations(app.id ?? "");
                                    return (translations as { locale: string; entries: Record<string, string> }[])
                                        .find((translation) => translation.locale === locale);
                                }}
                                saveWorkspace={async (locale, entries) => {
                                    await client.putTranslation(app.id ?? "", locale, entries);
                                    await reload();
                                }}
                            />
                        ) : null}
                        {screen === "lifecycle" ? (
                            <>
                                <GapLogEditor
                                    app={app}
                                    onSave={async (patch) => {
                                        await client.patchApp(app.id ?? "", patch);
                                        await reload();
                                    }}
                                />
                                <SuiteRuns client={client} appId={app.id ?? ""} />
                                <Lifecycle client={client} appId={app.id ?? ""} />
                            </>
                        ) : null}
                    </>
                ) : null}
                {screen === "templates" ? (
                    <Templates
                        client={client}
                        onInstalled={(apiName) => {
                            setError(null);
                            setScreen("entities");
                            void (async () => {
                                const apps = (await client.listApps()) as { id?: string }[];
                                if (apps[0]?.id) {
                                    const loaded = (await client.getApp(apps[0].id)) as AppDefinition;
                                    setApp({ ...loaded, id: apps[0].id });
                                }
                            })();
                        }}
                    />
                ) : null}
                {screen === "onboarding" ? (
                    <Onboarding
                        client={client}
                        onAppCreated={() => {
                            setError(null);
                            setScreen("entities");
                            void (async () => {
                                const apps = (await client.listApps()) as { id?: string }[];
                                if (apps[0]?.id) {
                                    const loaded = (await client.getApp(apps[0].id)) as AppDefinition;
                                    setApp({ ...loaded, id: apps[0].id });
                                }
                            })();
                        }}
                    />
                ) : null}
            </main>
        </div>
    );
}

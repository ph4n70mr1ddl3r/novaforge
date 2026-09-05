import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
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
import { BrandingEditor } from "./branding-editor.tsx";

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
    | "branding"
    | "i18n"
    | "lifecycle"
    | "templates"
    | "onboarding";

/**
 * The topbar nav, module-grouped like the runtime's (§5): fourteen raw screen
 * keys in one unlabeled run ("rbac", "i18n", …) read as debug output — groups
 * with human labels are scannable, and the divider CSS carries the grouping.
 * Exported so the tests drive the same mapping the shell renders.
 */
export const BUILDER_NAV: { group: string; items: { key: BuilderScreen; label: string }[] }[] = [
    {
        group: "Build",
        items: [
            { key: "entities", label: "Entities" },
            { key: "pages", label: "Pages" },
            { key: "logic", label: "Logic" },
        ],
    },
    {
        group: "Automate",
        items: [
            { key: "automation", label: "Automation" },
            { key: "integrations", label: "Integrations" },
        ],
    },
    {
        group: "Quality",
        items: [
            { key: "suites", label: "Suites" },
            { key: "lifecycle", label: "Lifecycle" },
        ],
    },
    {
        group: "Insight",
        items: [
            { key: "reports", label: "Reports" },
            { key: "dashboards", label: "Dashboards" },
        ],
    },
    {
        group: "Govern",
        items: [
            { key: "rbac", label: "RBAC" },
            { key: "branding", label: "Branding" },
            { key: "i18n", label: "I18n" },
        ],
    },
    {
        group: "Workspace",
        items: [
            { key: "templates", label: "Templates" },
            { key: "onboarding", label: "Onboarding" },
        ],
    },
];

/** Every legal screen key, derived from the nav so the two can never drift. */
const BUILDER_SCREENS: readonly BuilderScreen[] =
    BUILDER_NAV.flatMap((group) => group.items.map((item) => item.key));

/** Hash routing for the builder (the runtime router's trivial twin): the
 *  screen lives in the fragment — `#pages`, `#rbac` — so a refresh keeps the
 *  editor you were in and an editor screen deep-links. Anything unknown
 *  decodes null; the shell falls to its default screen. */
const encodeScreen = (screen: BuilderScreen): string => `#${screen}`;

const decodeScreen = (hash: string): BuilderScreen | null => {
    const raw = hash.replace(/^#\/?/, "");
    return (BUILDER_SCREENS as readonly string[]).includes(raw) ? (raw as BuilderScreen) : null;
};

export interface BuilderShellProps {
    client: PlatformClient;
    role?: string;
}

export function BuilderShell({ client, role }: BuilderShellProps): ReactNode {
    // Boot reads the URL (encodeScreen/decodeScreen above): a refresh or a
    // shared link lands ON the named screen; junk falls to the default. The
    // screen's synchronous mirror serves the hashchange listener below — echo
    // suppression and the gate's rewind both run outside render.
    const [screen, setScreen] = useState<BuilderScreen>(
        () => decodeScreen(window.location.hash) ?? "entities",
    );
    const screenRef = useRef(screen);
    screenRef.current = screen;
    // Apply a screen AND its URL face: a changed hash is pushed (an in-app nav
    // becomes a history entry, so Back walks screens), an unchanged one is left
    // alone. The async hashchange echo of our own push re-decodes to the screen
    // we already applied — screenRef suppresses it (and serves the Back/Forward
    // arm, which arrives with no click of its own).
    const go = useCallback((next: BuilderScreen) => {
        setScreen(next);
        if (window.location.hash !== encodeScreen(next)) {
            window.location.hash = encodeScreen(next);
        }
    }, []);
    const [app, setApp] = useState<AppDefinition | null>(null);
    const [error, setError] = useState<string | null>(null);
    // the logic/suites editors take their busy state from the shell (their save
    // legs live here) — the ref-less version left their buttons live mid-flight
    const [busy, setBusy] = useState(false);

    // The builder's unsaved-changes gate — the runtime shell's contract, learned:
    // the page builder and the dashboard composer hold edits LOCAL until their
    // explicit Save ("Save page •"), but a topbar click unmounted them silently
    // and destroyed the work (the exact class of loss the runtime's route guard
    // fixed for typed records). Mounted editors register a SYNCHRONOUS dirty
    // check (refs, not render-lagged state — the runtime's lesson); a gated
    // screen switch asks first.
    const dirtyCheckRef = useRef<(() => boolean) | null>(null);
    const registerDirtyCheck = useCallback((check: (() => boolean) | null) => {
        dirtyCheckRef.current = check;
    }, []);
    const [pendingScreen, setPendingScreen] = useState<{ proceed: () => void } | null>(null);
    // where focus returns when the gate's dialog closes: the trigger (a nav
    // button) is captured at guard-fire time — an effect capture would run after
    // the commit, when the dialog's own autofocus already owns focus
    const restoreFocusRef = useRef<HTMLElement | null>(null);
    const guard = useCallback((proceed: () => void) => {
        if (dirtyCheckRef.current?.()) {
            restoreFocusRef.current =
                document.activeElement instanceof HTMLElement ? document.activeElement : null;
            setPendingScreen({ proceed });
            return;
        }
        proceed();
    }, []);
    // Back/Forward: a committed hash jump lands here with no click of its own —
    // apply it, unless the builder is dirty, in which case the URL is rewound to
    // where we still are and the gate asks (its Discard rides the same go()).
    // Echoes of our own go() (decoded == current) and junk hashes are ignored;
    // a junk hash also has its URL snapped back so the bar never disagrees.
    useEffect(() => {
        const onHashChange = (): void => {
            const next = decodeScreen(window.location.hash);
            if (!next) {
                history.replaceState(null, "", encodeScreen(screenRef.current));
                return;
            }
            if (next === screenRef.current) {
                return;
            }
            if (dirtyCheckRef.current?.()) {
                restoreFocusRef.current =
                    document.activeElement instanceof HTMLElement ? document.activeElement : null;
                history.replaceState(null, "", encodeScreen(screenRef.current));
                setPendingScreen({ proceed: () => go(next) });
                return;
            }
            setScreen(next);
        };
        window.addEventListener("hashchange", onHashChange);
        return () => window.removeEventListener("hashchange", onHashChange);
    }, [go]);
    // boot-time URL alignment: an empty or hand-edited hash snaps to the
    // mounted screen's canonical form without adding a history entry
    useEffect(() => {
        const encoded = encodeScreen(screen);
        if (window.location.hash !== encoded) {
            history.replaceState(null, "", encoded);
        }
        // mount-only — the initial screen is the alignment source
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    // Dialog modality — the product's keyboard contract (the runtime's gate, the
    // inbox's ask dialog): Escape cancels, Tab is TRAPPED inside the dialog, and
    // closing restores focus to the trigger.
    useEffect(() => {
        if (!pendingScreen) {
            return;
        }
        const onKey = (event: KeyboardEvent): void => {
            if (event.key === "Escape") {
                event.stopPropagation();
                setPendingScreen(null);
                return;
            }
            if (event.key !== "Tab") {
                return;
            }
            const dialog = document.querySelector(".nf-dialog");
            if (!(dialog instanceof HTMLElement)) {
                return;
            }
            const focusable = Array.from(
                dialog.querySelectorAll<HTMLElement>("button, input, textarea, select, a[href]"),
            ).filter((element) => !element.hasAttribute("disabled"));
            if (focusable.length === 0) {
                return;
            }
            const first = focusable[0]!;
            const last = focusable[focusable.length - 1]!;
            const active = document.activeElement;
            if (event.shiftKey && (active === first || !(active instanceof Node) || !dialog.contains(active))) {
                event.preventDefault();
                last.focus();
            } else if (!event.shiftKey && active === last) {
                event.preventDefault();
                first.focus();
            }
        };
        document.addEventListener("keydown", onKey, true);
        return () => {
            document.removeEventListener("keydown", onKey, true);
            restoreFocusRef.current?.focus();
        };
    }, [pendingScreen]);

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
        // once per client — the old `app === null` dependency re-fired the whole
        // listApps+getApp load exactly when the first app landed, duplicating it
        // on every mount
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [client]);

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
                    {BUILDER_NAV.map((group) => (
                        <span key={group.group} className="nf-navgroup">
                            <span className="nf-navgroup-label">{group.group}</span>
                            {group.items.map((item) => (
                                <button
                                    key={item.key}
                                    type="button"
                                    aria-current={screen === item.key}
                                    // every screen switch rides the gate: a dirty page
                                    // builder or dashboard composer asks before its
                                    // unmount destroys the edits
                                    onClick={() => guard(() => go(item.key))}
                                    id={item.key === "entities" ? "entities" : undefined}
                                >
                                    {item.label}
                                </button>
                            ))}
                        </span>
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
                                // the gate rides in: the builder's own entity/kind
                                // switches discard the working page just as a topbar
                                // click would, so they route through the same guard
                                guard={guard}
                                registerDirtyCheck={registerDirtyCheck}
                                savePage={async (page) => {
                                    try {
                                        const saved = await client.putPage(app.id ?? "", page);
                                        // every other screen-saver reloads; the pages leg
                                        // skipping it left the shell's app (and the
                                        // builder's seed, revision included) at the
                                        // pre-save snapshot — the NEXT save 409'd
                                        // against the user's own revision
                                        await reload();
                                        return saved;
                                    } catch (caught) {
                                        if (caught instanceof ApiError && caught.status === 409) {
                                            // refresh the revision base, then surface the rebase prompt.
                                            // The FRESH saved page rides the error: the editor's catch
                                            // runs synchronously with the `app` prop captured at click
                                            // time (setApp has only scheduled a re-render), so reading
                                            // the prop there resolved the "server page" to the stale
                                            // tree the editor already had.
                                            const fresh = (await client.getApp(app.id ?? "")) as AppDefinition;
                                            setApp({ ...fresh, id: app.id });
                                            (caught as ApiError & { freshSavedPage?: unknown }).freshSavedPage =
                                                fresh.pages.find(
                                                    (candidate) => candidate.apiName === page.apiName);
                                        }
                                        throw caught;
                                    }
                                }}
                            />
                        ) : null}
                        {screen === "logic" ? (
                            <LogicEditor
                                app={app}
                                busy={busy}
                                onSaveEntity={async (entity: EntityDefinition) => {
                                    setBusy(true);
                                    try {
                                        await client.putEntity(app.id ?? "", entity as unknown as Record<string, unknown>);
                                        await reload();
                                    } finally {
                                        setBusy(false);
                                    }
                                }}
                            />
                        ) : null}
                        {screen === "suites" ? (
                            <SuitesEditor
                                app={app}
                                busy={busy}
                                onSaveSuite={async (suite) => {
                                    setBusy(true);
                                    try {
                                        await client.putSuite(app.id ?? "", suite.apiName, suite as unknown as Record<string, unknown>);
                                        await reload();
                                    } finally {
                                        setBusy(false);
                                    }
                                }}
                                onRunSuite={async (apiName) => {
                                    setBusy(true);
                                    try {
                                        return await client.runSuite(app.id ?? "", apiName);
                                    } finally {
                                        setBusy(false);
                                    }
                                }}
                            />
                        ) : null}
                        {screen === "automation" ? (
                            <Automation
                                app={app}
                                client={client}
                                onSave={async (patch) => {
                                    // state machines, SLAs, and jobs ride the app document
                                    // (versioned, promoted — §3/§6/§7); the branch patch
                                    // applies to a FRESH fetch so another tab's concurrent
                                    // additions survive (the dashboards rule)
                                    const fresh = (await client.getApp(app.id ?? "")) as AppDefinition;
                                    await client.patchApp(app.id ?? "", patch(fresh));
                                    await reload();
                                }}
                            />
                        ) : null}
                        {screen === "rbac" ? (
                            <RbacEditor
                                app={app}
                                onSave={async (mutate) => {
                                    // PermissionSet rides the app document (versioned, promoted — §9);
                                    // the mutation applies to a FRESH fetch (the dashboards rule)
                                    const fresh = (await client.getApp(app.id ?? "")) as AppDefinition;
                                    await client.patchApp(app.id ?? "", {
                                        permissionSet: mutate(fresh.permissionSet),
                                    } as Record<string, unknown>);
                                    await reload();
                                }}
                            />
                        ) : null}
                        {screen === "reports" ? (
                            <ReportBuilder
                                app={app}
                                saveReports={async (mutate) => {
                                    // the mutation applies to a FRESH fetch: a stale
                                    // mount-time snapshot must never replace another
                                    // tab's concurrent report save (the dashboards rule)
                                    const fresh = (await client.getApp(app.id ?? "")) as {
                                        reports?: Parameters<typeof mutate>[0];
                                    };
                                    await client.patchApp(app.id ?? "", { reports: mutate(fresh.reports ?? []) } as Record<string, unknown>);
                                    await reload();
                                }}
                            />
                        ) : null}
                        {screen === "dashboards" ? (
                            <DashboardComposer
                                app={app}
                                registerDirtyCheck={registerDirtyCheck}
                                saveDashboards={async (mutate) => {
                                    // the mutation applies to a FRESH fetch: a stale
                                    // mount-time snapshot must never replace another
                                    // tab's concurrent dashboard save
                                    const fresh = (await client.getApp(app.id ?? "")) as {
                                        dashboards?: unknown[];
                                    };
                                    const next = mutate((fresh.dashboards ?? []) as Parameters<typeof mutate>[0]);
                                    await client.patchApp(app.id ?? "", { dashboards: next } as Record<string, unknown>);
                                    await reload();
                                }}
                            />
                        ) : null}
                        {screen === "integrations" ? (
                            <Integrations
                                app={app}
                                client={client}
                                onSave={async (mutate) => {
                                    // the mutation applies to a FRESH fetch: the editor's
                                    // mount-time branch must never replace another tab's
                                    // concurrent integrations save (the dashboards rule)
                                    const fresh = (await client.getApp(app.id ?? "")) as AppDefinition;
                                    await client.patchApp(app.id ?? "", {
                                        integrations: mutate(fresh.integrations ?? {}),
                                    } as Record<string, unknown>);
                                    await reload();
                                }}
                            />
                        ) : null}
                        {screen === "branding" ? (
                            <BrandingEditor
                                key={String(app.id)}
                                app={app}
                                busy={busy}
                                onSave={async (mutate) => {
                                    // the branding patch applies to a FRESH fetch (the
                                    // dashboards rule); an empty branch replaces whole,
                                    // dropping the tenant back to the platform palette
                                    setBusy(true);
                                    try {
                                        const fresh = (await client.getApp(app.id ?? "")) as AppDefinition;
                                        await client.patchApp(app.id ?? "", {
                                            branding: mutate(fresh.branding),
                                        } as Record<string, unknown>);
                                        await reload();
                                    } finally {
                                        setBusy(false);
                                    }
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
                                        // the gapLog patch applies to a FRESH fetch (the
                                        // dashboards rule): a mount-time snapshot saved
                                        // verbatim deleted another tab's concurrent entries
                                        const fresh = (await client.getApp(app.id ?? "")) as AppDefinition;
                                        await client.patchApp(app.id ?? "", patch(fresh));
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
                            go("entities");
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
                            go("entities");
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
            {pendingScreen ? (
                // the unsaved-changes gate — a real dialog, not a blocking
                // browser confirm; Discard completes the interrupted switch
                <div className="nf-dialog-scrim" onClick={() => setPendingScreen(null)}>
                    <div
                        role="dialog"
                        aria-modal="true"
                        aria-label="Unsaved changes"
                        className="nf-dialog"
                        onClick={(event) => event.stopPropagation()}
                    >
                        <h3>Leave with unsaved changes?</h3>
                        <p className="nf-hint">
                            This screen has edits that are not saved yet. Discarding loses
                            every change since the last save.
                        </p>
                        <div className="nf-dialog-actions">
                            <button type="button" autoFocus onClick={() => setPendingScreen(null)}>
                                Keep editing
                            </button>
                            <button
                                type="button"
                                className="nf-danger"
                                onClick={() => {
                                    const target = pendingScreen;
                                    setPendingScreen(null);
                                    target.proceed();
                                }}
                            >
                                Discard changes
                            </button>
                        </div>
                    </div>
                </div>
            ) : null}
        </div>
    );
}

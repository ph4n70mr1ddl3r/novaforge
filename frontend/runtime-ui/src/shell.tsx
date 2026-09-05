import { useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from "react";
import {
    ApiError,
    PlatformClient,
    type PageDefinition,
    type QueryFilter,
    randomKey,
    type RendererDataService,
    resolvePage,
    resolveNav,
    PageRenderer,
    type AppDefinition,
    type EntityDefinition,
    type PublishedApp,
    type RendererContextValue,
} from "@novaforge/shared";
import { Inbox } from "./inbox.tsx";
import { Notifications } from "./notifications.tsx";
import { Dashboards } from "./dashboards.tsx";

/**
 * The runtime application shell (PHASE-2 §6): nav from published metadata
 * (module-grouped), pages through the shared renderer — the L1 default overlaid
 * with the app's saved page deltas — plus the Phase 4 approval inbox and
 * notification inbox/preferences (§5/§8) and the Phase 5 dashboards. The renderer
 * interprets; this shell supplies data + actions.
 */

export interface RuntimeShellProps {
    client: PlatformClient;
    published: PublishedApp;
    user: { name: string; roles: string[]; locale?: string };
    versionKey: string;
}

type Route =
    | { view: "home" }
    | { view: "entity"; entity: string; kind: "list" | "form" | "detail"; id?: string;
        /** A drill-through deep link's query-DSL payload (PHASE-5 §5). */
        filter?: QueryFilter }
    | { view: "inbox" }
    | { view: "notifications" }
    | { view: "dashboards" };

/** The save/action toast: the message plus its tone. Success and failure shared
 * one grey paragraph before, so "Saved" and an error looked identical. */
interface Flash {
    message: string;
    tone: "ok" | "error";
}

function effectiveRoles(app: AppDefinition, user: { roles: string[] }): string[] {
    // App-scoped roles arrive as `app.role` assignments; the shell maps EVERY
    // app-defined role the user holds (PHASE-2 §9: rendering only — the Data
    // Runtime enforces server-side). The first one drives single-role surfaces;
    // dashboard composition matches any held role — a controller who is also a
    // clerk must still see the executive dashboard (§8 filters composition only).
    const appRoles = new Set(app.permissionSet.roles.map((role) => role.name));
    return user.roles
        .map((role) => role.split(".").pop() ?? role)
        .filter((role) => appRoles.has(role));
}

export function RuntimeShell({ client, published, user, versionKey }: RuntimeShellProps): ReactNode {
    const app = published.app as AppDefinition;
    // Tenant branding (ADR-009 §5): overrides ride the token layer — the shell
    // sets the variables on its root and every surface under it re-themes (light
    // and dark both, the tokens re-map underneath); no component reads a raw
    // color. accentContrast keeps the platform token when the tenant omits it.
    const brandingStyle: CSSProperties | undefined = app.branding?.accent
        ? ({
              "--nf-color-accent": app.branding.accent,
              ...(app.branding.accentContrast
                  ? { "--nf-color-accent-contrast": app.branding.accentContrast }
                  : {}),
          } as CSSProperties)
        : undefined;
    const [route, setRoute] = useState<Route>({ view: "home" });
    const [locale, setLocale] = useState<string | undefined>(user.locale);
    const roles = effectiveRoles(app, user);
    const role = roles[0];
    const entities = useMemo(
        () => new Map(app.entities.map((entity) => [entity.apiName, entity])),
        [app.entities],
    );
    // The runtime API addresses entities APP-QUALIFIED (`App.Entity` — the
    // resolver's disambiguation surface): a tenant running two published apps
    // that collide on an entity apiName (the ERP corpus and a same-named demo
    // app both defining Customer) 400s every bare-name write with "defined by
    // multiple published apps — qualify the app" (found live at the golden
    // journey). The engine pins the qualified form; the shell must send it.
    const qualified = (entityApiName: string) => `${app.apiName}.${entityApiName}`;

    const [flash, setFlash] = useState<Flash | null>(null);
    // the toast dismisses itself: a "Saved" that outlives its context is noise,
    // and the same budget applies to failures — the page's role=alert surfaces
    // keep failure details on screen regardless
    useEffect(() => {
        if (!flash) {
            return;
        }
        const timer = window.setTimeout(() => setFlash(null), 6000);
        return () => window.clearTimeout(timer);
    }, [flash]);
    const data: RendererDataService = useMemo(
        () => ({
            list: (request) => client.list({ ...request, entity: qualified(request.entity) }),
            search: async (target, term, size) => {
                const entity = entities.get(target);
                const field = entity?.displayField ?? "id";
                return client.search(qualified(target), term, field, size);
            },
            // lookup options label by the target's own display field — the widget
            // cannot see other entities' definitions through its own field map
            displayFieldOf: (target) => entities.get(target)?.displayField,
        }),
        // qualified reads app.apiName — stable for the lifetime of a published shell
        [client, entities],
    );

    const navigate = (entity: string, kind: "list" | "form" | "detail", id?: string) => {
        setRoute({ view: "entity", entity, kind, id });
    };

    // the drill-through deep link (PHASE-5 §5): the report row's filters ride the
    // route as a query-DSL payload the list page consumes natively
    const drillTo = (entity: string, filter: QueryFilter) => {
        setRoute({ view: "entity", entity, kind: "list", filter });
    };

    const nav = resolveNav(app, { role, locale });
    const savedPages = useMemo(
        () => new Map(
            app.pages
                .filter((page) => page.entity)
                .map((page) => [`${page.entity}:${page.type}`, page] as const),
        ),
        [app.pages],
    );

    return (
        <div className="nf-runtime" data-metadata-version={versionKey} style={brandingStyle}>
            <header className="nf-topbar">
                <h1>{app.label ?? app.apiName}</h1>
                <nav aria-label="Primary">
                    <button type="button" onClick={() => setRoute({ view: "home" })}>Home</button>
                    {nav.map((group) => (
                        <span key={group.label} className="nf-navgroup">
                            {/* the module label was resolved and then dropped — an ERP's
                                entities rendered as one flat, unlabeled run of buttons */}
                            <span className="nf-navgroup-label">{group.label}</span>
                            {group.entities.map((entity) => (
                                <button
                                    key={entity.apiName}
                                    type="button"
                                    onClick={() => navigate(entity.apiName, "list")}
                                >
                                    {entity.label}
                                </button>
                            ))}
                        </span>
                    ))}
                    <button type="button" onClick={() => setRoute({ view: "inbox" })}>Approvals</button>
                    <button type="button" onClick={() => setRoute({ view: "notifications" })}>Notifications</button>
                    <button type="button" onClick={() => setRoute({ view: "dashboards" })}>Dashboards</button>
                </nav>
                <label className="nf-locale">
                    Locale
                    <select
                        value={locale ?? "en"}
                        onChange={(event) => setLocale(event.target.value)}
                    >
                        <option value="en">en</option>
                        {app.translations.map((translation) => (
                            <option key={translation.locale} value={translation.locale}>
                                {translation.locale}
                            </option>
                        ))}
                    </select>
                </label>
                <span className="nf-user">{user.name}</span>
            </header>
            <main>
                {route.view === "home" ? (
                    <p className="nf-home">Select a record type to begin.</p>
                ) : route.view === "inbox" ? (
                    <Inbox client={client} />
                ) : route.view === "notifications" ? (
                    <Notifications client={client} />
                ) : route.view === "dashboards" ? (
                    <Dashboards client={client} appApiName={app.apiName} app={app} roles={roles} onDrill={drillTo} />
                ) : (
                    <EntityPage
                        // keyed by the route's identity: without it React reuses the
                        // instance across entity/kind navigation, and a previously
                        // loaded record bled into "New form" for another entity —
                        // whose save PATCHed the wrong record with foreign data
                        key={`${route.entity}:${route.kind}:${route.id ?? "new"}`}
                        client={client}
                        entity={entities.get(route.entity) as EntityDefinition}
                        kind={route.kind}
                        id={route.id}
                        filter={route.filter}
                        savedPages={savedPages}
                        app={app}
                        role={role}
                        locale={locale}
                        data={data}
                        navigate={navigate}
                        setFlash={setFlash}
                    />
                )}
            </main>
            {flash ? (
                // keyed by content: repeating the same message re-runs the toast
                // (and restarts its dismiss budget) instead of invisibly no-oping
                <div
                    key={`${flash.tone}:${flash.message}`}
                    role="status"
                    aria-live="polite"
                    className={flash.tone === "error" ? "nf-flash nf-flash-error" : "nf-flash"}
                >
                    {flash.message}
                </div>
            ) : null}
        </div>
    );
}

interface EntityPageProps {
    client: PlatformClient;
    entity: EntityDefinition;
    kind: "list" | "form" | "detail";
    id?: string;
    /** The drill-through payload (§5) — the list page splices it into every request. */
    filter?: QueryFilter;
    savedPages: Map<string, PageDefinition>;
    app: AppDefinition;
    /** The save/action toast setter, lifted to the shell: a create navigates to
     * the record's detail, and a page-local toast died with the page it
     * reported for — "Saved" was unobservable on every create (found live at
     * the golden journey's final step). The toast itself renders in the shell. */
    setFlash: (flash: Flash | null) => void;
    role?: string;
    locale?: string;
    data: RendererDataService;
    navigate: (entity: string, kind: "list" | "form" | "detail", id?: string) => void;
}

function EntityPage(props: EntityPageProps): ReactNode {
    const { client, entity, kind, id, filter, savedPages, app, role, locale, data, navigate, setFlash } = props;
    // app-qualified runtime addresses (see RuntimeShell's note — the ambiguity guard)
    const qualified = (entityApiName: string) => `${app.apiName}.${entityApiName}`;
    const saved = savedPages.get(`${entity.apiName}:${kind}`);
    const { page, stale } = resolvePage(saved, entity, { role, permissions: app.permissionSet, locale, kind });
    const [record, setRecord] = useState<Record<string, unknown> | null>(null);
    const [errors, setErrors] = useState<Record<string, string>>({});
    const [busy, setBusy] = useState(false);

    const [loadError, setLoadError] = useState<string | null>(null);

    const load = async (recordId: string): Promise<void> => {
        try {
            setRecord(await client.getRecord(qualified(entity.apiName), recordId));
            setLoadError(null);
        } catch (caught) {
            // a failed detail load used to leave a silent empty form plus an
            // unhandled rejection — the user sees what happened and can retry
            setLoadError(caught instanceof Error ? caught.message : String(caught));
        }
    };

    // The record fetch rides an effect with an in-flight guard, never the render
    // body: the old render-time call refetched on EVERY re-render while the record
    // was loading (a fetch storm), clobbered typed edits when each response landed,
    // and retried a failing load forever. The id comparison normalizes to strings —
    // the route param is always a string, the server's id is whatever JSON gave.
    const inFlightRef = useRef<string | null>(null);
    useEffect(() => {
        if (kind === "list" || !id) {
            return;
        }
        if (record?.id !== undefined && String(record.id) === id) {
            return;
        }
        if (inFlightRef.current === id) {
            return;
        }
        inFlightRef.current = id;
        void load(id).finally(() => {
            inFlightRef.current = null;
        });
        // eslint-disable-next-line react-hooks/exhaustive-deps -- record is deliberately NOT a dependency: user edits must never re-trigger the fetch
    }, [id, kind]);

    // The save's double-submit fence: `busy` state is async (a fast double-click
    // re-entered before the re-render), and the create leg additionally rides an
    // idempotency key so even a raced double POST cannot mint two records.
    const savingRef = useRef(false);
    const createKeyRef = useRef<string | null>(null);
    // the state-machine transition buttons' own re-entry fence (see save's rule)
    const transitioningRef = useRef(false);

    const context: RendererContextValue = {
        mode: "runtime",
        clock: new Date().toISOString().replace(/\.\d{3}Z$/, ".000Z"),
        user: { name: "", roles: [], locale },
        role,
        fields: Object.fromEntries(entity.fields.map((field) => [field.apiName, field])),
        record,
        errors,
        busy,
        // the file-upload leg: same-origin base + the client's live token (the
        // renderer threads both to novaforge.file-upload nodes and binds the
        // uploaded attachment id back to the record field)
        files: { base: client.base, token: () => client.bearer() },
        getValue: (path) => {
            const head = path.split(".")[0]!;
            return record?.[head];
        },
        setValue: (path, value) => {
            setRecord((current) => ({ ...(current ?? {}), [path]: value }));
        },
        actions: {
            save: async () => {
                if (savingRef.current) {
                    return;
                }
                savingRef.current = true;
                setBusy(true);
                setErrors({});
                try {
                    const savedRecord = record?.id
                        ? await client.updateRecord(qualified(entity.apiName), String(record.id), Number(record.version ?? 1), record)
                        : await client.createRecord(
                              qualified(entity.apiName),
                              record ?? {},
                              // one key per unsaved draft: a re-click or a raced
                              // retry of the same create collapses server-side
                              createKeyRef.current ?? (createKeyRef.current = randomKey()),
                          );
                    createKeyRef.current = null;
                    setRecord(savedRecord);
                    setFlash({ message: "Saved", tone: "ok" });
                    if (!record?.id) {
                        navigate(entity.apiName, "detail", String(savedRecord.id));
                    }
                } catch (error) {
                    // every failure surfaces: the callers dispatch with `void`, so a
                    // rethrown non-ApiError (offline, gateway 502) was an unhandled
                    // rejection with no UI — and the previous "Saved" flash stayed
                    // on screen as active misinformation
                    if (error instanceof ApiError) {
                        setErrors(error.fieldErrors());
                        setFlash({ message: error.message, tone: "error" });
                    } else {
                        setFlash({ message: error instanceof Error ? error.message : "Save failed", tone: "error" });
                    }
                } finally {
                    savingRef.current = false;
                    setBusy(false);
                }
            },
            cancel: async () => navigate(entity.apiName, "list"),
            deleteRecord: async () => {
                if (record?.id) {
                    try {
                        await client.deleteRecord(qualified(entity.apiName), String(record.id), Number(record.version ?? 1));
                        navigate(entity.apiName, "list");
                    } catch (error) {
                        // a 409 (stale version) or 403 on delete was a silent
                        // unhandled rejection — the user clicked again and again
                        setFlash({ message: error instanceof Error ? error.message : "Delete failed", tone: "error" });
                    }
                }
            },
            openPage: async (target, targetId) => {
                const kindOf = target.endsWith("List") ? "list" : target.endsWith("Detail") ? "detail" : "form";
                const entityName = target.replace(/(Form|List|Detail)$/, "");
                // pageApiName lowercases its input, so the strip must resolve back
                // case-insensitively against the app's entities — a bare map lookup
                // on the stripped name returned undefined and EntityPage crashed
                // (found live at the golden journey: "customerForm" → "customer" ≠ "Customer")
                const resolved = app.entities.find(
                    (candidate) => candidate.apiName.toLowerCase() === entityName.toLowerCase(),
                );
                navigate(resolved?.apiName ?? entityName, kindOf, targetId);
            },
            runFlow: async (hook) => {
                // PHASE-3 §8: the named flow runs server-side (system principal, the
                // initiating actor recorded); the shell reloads the record's state after.
                if (!record?.id) {
                    setFlash({ message: "runFlow needs a saved record", tone: "error" });
                    return;
                }
                setBusy(true);
                try {
                    const fresh = await client.runHook(qualified(entity.apiName), String(record.id), hook);
                    setRecord(fresh);
                    setFlash({ message: `Ran ${hook}`, tone: "ok" });
                } catch (error) {
                    if (error instanceof ApiError) {
                        setErrors(error.fieldErrors());
                        setFlash({ message: error.message, tone: "error" });
                    } else {
                        setFlash({ message: error instanceof Error ? error.message : `Running ${hook} failed`, tone: "error" });
                    }
                } finally {
                    setBusy(false);
                }
            },
        },
        navigate,
        data,
        listFilter: filter,
        transitions: app.stateMachines
            .filter((machine) => machine.entity === entity.apiName && record)
            .flatMap((machine) => {
                const current = String((record as Record<string, unknown>)?.[machine.stateField] ?? machine.initial);
                return machine.transitions
                    .filter((transition) => transition.from === current)
                    .map((transition) => ({ to: transition.to, label: transition.to, guard: transition.guard }));
            }),
    };

    return (
        <>
            {stale.length > 0 ? (
                <p role="status" className="nf-stale">
                    {stale.length} page overlay(s) no longer apply after the last entity change.
                </p>
            ) : null}
            {loadError ? (
                <p role="alert" className="nf-error">
                    Could not load {entity.apiName}/{id}: {loadError}
                </p>
            ) : null}
            <PageRenderer page={page} entity={entity} context={context} />
            {context.transitions && context.transitions.length > 0 ? (
                <div className="nf-transitions" role="group" aria-label="State transitions">
                    {context.transitions.map((transition) => (
                        <button
                            key={transition.to}
                            type="button"
                            disabled={busy}
                            title={transition.guard ? `guard: ${transition.guard}` : undefined}
                            onClick={() => {
                                // The transition is a REAL versioned PATCH (the form
                                // save's own update leg), not a local flip: the old
                                // setRecord-only click showed a state change that
                                // silently reverted on the next reload. The ref fences
                                // the double-click the async `busy` state can't.
                                const machine = app.stateMachines.find((m) => m.entity === entity.apiName)!;
                                if (!record?.id || transitioningRef.current) return;
                                transitioningRef.current = true;
                                setBusy(true);
                                void client
                                    .updateRecord(
                                        qualified(entity.apiName),
                                        String(record.id),
                                        Number(record.version ?? 1),
                                        { [machine.stateField]: transition.to },
                                    )
                                    .then((fresh) => {
                                        // the SERVER's record — its state, its version —
                                        // never a locally guessed shape
                                        setRecord(fresh);
                                        setFlash({ message: `Moved to ${transition.to}`, tone: "ok" });
                                    })
                                    .catch((error: unknown) => {
                                        if (error instanceof ApiError) {
                                            setErrors(error.fieldErrors());
                                            setFlash({ message: error.message, tone: "error" });
                                        } else {
                                            setFlash(
                                                { message: error instanceof Error ? error.message : `Moving to ${transition.to} failed`, tone: "error" },
                                            );
                                        }
                                    })
                                    .finally(() => {
                                        transitioningRef.current = false;
                                        setBusy(false);
                                    });
                            }}
                        >
                            {transition.label ?? transition.to}
                        </button>
                    ))}
                </div>
            ) : null}
        </>
    );
}

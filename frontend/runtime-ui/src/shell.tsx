import { useMemo, useState, type ReactNode } from "react";
import {
    ApiError,
    PlatformClient,
    type PageDefinition,
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
    | { view: "entity"; entity: string; kind: "list" | "form" | "detail"; id?: string }
    | { view: "inbox" }
    | { view: "notifications" }
    | { view: "dashboards" };

function effectiveRole(app: AppDefinition, user: { roles: string[] }): string | undefined {
    // App-scoped roles arrive as `app.role` assignments; the shell maps the first
    // app-defined role the user holds (PHASE-2 §9: rendering only — the Data
    // Runtime enforces server-side).
    const appRoles = new Set(app.permissionSet.roles.map((role) => role.name));
    return user.roles.map((role) => role.split(".").pop() ?? role).find((role) => appRoles.has(role));
}

export function RuntimeShell({ client, published, user, versionKey }: RuntimeShellProps): ReactNode {
    const app = published.app as AppDefinition;
    const [route, setRoute] = useState<Route>({ view: "home" });
    const [locale, setLocale] = useState<string | undefined>(user.locale);
    const role = effectiveRole(app, user);
    const entities = useMemo(
        () => new Map(app.entities.map((entity) => [entity.apiName, entity])),
        [app.entities],
    );

    const data: RendererDataService = useMemo(
        () => ({
            list: (request) => client.list(request),
            search: async (target, term, size) => {
                const entity = entities.get(target);
                const field = entity?.displayField ?? "id";
                return client.search(target, term, field, size);
            },
        }),
        [client, entities],
    );

    const navigate = (entity: string, kind: "list" | "form" | "detail", id?: string) => {
        setRoute({ view: "entity", entity, kind, id });
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
        <div className="nf-runtime" data-metadata-version={versionKey}>
            <header className="nf-topbar">
                <h1>{app.label ?? app.apiName}</h1>
                <nav aria-label="Primary">
                    <button type="button" onClick={() => setRoute({ view: "home" })}>Home</button>
                    {nav.map((group) => (
                        <span key={group.label} className="nf-navgroup">
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
                    <Dashboards client={client} appApiName={app.apiName} app={app} role={role} />
                ) : (
                    <EntityPage
                        client={client}
                        entity={entities.get(route.entity) as EntityDefinition}
                        kind={route.kind}
                        id={route.id}
                        savedPages={savedPages}
                        app={app}
                        role={role}
                        locale={locale}
                        data={data}
                        navigate={navigate}
                    />
                )}
            </main>
        </div>
    );
}

interface EntityPageProps {
    client: PlatformClient;
    entity: EntityDefinition;
    kind: "list" | "form" | "detail";
    id?: string;
    savedPages: Map<string, PageDefinition>;
    app: AppDefinition;
    role?: string;
    locale?: string;
    data: RendererDataService;
    navigate: (entity: string, kind: "list" | "form" | "detail", id?: string) => void;
}

function EntityPage(props: EntityPageProps): ReactNode {
    const { client, entity, kind, id, savedPages, app, role, locale, data, navigate } = props;
    const saved = savedPages.get(`${entity.apiName}:${kind}`);
    const { page, stale } = resolvePage(saved, entity, { role, permissions: app.permissionSet, locale, kind });
    const [record, setRecord] = useState<Record<string, unknown> | null>(null);
    const [errors, setErrors] = useState<Record<string, string>>({});
    const [busy, setBusy] = useState(false);
    const [flash, setFlash] = useState<string | null>(null);

    const load = async (recordId: string): Promise<void> => {
        setRecord(await client.getRecord(entity.apiName, recordId));
    };

    if (kind !== "list" && id && record?.id !== id) {
        void load(id);
    }

    const context: RendererContextValue = {
        mode: "runtime",
        clock: new Date().toISOString().replace(/\.\d{3}Z$/, ".000Z"),
        user: { name: "", roles: [], locale },
        role,
        fields: Object.fromEntries(entity.fields.map((field) => [field.apiName, field])),
        record,
        errors,
        busy,
        getValue: (path) => {
            const head = path.split(".")[0]!;
            return record?.[head];
        },
        setValue: (path, value) => {
            setRecord((current) => ({ ...(current ?? {}), [path]: value }));
        },
        actions: {
            save: async () => {
                setBusy(true);
                setErrors({});
                try {
                    const savedRecord =
                        record?.id
                            ? await client.updateRecord(entity.apiName, String(record.id), Number(record.version ?? 1), record)
                            : await client.createRecord(entity.apiName, record ?? {});
                    setRecord(savedRecord);
                    setFlash("Saved");
                    if (!record?.id) {
                        navigate(entity.apiName, "detail", String(savedRecord.id));
                    }
                } catch (error) {
                    if (error instanceof ApiError) {
                        setErrors(error.fieldErrors());
                        setFlash(error.message);
                    } else {
                        throw error;
                    }
                } finally {
                    setBusy(false);
                }
            },
            cancel: async () => navigate(entity.apiName, "list"),
            deleteRecord: async () => {
                if (record?.id) {
                    await client.deleteRecord(entity.apiName, String(record.id), Number(record.version ?? 1));
                    navigate(entity.apiName, "list");
                }
            },
            openPage: async (target, targetId) => {
                const kindOf = target.endsWith("List") ? "list" : target.endsWith("Detail") ? "detail" : "form";
                const entityName = target.replace(/(Form|List|Detail)$/, "");
                navigate(entityName, kindOf, targetId);
            },
        },
        navigate,
        data,
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
            {flash ? (
                <p role="status" className="nf-flash" aria-live="polite">{flash}</p>
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
                                const machine = app.stateMachines.find((m) => m.entity === entity.apiName)!;
                                setRecord((current) => ({ ...(current ?? {}), [machine.stateField]: transition.to }));
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

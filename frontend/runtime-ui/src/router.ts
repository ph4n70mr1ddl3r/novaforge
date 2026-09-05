import type { QueryFilter } from "@novaforge/shared";

/** The shell's route — the URL is now its second, persistent face. */
export type Route =
    | { view: "home" }
    | { view: "entity"; entity: string; kind: "list" | "form" | "detail"; id?: string;
        /** A drill-through deep link's query-DSL payload (PHASE-5 §5). */
        filter?: QueryFilter }
    | { view: "inbox" }
    | { view: "notifications" }
    | { view: "dashboards" };

/**
 * Hash routing for the runtime shell (PHASE-2 §6): the route lives in the URL
 * fragment, so a refresh keeps your place, the browser's Back/Forward buttons
 * move through the app's own navigations, and a record page is a bookmarkable,
 * shareable deep link. Hash — not pushState paths — because the shells ship as
 * static bundles behind the gateway: a fragment needs zero server rewrites.
 *
 * Grammar:
 *   #/home                    (also the empty hash)
 *   #/inbox  #/notifications  #/dashboards
 *   #/e/{entity}/{kind}                       — list, or "new record" form
 *   #/e/{entity}/{kind}/{id}                  — detail (form carries no id)
 *   #/e/{entity}/{kind}[/{id}]?f={filter}     — the drill-through payload,
 *       JSON-encoded query DSL (§5), so a filtered list survives a refresh
 *
 * Decoding is paranoid: unknown screens, unknown entities, malformed kinds, and
 * junk filter payloads all degrade to `null` (the caller falls back to home)
 * rather than crashing a mount or trusting a hand-edited URL.
 */

/** QueryFilter guard for the URL-round-tripped payload — a leaf, or an and/or
 *  composite whose children recurse. Anything else (or a JSON.parse throw)
 *  silently drops the filter: the route survives, unfiltered. */
function isQueryFilter(value: unknown): value is QueryFilter {
    if (value === null || typeof value !== "object") return false;
    const candidate = value as Record<string, unknown>;
    if (typeof candidate.field === "string" && typeof candidate.op === "string") return true;
    if ((candidate.op === "and" || candidate.op === "or") && Array.isArray(candidate.children)) {
        return candidate.children.every(isQueryFilter);
    }
    return false;
}

const KINDS = ["list", "form", "detail"] as const;

export function encodeRoute(route: Route): string {
    switch (route.view) {
        case "home":
            return "#/home";
        case "inbox":
            return "#/inbox";
        case "notifications":
            return "#/notifications";
        case "dashboards":
            return "#/dashboards";
        case "entity": {
            let hash = `#/e/${encodeURIComponent(route.entity)}/${route.kind}`;
            if (route.id) hash += `/${encodeURIComponent(route.id)}`;
            if (route.filter) hash += `?f=${encodeURIComponent(JSON.stringify(route.filter))}`;
            return hash;
        }
    }
}

/** Decodes a location.hash into a Route, or null when it names nothing this
 *  app serves (empty/absent hash counts as home, not null). `entities` is the
 *  published app's apiName set — a deep link to an entity the app doesn't
 *  define must fall home, not crash the shell on an undefined definition. */
export function decodeRoute(hash: string, entities: ReadonlySet<string>): Route | null {
    const raw = hash.replace(/^#\/?/, "");
    if (raw === "" || raw === "home") return { view: "home" };
    if (raw === "inbox") return { view: "inbox" };
    if (raw === "notifications") return { view: "notifications" };
    if (raw === "dashboards") return { view: "dashboards" };
    const [path, query] = raw.split("?") as [string, string | undefined];
    const parts = path.split("/").map((part) => decodeURIComponent(part));
    if (parts[0] !== "e") return null;
    const entity = parts[1] ?? "";
    const kind = parts[2] as (typeof KINDS)[number];
    if (!entities.has(entity) || !KINDS.includes(kind)) return null;
    const route: Route = { view: "entity", entity, kind };
    if (parts[3]) route.id = parts[3];
    if (query?.startsWith("f=")) {
        try {
            const filter: unknown = JSON.parse(decodeURIComponent(query.slice(2)));
            if (isQueryFilter(filter)) route.filter = filter;
        } catch {
            // a malformed payload drops the filter and keeps the route
        }
    }
    return route;
}

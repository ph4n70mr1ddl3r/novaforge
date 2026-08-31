import { hydrateRoot } from "react-dom/client";
import { createElement as h, useState, useEffect, type ReactNode } from "react";

const { createElement } = { createElement: h };
import { PlatformClient } from "@novaforge/shared";
import { RuntimeShell } from "./shell.tsx";
import { login, restoreSession, sessionManager, type OidcConfig, type OidcSession } from "./auth.ts";

/**
 * The runtime entry point: same-origin static bundle behind the gateway (§13 Q5).
 * `window.novaforge` carries deployment config (issuer/base) from index.html.
 */

declare global {
    interface Window {
        novaforge?: { issuer?: string; base?: string };
    }
}

const config: OidcConfig = {
    issuer: window.novaforge?.issuer ?? "http://localhost:8082/realms/novaforge",
};

function Root(): ReactNode {
    const [session, setSession] = useState<OidcSession | null>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        restoreSession(config)
            .then(setSession)
            .catch((caught: unknown) => setError(String(caught)));
    }, []);

    if (error) {
        return h("p", { role: "alert" }, `Sign-in failed: ${error}`);
    }
    if (!session) {
        return h(
            "main",
            { className: "nf-signin" },
            h("h1", null, "NovaForge"),
            h("button", { type: "button", onClick: () => void login(config) }, "Sign in"),
        );
    }
    // the manager keeps tokens alive past the access token's lifetime (refresh on
    // the margin, single-flight, one 401 retry per request) — the shell's session
    // view still carries the claims/roles from restore
    const manager = sessionManager(config, session);
    const client = new PlatformClient(
        window.novaforge?.base ?? "",
        () => manager.token(),
        fetch.bind(globalThis),
        () => manager.refreshOnUnauthorized(),
    );
    return h(RuntimeBridge, { client, session });
}

function RuntimeBridge({ client, session }: { client: PlatformClient; session: OidcSession }): ReactNode {
    const [published, setPublished] = useState<{ appId: string; version: number; app: Record<string, unknown> } | null>(null);
    const [error, setError] = useState<string | null>(null);
    useEffect(() => {
        // The published-apps index → first app (v1 single-app runtime shell).
        client
            .listApps()
            .then(async (apps) => {
                const first = apps[0] as { id?: string } | undefined;
                if (!first?.id) throw new Error("no apps available");
                const bundle = await client.published(first.id);
                setPublished({ appId: first.id, version: bundle.version, app: bundle.app });
            })
            .catch((caught: unknown) => setError(caught instanceof Error ? caught.message : String(caught)));
    }, [client]);
    if (error) {
        return h("p", { role: "alert" }, error);
    }
    if (!published) {
        return h("p", { role: "status" }, "Loading…");
    }
    const claims = session.claims;
    return createElement(RuntimeShell, {
        client,
        published: { version: published.version, app: published.app } as never,
        user: {
            name: String(claims.preferred_username ?? "user"),
            roles: Array.isArray(claims.platform_roles) ? (claims.platform_roles as string[]) : [],
            locale: typeof claims.locale === "string" ? claims.locale : undefined,
        },
        versionKey: String(published.version),
    });
}

import { ErrorBoundary } from "@novaforge/shared";

hydrateRoot(
    document.getElementById("root")!,
    h(ErrorBoundary, { label: "NovaForge" }, h(Root)),
);

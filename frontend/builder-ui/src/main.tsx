import { createElement, useState, useEffect, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { PlatformClient } from "@novaforge/shared";
import { BuilderShell } from "./shell.tsx";
import { login, restoreSession, sessionManager, type OidcConfig, type OidcSession } from "./auth.ts";
import { ErrorBoundary } from "@novaforge/shared";
/** The builder entry point — same OIDC flow as the runtime shell, builder-gated APIs. */
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
    if (error) return createElement("p", { role: "alert" }, `Sign-in failed: ${error}`);
    if (!session) {
        return createElement(
            "main",
            { className: "nf-signin" },
            createElement("h1", null, "NovaForge Builder"),
            createElement("button", { type: "button", onClick: () => void login(config) }, "Sign in"),
        );
    }
    // the manager keeps tokens alive past the access token's lifetime (refresh on
    // the margin, single-flight, one 401 retry per request)
    const manager = sessionManager(config, session);
    const client = new PlatformClient(
        window.novaforge?.base ?? "",
        () => manager.token(),
        fetch.bind(globalThis),
        () => manager.refreshOnUnauthorized(),
    );
    const roles = Array.isArray(session.claims.platform_roles) ? (session.claims.platform_roles as string[]) : [];
    return createElement(BuilderShell, { client, role: roles[0] });
}
// createRoot, not hydrateRoot: the SPA is client-only — the gateway-served
// index.html ships an EMPTY #root, so there is nothing to hydrate and React 19
// throws a hydration mismatch on every boot, regenerating the whole tree (the
// thrown error even escapes asynchronously and fails the test run under load).
createRoot(document.getElementById("root")!).render(
    // Root is an ELEMENT, not a call: invoking Root() at module scope ran
    // useState outside any render — React's dispatcher is null there and the
    // whole SPA died at boot (found live by the golden journey's builder leg,
    // masked since the twelfth pass by the never-rebuilt gitignored bundle)
    createElement(ErrorBoundary, { label: "The builder" }, createElement(Root)),
);

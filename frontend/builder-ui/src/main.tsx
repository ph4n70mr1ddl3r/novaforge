import { createElement, useState, useEffect, type ReactNode } from "react";
import { hydrateRoot } from "react-dom/client";
import { PlatformClient } from "@novaforge/shared";
import { BuilderShell } from "./shell.tsx";
import { login, restoreSession, type OidcConfig, type OidcSession } from "./auth.ts";

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
    const client = new PlatformClient(window.novaforge?.base ?? "", () => session.accessToken);
    const roles = Array.isArray(session.claims.platform_roles) ? (session.claims.platform_roles as string[]) : [];
    return createElement(BuilderShell, { client, role: roles[0] });
}

hydrateRoot(document.getElementById("root")!, createElement(Root));

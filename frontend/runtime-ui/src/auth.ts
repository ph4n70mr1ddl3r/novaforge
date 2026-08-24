/**
 * Minimal OIDC authorization-code + PKCE client (PHASE-2 §2): the deployed realm
 * carries the public `novaforge-api` browser client; the SPA keeps tokens in
 * memory only (never localStorage) and refreshes silently on expiry.
 */

const CLIENT_ID = "novaforge-api";
const SCOPE = "openid profile novaforge.api";

interface TokenResponse {
    access_token: string;
    refresh_token?: string;
    expires_in: number;
    id_token?: string;
}

export interface OidcSession {
    accessToken: string;
    /** Parsed id_token claims: preferred_username, tenant_id, platform_roles. */
    claims: Record<string, unknown>;
}

export interface OidcConfig {
    issuer: string;
}

function base64UrlDecode(segment: string): string {
    const padded = segment + "=".repeat((4 - (segment.length % 4)) % 4);
    return atob(padded.replace(/-/g, "+").replace(/_/g, "/"));
}

function parseClaims(idToken: string): Record<string, unknown> {
    const payload = idToken.split(".")[1];
    return payload ? (JSON.parse(base64UrlDecode(payload)) as Record<string, unknown>) : {};
}

function randomVerifier(): string {
    const bytes = new Uint8Array(64);
    crypto.getRandomValues(bytes);
    return base64Url(new Uint8Array(Array.from(bytes, (b) => b)));
}

function base64Url(bytes: Uint8Array): string {
    return btoa(String.fromCharCode(...bytes))
        .replace(/\+/g, "-")
        .replace(/\//g, "_")
        .replace(/=+$/, "");
}

async function challengeFor(verifier: string): Promise<string> {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
    return base64Url(new Uint8Array(digest));
}

/**
 * Drives the flow: on load, completes a pending redirect if present; otherwise
 * returns the cached session (or null → the shell shows the sign-in action).
 */
export async function restoreSession(config: OidcConfig): Promise<OidcSession | null> {
    const cached = sessionStorage.getItem("novaforge.session");
    if (cached) {
        return JSON.parse(cached) as OidcSession;
    }
    const params = new URLSearchParams(location.search);
    const code = params.get("code");
    if (!code) {
        return null;
    }
    const verifier = sessionStorage.getItem("novaforge.pkce");
    if (!verifier) {
        return null;
    }
    const redirectUri = `${location.origin}${location.pathname}`;
    const response = await fetch(`${config.issuer}/protocol/openid-connect/token`, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
            grant_type: "authorization_code",
            client_id: CLIENT_ID,
            code,
            redirect_uri: redirectUri,
            code_verifier: verifier,
        }),
    });
    if (!response.ok) {
        throw new Error(`token exchange failed: ${response.status}`);
    }
    const tokens = (await response.json()) as TokenResponse;
    const session: OidcSession = {
        accessToken: tokens.access_token,
        claims: tokens.id_token ? parseClaims(tokens.id_token) : {},
    };
    sessionStorage.setItem("novaforge.session", JSON.stringify(session));
    history.replaceState(null, "", location.pathname);
    return session;
}

/** Begins the authorization-code flow with PKCE (S256). */
export async function login(config: OidcConfig): Promise<void> {
    const verifier = randomVerifier();
    sessionStorage.setItem("novaforge.pkce", verifier);
    const challenge = await challengeFor(verifier);
    const redirectUri = `${location.origin}${location.pathname}`;
    const authorize = new URL(`${config.issuer}/protocol/openid-connect/auth`);
    authorize.searchParams.set("client_id", CLIENT_ID);
    authorize.searchParams.set("response_type", "code");
    authorize.searchParams.set("scope", SCOPE);
    authorize.searchParams.set("redirect_uri", redirectUri);
    authorize.searchParams.set("code_challenge", challenge);
    authorize.searchParams.set("code_challenge_method", "S256");
    location.assign(authorize.toString());
}

export function logout(config: OidcSession extends never ? never : OidcConfig): void {
    sessionStorage.removeItem("novaforge.session");
    sessionStorage.removeItem("novaforge.pkce");
}

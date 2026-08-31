/**
 * Minimal OIDC authorization-code + PKCE client (PHASE-2 §2): the deployed realm
 * carries the public `novaforge-api` browser client. The session lives in
 * `sessionStorage` — it survives reloads within the tab and dies with it, a
 * deliberate middle ground between localStorage persistence and memory-only
 * (XSS still reads sessionStorage, but nothing outlives the tab). Access tokens
 * are expiry-checked on restore and silently refreshed via the refresh-token
 * grant while it is valid; an unrecoverable session clears and the shell shows
 * the sign-in action again.
 */

const CLIENT_ID = "novaforge-api";
const SCOPE = "openid novaforge.api";

/** Refresh when the access token is within this window of expiring (ms). */
const EXPIRY_MARGIN_MS = 30_000;

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
    /** When the access token expires (epoch ms) — restore checks it. */
    expiresAt: number;
    /** Present when the realm issued one; drives silent refresh on restore. */
    refreshToken?: string;
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

function persist(session: OidcSession): void {
    sessionStorage.setItem("novaforge.session", JSON.stringify(session));
}

function clearSession(): void {
    sessionStorage.removeItem("novaforge.session");
    sessionStorage.removeItem("novaforge.pkce");
}

function sessionFrom(tokens: TokenResponse): OidcSession {
    return {
        accessToken: tokens.access_token,
        claims: tokens.id_token ? parseClaims(tokens.id_token) : {},
        expiresAt: Date.now() + tokens.expires_in * 1000,
        refreshToken: tokens.refresh_token,
    };
}

async function tokenExchange(config: OidcConfig, body: URLSearchParams): Promise<TokenResponse> {
    const response = await fetch(`${config.issuer}/protocol/openid-connect/token`, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body,
    });
    if (!response.ok) {
        throw new Error(`token exchange failed: ${response.status}`);
    }
    return (await response.json()) as TokenResponse;
}

/** Silent refresh via the refresh-token grant; null when it cannot recover. */
async function refreshSession(config: OidcConfig, refreshToken: string): Promise<OidcSession | null> {
    try {
        const tokens = await tokenExchange(config, new URLSearchParams({
            grant_type: "refresh_token",
            client_id: CLIENT_ID,
            refresh_token: refreshToken,
        }));
        const session = sessionFrom(tokens);
        persist(session);
        return session;
    } catch {
        return null;
    }
}

/**
 * Drives the flow: a cached session returns while its access token lives (or
 * refreshes silently when expired and a refresh token is held); otherwise a
 * pending redirect completes; otherwise null → the shell shows the sign-in
 * action.
 */
export async function restoreSession(config: OidcConfig): Promise<OidcSession | null> {
    const cached = sessionStorage.getItem("novaforge.session");
    if (cached) {
        try {
            const session = JSON.parse(cached) as OidcSession;
            if (typeof session.expiresAt === "number"
                    && Date.now() < session.expiresAt - EXPIRY_MARGIN_MS) {
                return session;
            }
            if (session.refreshToken) {
                const refreshed = await refreshSession(config, session.refreshToken);
                if (refreshed) {
                    return refreshed;
                }
            }
            // expired beyond recovery — drop it and fall through to the code flow
            clearSession();
        } catch {
            clearSession();
        }
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
    const tokens = await tokenExchange(config, new URLSearchParams({
        grant_type: "authorization_code",
        client_id: CLIENT_ID,
        code,
        redirect_uri: redirectUri,
        code_verifier: verifier,
    }));
    const session = sessionFrom(tokens);
    persist(session);
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
    clearSession();
}

/**
 * The live session manager (2026-08-31, thirteenth pass): refreshes were wired only
 * into restoreSession — one refresh per page load — so an SPA in use for longer
 * than the access token's lifetime (5 minutes against the realm defaults) failed
 * every API call with a 401 until a manual reload discarded unsaved state. The
 * manager owns the current session: `token()` proactively refreshes inside the
 * expiry margin, `refreshOnUnauthorized()` is the client's 401 recovery hook, and
 * concurrent callers share one in-flight refresh (single-flight — N parallel 401s
 * must not fire N refresh grants against a rotating refresh token).
 */
export interface SessionManager {
    /** A usable access token, refreshing inside the margin; "" when unrecoverable. */
    token(): Promise<string>;
    /** The 401 hook: single-flight refresh, the fresh token or null when signed out. */
    refreshOnUnauthorized(): Promise<string | null>;
    /** The manager's current session view (null after an unrecoverable refresh). */
    current(): OidcSession | null;
}

export function sessionManager(config: OidcConfig, initial: OidcSession | null): SessionManager {
    let session: OidcSession | null = initial;
    let inFlight: Promise<OidcSession | null> | null = null;

    const doRefresh = (): Promise<OidcSession | null> => {
        if (!inFlight) {
            inFlight = (async () => {
                const prior = session;
                if (!prior?.refreshToken) {
                    return null;
                }
                const next = await refreshSession(config, prior.refreshToken);
                if (next) {
                    session = next;
                    return next;
                }
                // unrecoverable — the session dies with the tab's storage
                clearSession();
                session = null;
                return null;
            })().finally(() => {
                inFlight = null;
            });
        }
        return inFlight;
    };

    return {
        async token(): Promise<string> {
            if (session && Date.now() < session.expiresAt - EXPIRY_MARGIN_MS) {
                return session.accessToken;
            }
            const refreshed = await doRefresh();
            // a stale token still goes out when refresh is impossible — the request
            // 401s, the client's hook refreshes once, and failure then surfaces
            return refreshed?.accessToken ?? session?.accessToken ?? "";
        },
        async refreshOnUnauthorized(): Promise<string | null> {
            return (await doRefresh())?.accessToken ?? null;
        },
        current: () => session,
    };
}

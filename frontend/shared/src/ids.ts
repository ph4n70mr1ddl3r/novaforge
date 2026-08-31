/**
 * A random id safe to mint in ANY browser context: crypto.randomUUID exists only
 * in secure contexts (HTTPS or localhost) — on a plain-HTTP origin (a LAN demo,
 * a non-TLS ingress) it throws, and any call site that reached for it unguarded
 * (a palette insert, the create path's idempotency key) would brick. The
 * Math.random twin carries no security — node keys and idempotency keys only.
 */
export function randomKey(): string {
    if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
        return crypto.randomUUID();
    }
    return "idem-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 12);
}

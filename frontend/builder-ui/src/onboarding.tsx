import { useState, type ReactNode } from "react";
import type { PlatformClient } from "@novaforge/shared";

/**
 * Tenant onboarding (PHASE-2 §10): platform-admin driven — create tenant → first
 * admin → first app → land in the entity builder. Fully scripted in API terms;
 * the target is < 5 minutes of platform-admin time.
 */

type Step = "tenant" | "admin" | "app" | "done";

export function Onboarding({ client, onAppCreated }: { client: PlatformClient; onAppCreated: (appId: string) => void }): ReactNode {
    const [step, setStep] = useState<Step>("tenant");
    const [tenantId, setTenantId] = useState<string>("");
    const [adminUserId, setAdminUserId] = useState<string>("");
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);

    const run = async (action: () => Promise<void>): Promise<void> => {
        setBusy(true);
        setError(null);
        try {
            await action();
        } catch (caught) {
            setError(caught instanceof Error ? caught.message : String(caught));
        } finally {
            setBusy(false);
        }
    };

    return (
        <section className="nf-b-onboarding" aria-label="Tenant onboarding">
            <h2>Tenant onboarding</h2>
            <ol className="nf-steps" aria-label="Progress">
                {(["tenant", "admin", "app", "done"] as Step[]).map((name, index) => (
                    <li key={name} aria-current={step === name ? "step" : undefined}>
                        {index + 1}. {name}
                    </li>
                ))}
            </ol>
            {error ? <p role="alert">{error}</p> : null}
            {step === "tenant" ? (
                <form
                    onSubmit={(event) => {
                        event.preventDefault();
                        const form = new FormData(event.currentTarget);
                        void run(async () => {
                            // the platform admin API's exact shape (PHASE-2 §10):
                            // apiName + displayName + the first admin's credentials —
                            // found live at the golden-journey wiring: the form used to
                            // send `label` and never a password, and the stubbed journey
                            // could not see the API rejecting it.
                            const tenant = (await client.createTenant({
                                apiName: String(form.get("apiName")),
                                displayName: String(form.get("displayName")),
                                adminUsername: String(form.get("adminUsername")),
                                adminEmail: String(form.get("adminEmail")),
                                adminPassword: String(form.get("adminPassword")),
                            })) as { tenantId?: string; id?: string; adminUserId?: string };
                            setTenantId(String(tenant.tenantId ?? tenant.id ?? ""));
                            setAdminUserId(String(tenant.adminUserId ?? ""));
                            setStep("admin");
                        });
                    }}
                >
                    <label>Tenant apiName <input name="apiName" required /></label>
                    <label>Tenant display name <input name="displayName" required /></label>
                    <label>First admin username <input name="adminUsername" required /></label>
                    <label>First admin email <input name="adminEmail" type="email" required /></label>
                    <label>First admin password <input name="adminPassword" type="password" required /></label>
                    <button type="submit" className="nf-action-primary" disabled={busy}>Create tenant + admin</button>
                </form>
            ) : null}
            {step === "admin" ? (
                <form
                    onSubmit={(event) => {
                        event.preventDefault();
                        const form = new FormData(event.currentTarget);
                        void run(async () => {
                            // the platform admin API's exact shape: the provisioned
                            // admin's userId (createTenant returns it) + the role —
                            // the first admin already carries admin/builder/user from
                            // provisioning, so this step grants any additional role
                            await client.assignRole(tenantId, {
                                userId: adminUserId,
                                role: String(form.get("role")),
                            });
                            setStep("app");
                        });
                    }}
                >
                    <p role="status">Tenant {tenantId} created — assign the first admin roles.</p>
                    <label>Admin userId <input value={adminUserId} readOnly aria-label="Admin userId" /></label>
                    <label>Role (e.g. admin) <input name="role" defaultValue="admin" required /></label>
                    <button type="submit" className="nf-action-primary" disabled={busy}>Assign role</button>
                </form>
            ) : null}
            {step === "app" ? (
                <form
                    onSubmit={(event) => {
                        event.preventDefault();
                        const form = new FormData(event.currentTarget);
                        void run(async () => {
                            const app = (await client.createApp({
                                apiName: String(form.get("apiName")),
                                label: String(form.get("label")),
                                entities: [],
                            })) as { id?: string };
                            setStep("done");
                            onAppCreated(String(app?.id ?? ""));
                        });
                    }}
                >
                    <label>App apiName (PascalCase) <input name="apiName" pattern="[A-Z][A-Za-z0-9]*" required /></label>
                    <label>App label <input name="label" /></label>
                    <button type="submit" className="nf-action-primary" disabled={busy}>Create first app</button>
                </form>
            ) : null}
            {step === "done" ? (
                <p role="status">Done — land in the <a href="#entities">entity builder</a>.</p>
            ) : null}
        </section>
    );
}

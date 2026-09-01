import { expect, test, type Page } from "@playwright/test";

/**
 * The PHASE-2 §11 item-3 golden journey, scripted end to end against the live
 * stack: *build a 3-entity app (customers/orders/lines) purely via the builder
 * UI* — tenant onboarding (§10's three steps), three entities with fields and a
 * lookup relationship, dev publish from the lifecycle screen — then run it in the
 * runtime shell: auto-generated list/form pages with zero page definitions (§5),
 * a record created through the real renderer and visible in the server-paged list.
 *
 * Every step is UI-driven — the only API call the journey makes is none; the
 * builder and runtime ride their own gateway clients. The app's apiName sorts
 * first alphabetically (the apps index is ORDER BY api_name and both shells open
 * apps[0]), and carries a per-run suffix so the journey is idempotent.
 */

const RUN = Date.now().toString(36);
// A DESCENDING suffix (subtracted from a large constant): every run's app sorts
// BEFORE all earlier journey apps — the apps index is ORDER BY api_name and both
// shells open apps[0], so the journey must own the first slot.
const SUFFIX = (9_999_999_999_999_999 - Date.now()).toString(36).padStart(12, "0");
const APP = `Aaa${SUFFIX}`;
const TENANT_LABEL = `E2E ${RUN}`;
const ADMIN = `e2e-admin-${RUN}`;

test.beforeEach(async ({ request }) => {
    // journey hygiene (not a journey step): remove prior runs' journey apps so the
    // demo workspace stays clean and apps[0] is unambiguous
    const token = await (await request.post(
        "http://localhost:8082/realms/novaforge/protocol/openid-connect/token",
        { form: { grant_type: "password", client_id: "novaforge-api", username: "demo", password: "demo" } },
    )).json();
    const auth = { Authorization: `Bearer ${token.access_token}` };
    const apps = (await (await request.get("http://localhost:8081/api/v1/metadata/apps", { headers: auth })).json()) as { id: string; apiName: string }[];
    for (const app of apps.filter((candidate) => candidate.apiName.startsWith("Aaa"))) {
        await request.delete(`http://localhost:8081/api/v1/metadata/apps/${app.id}`, { headers: auth });
    }
});

test("golden journey: 3-entity app built via the builder, run in the runtime", async ({ page }) => {
    test.setTimeout(240_000);

    // --- builder: sign in through the real PKCE flow (Keycloak 26 login form)
    await page.goto("/builder");
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.waitForURL(/localhost:8082/, { timeout: 60_000 });
    await page.locator("#username").fill("demo");
    await page.locator("#password").fill("demo");
    await page.locator("#kc-login").click();
    await page.waitForURL(/localhost:8080\/builder/, { timeout: 60_000 });

    // --- §10 onboarding: tenant → first-admin roles → first app
    await page.getByRole("button", { name: "onboarding" }).click();
    await page.getByLabel("Tenant apiName").fill(`e2e-${RUN}`);
    await page.getByLabel("Tenant display name").fill(TENANT_LABEL);
    await page.getByLabel("First admin username").fill(ADMIN);
    await page.getByLabel("First admin email").fill(`${ADMIN}@example.test`);
    await page.getByLabel("First admin password").fill("e2e-secret-1");
    await page.getByRole("button", { name: "Create tenant + admin" }).click();
    await expect(page.getByText(/Tenant .* created/)).toBeVisible();
    // the first admin already carries admin/builder/user; the step re-affirms admin
    await page.getByRole("button", { name: "Assign role" }).click();
    await expect(page.getByLabel("App apiName (PascalCase)")).toBeVisible({ timeout: 30_000 });
    await page.getByLabel("App apiName (PascalCase)").fill(APP);
    await page.getByLabel("App label", { exact: false }).fill(`E2E ${RUN}`);
    await page.getByRole("button", { name: "Create first app" }).click();

    // --- §8/T7: three entities through the entity builder (fields + lookup target)
    // wait for the shell to finish loading the NEW app (its late async swap would
    // otherwise race the first keystrokes — the empty-app editor state is the signal)
    await expect(page.getByText("Select or create an entity.")).toBeVisible({ timeout: 60_000 });
    await expect(page.getByRole("button", { name: "New entity" })).toBeVisible();

    // Customer — the seeded `name` field is already right
    await page.getByRole("button", { name: "New entity" }).click();
    await page.getByLabel("Entity apiName").fill("Customer");
    await page.getByRole("button", { name: "Save entity" }).click();
    await expect(page.getByRole("button", { name: "Customer", exact: true })).toBeVisible({ timeout: 120_000 });

    // Order — name + a lookup to Customer (the relationship authors as a typed field)
    await page.getByRole("button", { name: "New entity" }).click();
    await page.getByLabel("Entity apiName").fill("Order");
    await page.getByRole("button", { name: "Add field" }).click();
    await page.getByLabel("apiName row 1").fill("customer");
    await page.getByLabel("type row 1").selectOption("lookup");
    await page.getByLabel("target row 1").selectOption("Customer");
    await page.getByRole("button", { name: "Save entity" }).click();
    await expect(page.getByRole("button", { name: "Order", exact: true })).toBeVisible({ timeout: 120_000 });

    // OrderLine — name + qty
    await page.getByRole("button", { name: "New entity" }).click();
    await page.getByLabel("Entity apiName").fill("OrderLine");
    await page.getByRole("button", { name: "Add field" }).click();
    await page.getByLabel("apiName row 1").fill("qty");
    await page.getByLabel("type row 1").selectOption("int");
    await page.getByRole("button", { name: "Save entity" }).click();
    await expect(page.getByRole("button", { name: "OrderLine", exact: true })).toBeVisible({ timeout: 120_000 });

    // --- §9/T9: RBAC — a `user` role mapped from the demo user's platform role
    // (the shell maps `app.role` assignments by suffix), read+create on all three
    await page.getByRole("button", { name: "rbac" }).click();
    await page.getByLabel("New role name").fill("user");
    await page.getByRole("button", { name: "Add role" }).click();
    // the matrix row for `user`; cells follow the entity column order (the three
    // entities are the app's only ones — columns 1..3)
    const matrixRow = page.getByRole("row").filter({ hasText: "user" }).filter({
        has: page.getByLabel("read", { exact: true }),
    });
    for (let column = 0; column < 3; column++) {
        const cell = matrixRow.locator("td").nth(column);
        for (const action of ["read", "create"]) {
            await cell.getByLabel(action, { exact: true }).check();
        }
    }
    await page.getByRole("button", { name: "Save permissions" }).click();
    await expect(page.getByText("Permissions saved")).toBeVisible({ timeout: 60_000 });

    // --- §4: publish the dev version from the builder (the lifecycle screen)
    await page.getByRole("button", { name: "lifecycle" }).click();
    await page.getByTestId("publish").click();
    await expect(page.getByRole("status")).toContainText("Published v", { timeout: 60_000 });

    // --- runtime: the published app renders with zero page definitions (§5)
    const runtime: Page = await page.context().newPage();
    await runtime.goto("/runtime");
    await runtime.getByRole("button", { name: "Sign in" }).click();
    // SSO-aware: the builder's Keycloak session may complete the redirect without
    // ever showing the login form — decide on what renders, not on URLs (the
    // starting URL already matches /runtime, so a URL race resolves instantly)
    const loginForm = runtime.locator("#username");
    const shell = runtime.getByText("Select a record type to begin.");
    await Promise.race([
        loginForm.waitFor({ state: "visible", timeout: 45_000 }),
        shell.waitFor({ state: "visible", timeout: 45_000 }),
    ]);
    if (await loginForm.isVisible().catch(() => false)) {
        await loginForm.fill("demo");
        await runtime.locator("#password").fill("demo");
        await runtime.locator("#kc-login").click();
    }
    await shell.waitFor({ state: "visible", timeout: 60_000 });

    // module-grouped nav carries the three entities
    await expect(runtime.getByRole("button", { name: "Customer", exact: true })).toBeVisible({ timeout: 60_000 });
    await runtime.getByRole("button", { name: "Customer", exact: true }).click();
    await expect(runtime.getByText(/\d+ records?/)).toBeVisible();
    const before = Number(await runtime.getByText(/\d+ records?/).textContent().then((t) => /\d+/.exec(t ?? "")?.[0]));

    // create a customer through the auto-generated form (the real renderer, L1 defaults)
    await runtime.getByRole("button", { name: /new|add/i }).click();
    await runtime.getByLabel("name").fill("Acme E2E");
    await runtime.getByRole("button", { name: "Save" }).click();

    // save lands on the record's detail view (the shell's save action), then the
    // list — both server-paged and real
    await expect(runtime.getByText("Saved")).toBeVisible({ timeout: 60_000 });
    await runtime.getByRole("button", { name: "Customer", exact: true }).click();
    await expect(runtime.getByText("Acme E2E").first()).toBeVisible({ timeout: 60_000 });
    const after = Number(await runtime.getByText(/\d+ records?/).textContent().then((t) => /\d+/.exec(t ?? "")?.[0]));
    expect(after).toBe(before + 1);
});

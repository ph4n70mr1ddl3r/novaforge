import { useEffect, useState, type ReactNode } from "react";
import {
    type AppDefinition,
    type ConnectorDefinition,
    type ConnectorOperation,
    type CredentialDefinition,
    type ImportDefinition,
    type PlatformClient,
    type WebhookDefinition,
} from "@novaforge/shared";

/**
 * The integrations authoring + operational surface (PHASE-6 §3/§9): connectors,
 * webhooks (both directions), credential references, and import mappings — the
 * Integrations branch of the app definition, saved through the same draft APIs as
 * every other branch. The secret *material* never rides metadata: provisioning a
 * credential or webhook secret PUTs it straight to the Integration Service's
 * encrypted store (§9). The delivery log and DLQ (with replay) render beside the
 * editors — the §3 operational surface.
 */

export interface IntegrationsProps {
    app: AppDefinition;
    client: PlatformClient;
    /** Saves a metadata branch patch ({integrations}). */
    onSave: (patch: Record<string, unknown>) => Promise<void>;
}

export function Integrations({ app, client, onSave }: IntegrationsProps): ReactNode {
    const branch = app.integrations ?? {};
    const [connectors, setConnectors] = useState<ConnectorDefinition[]>(branch.connectors ?? []);
    const [webhooks, setWebhooks] = useState<WebhookDefinition[]>(branch.webhooks ?? []);
    const [credentials, setCredentials] = useState<CredentialDefinition[]>(branch.credentials ?? []);
    const [imports, setImports] = useState<ImportDefinition[]>(branch.imports ?? []);
    const [deliveries, setDeliveries] = useState<Record<string, unknown>[] | null>(null);
    const [dlq, setDlq] = useState<Record<string, unknown>[] | null>(null);
    const [jobs, setJobs] = useState<Record<string, unknown>[] | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [flash, setFlash] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);

    const reloadOps = (): void => {
        client
            .integrationDeliveries()
            .then(setDeliveries)
            .catch(() => setDeliveries([]));
        client
            .integrationDlq()
            .then(setDlq)
            .catch(() => setDlq([]));
        client
            .integrationJobs()
            .then(setJobs)
            .catch(() => setJobs([]));
    };

    useEffect(reloadOps, [client]);

    const save = async (what: string): Promise<void> => {
        setError(null);
        setBusy(true);
        try {
            await onSave({ integrations: { connectors, webhooks, credentials, imports } });
            setFlash(`Saved ${what}`);
        } catch (caught) {
            setError(caught instanceof Error ? caught.message : String(caught));
        } finally {
            setBusy(false);
        }
    };

    return (
        <section className="nf-b-integrations" aria-label="Integrations">
            {error ? <p role="alert">{error}</p> : null}
            {flash ? <p role="status" aria-live="polite">{flash}</p> : null}
            <ConnectorEditor entities={app.entities.map((entity) => entity.apiName)}
                credentials={credentials} connectors={connectors} onChange={setConnectors}
                busy={busy} onSave={() => save("connectors")} />
            <CredentialEditor credentials={credentials} onChange={setCredentials} client={client}
                busy={busy} onSave={() => save("credentials")} />
            <WebhookEditor entities={app.entities.map((entity) => entity.apiName)}
                webhooks={webhooks} onChange={setWebhooks} client={client}
                busy={busy} onSave={() => save("webhooks")} />
            <ImportEditor entities={app.entities.map((entity) => entity.apiName)}
                imports={imports} onChange={setImports} busy={busy}
                onSave={() => save("import mappings")} />
            <DeliveryLog rows={deliveries} />
            <DlqPanel rows={dlq} client={client} onReplayed={reloadOps} />
            <JobsPanel rows={jobs} client={client} onChanged={reloadOps} />
        </section>
    );
}

// --- connectors (§3): operations on a base URL, credentials by reference ---

function ConnectorEditor({
    entities,
    credentials,
    connectors,
    onChange,
    busy,
    onSave,
}: {
    entities: string[];
    credentials: CredentialDefinition[];
    connectors: ConnectorDefinition[];
    onChange: (connectors: ConnectorDefinition[]) => void;
    busy?: boolean;
    onSave: () => Promise<void>;
}): ReactNode {
    const update = (id: string, changes: Partial<ConnectorDefinition>): void =>
        onChange(connectors.map((connector) =>
            connector.id === id ? { ...connector, ...changes } : connector));
    const updateOperation = (id: string, name: string, changes: Partial<ConnectorOperation>): void =>
        update(id, {
            operations: (connectors.find((c) => c.id === id)?.operations ?? []).map((operation) =>
                operation.name === name ? { ...operation, ...changes } : operation),
        });
    return (
        <fieldset>
            <legend>Connectors</legend>
            <p className="nf-b-meta">
                REST operations on a base URL — path/query/header/body templates use the
                shared <code>{"${…}"}</code> convention. Secrets live in the encrypted
                store, referenced by credential id (never in this metadata).
            </p>
            {connectors.map((connector) => (
                <div key={connector.id} className="nf-connector" data-connector={connector.id}>
                    <input aria-label={`Connector id ${connector.id}`} placeholder="id" value={connector.id}
                        onChange={(e) => {
                            const oldId = connector.id;
                            update(oldId, { id: e.target.value });
                        }} />
                    <input aria-label={`Connector base URL ${connector.id}`} placeholder="https://api.example.local"
                        value={connector.baseUrl}
                        onChange={(e) => update(connector.id, { baseUrl: e.target.value })} />
                    <select aria-label={`Connector credential ${connector.id}`}
                        value={connector.credential ?? ""}
                        onChange={(e) => update(connector.id, { credential: e.target.value || undefined })}>
                        <option value="">no credential</option>
                        {credentials.map((credential) => (
                            <option key={credential.id} value={credential.id}>{credential.id}</option>
                        ))}
                    </select>
                    <h4>Operations</h4>
                    {(connector.operations ?? []).map((operation) => (
                        <div key={operation.name} className="nf-operation-row">
                            <input aria-label={`Operation name ${connector.id} ${operation.name}`}
                                placeholder="name" value={operation.name}
                                onChange={(e) => {
                                    const old = operation.name;
                                    updateOperation(connector.id, old, { name: e.target.value });
                                }} />
                            <select aria-label={`Operation method ${connector.id} ${operation.name}`}
                                value={operation.method}
                                onChange={(e) => updateOperation(connector.id, operation.name,
                                    { method: e.target.value as ConnectorOperation["method"] })}>
                                {["GET", "POST", "PUT", "PATCH", "DELETE"].map((method) => (
                                    <option key={method} value={method}>{method}</option>
                                ))}
                            </select>
                            <input aria-label={`Operation path ${connector.id} ${operation.name}`}
                                placeholder="/v1/things" value={operation.path}
                                onChange={(e) => updateOperation(connector.id, operation.name,
                                    { path: e.target.value })} />
                            <button type="button"
                                aria-label={`Remove operation ${connector.id} ${operation.name}`}
                                onClick={() => update(connector.id, {
                                    operations: (connector.operations ?? [])
                                        .filter((candidate) => candidate.name !== operation.name),
                                })}>×</button>
                        </div>
                    ))}
                    <button type="button"
                        onClick={() => update(connector.id, {
                            operations: [...(connector.operations ?? []),
                                { name: "", method: "GET", path: "" }],
                        })}>
                        Add operation
                    </button>
                    <div>
                        <button type="button" aria-label={`Remove connector ${connector.id}`}
                            onClick={() => onChange(connectors.filter((c) => c.id !== connector.id))}>
                            Remove connector
                        </button>
                    </div>
                </div>
            ))}
            <button type="button"
                onClick={() => onChange([...connectors,
                    { id: "", type: "rest", baseUrl: "", operations: [] }])}>
                Add connector
            </button>
            <div className="nf-b-actions">
                <button type="button" className="nf-action-primary" disabled={busy}
                    onClick={() => void onSave()}>Save connectors</button>
            </div>
            <p className="nf-b-meta">{entities.length} entities in this app.</p>
        </fieldset>
    );
}

// --- credentials (§9): references only; material PUTs to the secret store ---

function CredentialEditor({
    credentials,
    onChange,
    client,
    busy,
    onSave,
}: {
    credentials: CredentialDefinition[];
    onChange: (credentials: CredentialDefinition[]) => void;
    client: PlatformClient;
    busy?: boolean;
    onSave: () => Promise<void>;
}): ReactNode {
    const [material, setMaterial] = useState<Record<string, string>>({});
    const [provisioned, setProvisioned] = useState<string | null>(null);
    const update = (index: number, changes: Partial<CredentialDefinition>): void =>
        onChange(credentials.map((credential, i) => i === index ? { ...credential, ...changes } : credential));
    const provision = async (id: string): Promise<void> => {
        try {
            await client.putSecret(id, material[id] ?? "");
            setProvisioned(id);
        } catch {
            setProvisioned(null);
        }
    };
    return (
        <fieldset>
            <legend>Credentials</legend>
            <p className="nf-b-meta">
                A credential is the reference (kind + binding slots); the secret value
                provisions straight into the encrypted store and never touches the app
                definition or its exports.
            </p>
            {credentials.map((credential, index) => (
                <div key={index} className="nf-credential-row" data-credential={credential.id}>
                    <input aria-label={`Credential id ${index}`} placeholder="id" value={credential.id}
                        onChange={(e) => update(index, { id: e.target.value })} />
                    <select aria-label={`Credential kind ${index}`} value={credential.kind}
                        onChange={(e) => update(index, { kind: e.target.value as CredentialDefinition["kind"] })}>
                        <option value="api_key">api_key</option>
                        <option value="basic">basic</option>
                        <option value="oauth2_client_credentials">oauth2_client_credentials</option>
                    </select>
                    {credential.kind === "api_key" ? (
                        <input aria-label={`Credential header ${index}`} placeholder="header, e.g. X-Api-Key"
                            value={credential.header ?? ""}
                            onChange={(e) => update(index, { header: e.target.value || undefined })} />
                    ) : null}
                    {credential.kind === "basic" ? (
                        <input aria-label={`Credential username ${index}`} placeholder="username"
                            value={credential.username ?? ""}
                            onChange={(e) => update(index, { username: e.target.value || undefined })} />
                    ) : null}
                    {credential.kind === "oauth2_client_credentials" ? (
                        <>
                            <input aria-label={`Credential token URL ${index}`} placeholder="tokenUrl"
                                value={credential.tokenUrl ?? ""}
                                onChange={(e) => update(index, { tokenUrl: e.target.value || undefined })} />
                            <input aria-label={`Credential client id ${index}`} placeholder="clientId"
                                value={credential.clientId ?? ""}
                                onChange={(e) => update(index, { clientId: e.target.value || undefined })} />
                        </>
                    ) : null}
                    <input aria-label={`Secret material ${credential.id}`} type="password"
                        placeholder="secret material (store-only)"
                        value={material[credential.id] ?? ""}
                        onChange={(e) => setMaterial({ ...material, [credential.id]: e.target.value })} />
                    <button type="button" disabled={!credential.id || !(material[credential.id] ?? "").length}
                        aria-label={`Provision secret ${credential.id}`}
                        onClick={() => void provision(credential.id)}>
                        Provision secret
                    </button>
                    {provisioned === credential.id ? (
                        <span role="status">stored (rotation window: two active)</span>
                    ) : null}
                    <button type="button" aria-label={`Remove credential ${credential.id}`}
                        onClick={() => onChange(credentials.filter((_, i) => i !== index))}>×</button>
                </div>
            ))}
            <button type="button"
                onClick={() => onChange([...credentials, { id: "", kind: "api_key" }])}>
                Add credential
            </button>
            <div className="nf-b-actions">
                <button type="button" className="nf-action-primary" disabled={busy}
                    onClick={() => void onSave()}>Save credentials</button>
            </div>
        </fieldset>
    );
}

// --- webhooks (§5/§6): one schema, both directions ---

function WebhookEditor({
    entities,
    webhooks,
    onChange,
    client,
    busy,
    onSave,
}: {
    entities: string[];
    webhooks: WebhookDefinition[];
    onChange: (webhooks: WebhookDefinition[]) => void;
    client: PlatformClient;
    busy?: boolean;
    onSave: () => Promise<void>;
}): ReactNode {
    const [secret, setSecret] = useState<Record<string, string>>({});
    const [provisioned, setProvisioned] = useState<string | null>(null);
    const update = (index: number, changes: Partial<WebhookDefinition>): void =>
        onChange(webhooks.map((webhook, i) => i === index ? { ...webhook, ...changes } : webhook));
    const provision = async (ref: string): Promise<void> => {
        try {
            await client.putSecret(ref, secret[ref] ?? "");
            setProvisioned(ref);
        } catch {
            setProvisioned(null);
        }
    };
    return (
        <fieldset>
            <legend>Webhooks</legend>
            <p className="nf-b-meta">
                Inbound hooks map provider payloads onto the write path (HMAC-SHA256,
                timestamp + body); outbound hooks filter spine events onto a signed
                delivery. Rotation keeps two active secrets per ref (§9).
            </p>
            {webhooks.map((webhook, index) => (
                <div key={index} className="nf-webhook-row" data-webhook={webhook.id}>
                    <input aria-label={`Webhook id ${index}`} placeholder="id" value={webhook.id}
                        onChange={(e) => update(index, { id: e.target.value })} />
                    <select aria-label={`Webhook direction ${index}`} value={webhook.direction}
                        onChange={(e) => update(index,
                            { direction: e.target.value as WebhookDefinition["direction"] })}>
                        <option value="inbound">inbound</option>
                        <option value="outbound">outbound</option>
                    </select>
                    {webhook.direction === "inbound" ? (
                        <>
                            <select aria-label={`Webhook entity ${index}`} value={webhook.entity ?? ""}
                                onChange={(e) => update(index, { entity: e.target.value || undefined })}>
                                <option value="">target entity…</option>
                                {entities.map((entity) => (
                                    <option key={entity} value={entity}>{entity}</option>
                                ))}
                            </select>
                            <input aria-label={`Webhook key fields ${index}`}
                                placeholder="upsert key fields, comma-separated"
                                value={(webhook.mapping?.keyFields ?? []).join(",")}
                                onChange={(e) => update(index, {
                                    mapping: {
                                        mode: webhook.mapping?.mode ?? "upsert",
                                        keyFields: e.target.value
                                            ? e.target.value.split(",").map((field) => field.trim()).filter(Boolean)
                                            : undefined,
                                        idempotencyKey: webhook.mapping?.idempotencyKey,
                                        fields: webhook.mapping?.fields,
                                    },
                                })} />
                            <input aria-label={`Webhook mapping ${index}`}
                                placeholder='field templates JSON, e.g. {"number": "${txn_id}"}'
                                value={webhook.mapping?.fields && Object.keys(webhook.mapping.fields).length > 0
                                    ? JSON.stringify(webhook.mapping.fields) : ""}
                                onChange={(e) => {
                                    const text = e.target.value.trim();
                                    update(index, {
                                        mapping: {
                                            mode: webhook.mapping?.mode ?? "upsert",
                                            keyFields: webhook.mapping?.keyFields,
                                            idempotencyKey: webhook.mapping?.idempotencyKey,
                                            fields: text
                                                ? JSON.parse(text) as Record<string, unknown>
                                                : undefined,
                                        },
                                    });
                                }} />
                        </>
                    ) : (
                        <>
                            <input aria-label={`Webhook URL ${index}`} placeholder="https://hooks.example.local/novaforge"
                                value={webhook.url ?? ""}
                                onChange={(e) => update(index, { url: e.target.value || undefined })} />
                            <input aria-label={`Webhook filter ${index}`}
                                placeholder="event filter, e.g. event == 'record.created'"
                                value={webhook.events ?? ""}
                                onChange={(e) => update(index, { events: e.target.value || undefined })} />
                        </>
                    )}
                    <input aria-label={`Webhook secret ${webhook.id}`} type="password"
                        placeholder="HMAC secret (store-only)"
                        value={secret[webhook.id] ?? ""}
                        onChange={(e) => setSecret({ ...secret, [webhook.id]: e.target.value })} />
                    <button type="button" disabled={!webhook.id || !(secret[webhook.id] ?? "").length}
                        aria-label={`Provision webhook secret ${webhook.id}`}
                        onClick={() => void provision(webhook.id)}>
                        Provision secret
                    </button>
                    {provisioned === webhook.id ? <span role="status">stored</span> : null}
                    <label className="nf-inline">
                        <input type="checkbox" aria-label={`Webhook enabled ${webhook.id}`}
                            checked={webhook.enabled !== false}
                            onChange={(e) => update(index, { enabled: e.target.checked || undefined })} />
                        enabled
                    </label>
                    <button type="button" aria-label={`Remove webhook ${webhook.id}`}
                        onClick={() => onChange(webhooks.filter((_, i) => i !== index))}>×</button>
                </div>
            ))}
            <button type="button"
                onClick={() => onChange([...webhooks, { id: "", direction: "inbound", enabled: true }])}>
                Add webhook
            </button>
            <div className="nf-b-actions">
                <button type="button" className="nf-action-primary" disabled={busy}
                    onClick={() => void onSave()}>Save webhooks</button>
            </div>
        </fieldset>
    );
}

// --- import mappings (§7): versioned metadata; runs are tenant data ---

function ImportEditor({
    entities,
    imports,
    onChange,
    busy,
    onSave,
}: {
    entities: string[];
    imports: ImportDefinition[];
    onChange: (imports: ImportDefinition[]) => void;
    busy?: boolean;
    onSave: () => Promise<void>;
}): ReactNode {
    const update = (index: number, changes: Partial<ImportDefinition>): void =>
        onChange(imports.map((mapping, i) => i === index ? { ...mapping, ...changes } : mapping));
    return (
        <fieldset>
            <legend>Import mappings</legend>
            <p className="nf-b-meta">
                A mapping binds source columns to entity fields; import runs (upload →
                checkpointed chunks → per-row ledger) are tenant data in the
                Integration Service, never authored here.
            </p>
            {imports.map((mapping, index) => (
                <div key={index} className="nf-import-row" data-import={mapping.apiName}>
                    <input aria-label={`Import name ${index}`} placeholder="name" value={mapping.apiName}
                        onChange={(e) => update(index, { apiName: e.target.value })} />
                    <select aria-label={`Import entity ${index}`} value={mapping.entity}
                        onChange={(e) => update(index, { entity: e.target.value })}>
                        <option value="">target entity…</option>
                        {entities.map((entity) => (
                            <option key={entity} value={entity}>{entity}</option>
                        ))}
                    </select>
                    <select aria-label={`Import mode ${index}`} value={mapping.mode}
                        onChange={(e) => update(index,
                            { mode: e.target.value as ImportDefinition["mode"] })}>
                        <option value="create">create</option>
                        <option value="upsert">upsert</option>
                    </select>
                    <input aria-label={`Import key fields ${index}`}
                        placeholder="upsert keys, comma-separated" disabled={mapping.mode === "create"}
                        value={(mapping.keyFields ?? []).join(",")}
                        onChange={(e) => update(index, {
                            keyFields: e.target.value
                                ? e.target.value.split(",").map((field) => field.trim()).filter(Boolean)
                                : undefined,
                        })} />
                    <input aria-label={`Import mapping ${index}`}
                        placeholder='mapping JSON, e.g. {"reference": "Ref"}'
                        value={mapping.mapping && Object.keys(mapping.mapping).length > 0
                            ? JSON.stringify(mapping.mapping) : ""}
                        onChange={(e) => {
                            const text = e.target.value.trim();
                            update(index, {
                                mapping: text ? JSON.parse(text) as Record<string, unknown> : undefined,
                            });
                        }} />
                    <button type="button" aria-label={`Remove import ${mapping.apiName}`}
                        onClick={() => onChange(imports.filter((_, i) => i !== index))}>×</button>
                </div>
            ))}
            <button type="button"
                onClick={() => onChange([...imports, { apiName: "", entity: "", mode: "create" }])}>
                Add import mapping
            </button>
            <div className="nf-b-actions">
                <button type="button" className="nf-action-primary" disabled={busy}
                    onClick={() => void onSave()}>Save import mappings</button>
            </div>
        </fieldset>
    );
}

// --- the delivery log (§3): every dispatch, beside the editors ---

function DeliveryLog({ rows }: { rows: Record<string, unknown>[] | null }): ReactNode {
    return (
        <fieldset>
            <legend>Delivery log</legend>
            {rows === null ? (
                <p role="status">Loading deliveries…</p>
            ) : rows.length === 0 ? (
                <p>No deliveries yet — publish connectors/webhooks and let them fire.</p>
            ) : (
                <table className="nf-table nf-deliveries">
                    <thead>
                        <tr>
                            <th scope="col">Kind</th>
                            <th scope="col">Target</th>
                            <th scope="col">Status</th>
                            <th scope="col">Attempts</th>
                            <th scope="col">Last response</th>
                            <th scope="col">Latency</th>
                            <th scope="col">At</th>
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map((row, index) => (
                            <tr key={index}>
                                <td>{String(row.kind ?? "")}</td>
                                <td>{String(row.target ?? "")}</td>
                                <td>{String(row.status ?? "")}</td>
                                <td>{String(row.attempts ?? "")}</td>
                                <td>{String(row.lastStatus ?? "—")}</td>
                                <td>{row.latencyMs == null ? "—" : `${String(row.latencyMs)} ms`}</td>
                                <td>{String(row.createdAt ?? "")}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}
        </fieldset>
    );
}

// --- the DLQ (§5/§6): terminal failures with the payload preserved, replayable ---
function DlqPanel({
    rows,
    client,
    onReplayed,
}: {
    rows: Record<string, unknown>[] | null;
    client: PlatformClient;
    onReplayed: () => void;
}): ReactNode {
    const [busy, setBusy] = useState<string | null>(null);
    const [outcome, setOutcome] = useState<string | null>(null);
    const replay = async (id: string): Promise<void> => {
        setBusy(id);
        try {
            const result = await client.replayDlqEntry(id);
            setOutcome(`${id}: ${String(result.status ?? "replayed")}`);
            onReplayed();
        } catch (caught) {
            setOutcome(caught instanceof Error ? caught.message : String(caught));
        } finally {
            setBusy(null);
        }
    };
    return (
        <fieldset>
            <legend>Dead-letter queue</legend>
            {rows === null ? (
                <p role="status">Loading DLQ…</p>
            ) : rows.length === 0 ? (
                <p>DLQ empty — terminal failures land here with their payloads preserved.</p>
            ) : (
                <table className="nf-table nf-dlq">
                    <thead>
                        <tr>
                            <th scope="col">Kind</th>
                            <th scope="col">Target</th>
                            <th scope="col">Dedupe key</th>
                            <th scope="col">Error</th>
                            <th scope="col">At</th>
                            <th scope="col">Replay</th>
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map((row, index) => {
                            const id = String(row.id ?? index);
                            return (
                                <tr key={id} data-dlq={id}>
                                    <td>{String(row.kind ?? "")}</td>
                                    <td>{String(row.target ?? "")}</td>
                                    <td>{String(row.dedupeKey ?? "")}</td>
                                    <td>{String(row.error ?? "")}</td>
                                    <td>{String(row.createdAt ?? "")}</td>
                                    <td>
                                        <button type="button" disabled={busy === id}
                                            aria-label={`Replay DLQ entry ${id}`}
                                            onClick={() => void replay(id)}>
                                            Replay
                                        </button>
                                    </td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
            )}
            {outcome ? <p role="status" aria-live="polite">{outcome}</p> : null}
        </fieldset>
    );
}

// --- import/export jobs (§7): the builder progress surface — created, inspected,
// resumed through the operational APIs; progress counters + the row ledger ---

function JobsPanel({
    rows,
    client,
    onChanged,
}: {
    rows: Record<string, unknown>[] | null;
    client: PlatformClient;
    onChanged: () => void;
}): ReactNode {
    const [ledger, setLedger] = useState<{ job: string; rows: Record<string, unknown>[] } | null>(null);
    const [busy, setBusy] = useState<string | null>(null);
    const [outcome, setOutcome] = useState<string | null>(null);

    const resume = async (id: string): Promise<void> => {
        setBusy(id);
        try {
            await client.resumeIntegrationJob(id);
            setOutcome(`job ${id}: resumed from its checkpoint`);
            onChanged();
        } catch (caught) {
            setOutcome(caught instanceof Error ? caught.message : String(caught));
        } finally {
            setBusy(null);
        }
    };

    const inspect = async (id: string): Promise<void> => {
        setBusy(id);
        try {
            const jobRows = await client.integrationJobRows(id);
            setLedger({ job: id, rows: jobRows });
        } catch (caught) {
            setOutcome(caught instanceof Error ? caught.message : String(caught));
        } finally {
            setBusy(null);
        }
    };

    return (
        <fieldset>
            <legend>Import / export jobs</legend>
            <p className="nf-hint">
                Async runs — imports checkpoint per row (kill → resume applies each row exactly
                once); progress rides import.progress, completion notifies the initiator.
            </p>
            {rows === null ? (
                <p role="status">Loading jobs…</p>
            ) : rows.length === 0 ? (
                <p>No job runs yet — imports and over-cap exports land here.</p>
            ) : (
                <table className="nf-table nf-jobs">
                    <thead>
                        <tr>
                            <th scope="col">Kind</th>
                            <th scope="col">App / target</th>
                            <th scope="col">Status</th>
                            <th scope="col">Progress</th>
                            <th scope="col">Failed</th>
                            <th scope="col">Created</th>
                            <th scope="col">Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map((row, index) => {
                            const id = String(row.id ?? index);
                            const resumable = row.status === "paused" || row.status === "failed";
                            return (
                                <tr key={id} data-job={id}>
                                    <td>{String(row.kind ?? "")}</td>
                                    <td>{String(row.entity ?? row.importMapping ?? row.reportId ?? row.app ?? "")}</td>
                                    <td>{String(row.status ?? "")}</td>
                                    <td>
                                        {String(row.processedRows ?? 0)} / {String(row.totalRows ?? "?")}
                                    </td>
                                    <td>{String(row.failedRows ?? 0)}</td>
                                    <td>{String(row.createdAt ?? "")}</td>
                                    <td>
                                        <button type="button" disabled={busy === id}
                                            aria-label={`Inspect rows of job ${id}`}
                                            onClick={() => void inspect(id)}>
                                            Rows
                                        </button>
                                        {resumable ? (
                                            <button type="button" disabled={busy === id}
                                                aria-label={`Resume job ${id}`}
                                                onClick={() => void resume(id)}>
                                                Resume
                                            </button>
                                        ) : null}
                                    </td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
            )}
            {ledger ? (
                <details open data-job-rows={ledger.job}>
                    <summary>Row ledger — job {ledger.job}</summary>
                    {ledger.rows.length === 0 ? (
                        <p>No rows recorded yet.</p>
                    ) : (
                        <table className="nf-table nf-job-rows">
                            <thead>
                                <tr>
                                    <th scope="col">Row</th>
                                    <th scope="col">Status</th>
                                    <th scope="col">Record</th>
                                    <th scope="col">Code</th>
                                    <th scope="col">Detail</th>
                                </tr>
                            </thead>
                            <tbody>
                                {ledger.rows.map((row, index) => (
                                    <tr key={index}>
                                        <td>{String(row.row ?? index)}</td>
                                        <td>{String(row.status ?? "")}</td>
                                        <td>{String(row.recordId ?? "—")}</td>
                                        <td>{String(row.code ?? "—")}</td>
                                        <td>{String(row.detail ?? "")}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </details>
            ) : null}
            {outcome ? <p role="status" aria-live="polite">{outcome}</p> : null}
        </fieldset>
    );
}

import { type ReactNode, useState } from "react";

/**
 * FileUpload (PHASE-6 §8, activating PHASE-2 §5's disabled stub) — catalog version
 * 1.0.0. The `file` field type's upload path: the component requests an upload
 * grant from the File Service (a presigned PUT with the pinned 15-minute expiry),
 * uploads the bytes directly to object storage, and reports completion for the
 * server-side checksum verification and the config-gated ClamAV hook. The record's
 * field value is the attachment id the completion returns.
 */
export function FileUpload(props: {
  entity?: string;
  recordId?: string;
  filesBase?: string;
  bearerToken?: string;
  onUploaded?: (attachmentId: string, virusScan: string) => void;
  onError?: (detail: string) => void;
}): ReactNode {
  const [attachment, setAttachment] = useState<string | null>(null);
  const [scan, setScan] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);

  async function upload(file: File): Promise<void> {
    setBusy(true);
    setFailed(null);
    try {
      const base = props.filesBase ?? "";
      const grantResponse = await fetch(`${base}/api/v1/files/uploads`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(props.bearerToken ? { Authorization: `Bearer ${props.bearerToken}` } : {}),
        },
        body: JSON.stringify({
          fileName: file.name,
          contentType: file.type || "application/octet-stream",
          size: file.size,
          entity: props.entity,
          recordId: props.recordId,
        }),
      });
      if (!grantResponse.ok) {
        throw new Error(`upload grant failed: HTTP ${grantResponse.status}`);
      }
      const grant = (await grantResponse.json()) as UploadGrant;
      const put = await fetch(grant.uploadUrl, {
        method: "PUT",
        headers: { "Content-Type": file.type || "application/octet-stream" },
        body: file,
      });
      if (!put.ok) {
        throw new Error(`object upload failed: HTTP ${put.status}`);
      }
      const checksum = await sha256(file);
      const completeResponse = await fetch(`${base}/api/v1/files/${grant.id}/complete`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(props.bearerToken ? { Authorization: `Bearer ${props.bearerToken}` } : {}),
        },
        body: JSON.stringify({ checksum }),
      });
      if (!completeResponse.ok) {
        throw new Error(`completion rejected: HTTP ${completeResponse.status}`);
      }
      const completion = (await completeResponse.json()) as Completion;
      setAttachment(completion.id);
      setScan(completion.virusScan);
      props.onUploaded?.(completion.id, completion.virusScan);
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error);
      setFailed(detail);
      props.onError?.(detail);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="nf-file-upload" role="group" aria-label="attachment upload">
      <label className="nf-file-upload-label">
        <input
          type="file"
          disabled={busy}
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) {
              void upload(file);
            }
            event.target.value = "";
          }}
        />
        {busy ? "Uploading…" : "Choose file"}
      </label>
      {attachment ? (
        <output className="nf-file-upload-result" aria-live="polite">
          {attachment} · {scan === "infected" ? "quarantined" : scan}
        </output>
      ) : null}
      {failed ? (
        <p className="nf-file-upload-error" role="alert">
          {failed}
        </p>
      ) : null}
    </div>
  );
}

interface UploadGrant {
  id: string;
  uploadUrl: string;
  expiresAt: string;
  method: "PUT";
}

interface Completion {
  id: string;
  virusScan: "pending" | "clean" | "infected" | "skipped";
  checksum: string;
  size: number;
}

/** SHA-256 as base64 — the completion's client-side checksum (verified server-side). */
async function sha256(file: File): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest("SHA-256", await file.arrayBuffer());
  return btoa(String.fromCharCode(...new Uint8Array(digest)));
}

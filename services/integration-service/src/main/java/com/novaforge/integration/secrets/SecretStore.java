package com.novaforge.integration.secrets;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The secrets store (PHASE-6 §9): versioned secret material keyed by the metadata's
 * reference id — {@code CredentialDefinition.id} for connector auth, {@code
 * secretRef} for webhook HMAC keys. Secrets are write-only through the API (never
 * read back in clear, never exported); each {@link #put} adds a new active version,
 * so during a rotation window old and new both verify (§9's two-active-secrets rule)
 * until {@link #retireEarlierVersions} flips the store back to exactly one.
 */
@Repository
public class SecretStore {

    public static final String PURPOSE_WEBHOOK = "webhook";
    public static final String PURPOSE_CREDENTIAL = "credential";

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public SecretStore(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /**
     * Provisions (first write) or rotates (subsequent) the secret for a reference:
     * the new material lands as the newest active version — earlier versions stay
     * active for the verification window until explicitly retired.
     */
    @Transactional
    public void put(UUID tenantId, String ref, String purpose, String material) {
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM it_secrets WHERE tenant_id = ? AND ref = ?",
                Integer.class, tenantId, ref);
        jdbc.update("""
                INSERT INTO it_secrets (tenant_id, ref, purpose, ciphertext, version, active)
                VALUES (?, ?, ?, ?, ?, true)""",
                tenantId, ref, purpose, cipher.encrypt(material), next);
    }

    /** Retires every version but the newest — the rotation window closes. */
    @Transactional
    public void retireEarlierVersions(UUID tenantId, String ref) {
        jdbc.update("""
                UPDATE it_secrets SET active = false
                 WHERE tenant_id = ? AND ref = ?
                   AND version < (SELECT MAX(version) FROM it_secrets
                                   WHERE tenant_id = ? AND ref = ?)""",
                tenantId, ref, tenantId, ref);
    }

    /** The active materials, newest first — verification tries every one. */
    public List<String> active(UUID tenantId, String ref) {
        return jdbc.query("""
                SELECT ciphertext FROM it_secrets
                 WHERE tenant_id = ? AND ref = ? AND active
                 ORDER BY version DESC""",
                (rs, i) -> cipher.decrypt(rs.getBytes("ciphertext")), tenantId, ref);
    }

    /** The newest active material — the outbound signing key. Call when non-empty. */
    public String newest(UUID tenantId, String ref) {
        List<String> versions = active(tenantId, ref);
        if (versions.isEmpty()) {
            throw new PlatformException(PlatformErrorCode.NOT_FOUND,
                    "no active secret for reference " + ref);
        }
        return versions.get(0);
    }

    /** Hex SHA-256 — the inbound replay nonce's signature hash. */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(input.getBytes(StandardCharsets.UTF_8))) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "sha-256 unavailable: " + e.getMessage());
        }
    }
}

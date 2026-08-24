package com.novaforge.integration.webhook;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.integration.secrets.SecretStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The pinned webhook scheme (PHASE-6 §5): HMAC-SHA256 over the raw body with a
 * timestamp — {@code timestamp + "." + body} — the timestamp in
 * {@code X-NovaForge-Timestamp}, the hex signature in {@code X-NovaForge-Signature}.
 * One scheme protects both directions; a ±5-minute window rejects replay, and
 * verification tries every active secret so rotation windows admit old and new
 * alike (§9's two-active-secrets rule).
 */
@Component
public class HmacScheme {

    public static final String TIMESTAMP_HEADER = "X-NovaForge-Timestamp";
    public static final String SIGNATURE_HEADER = "X-NovaForge-Signature";

    private final long windowSeconds;

    public HmacScheme(@Value("${novaforge.webhook.timestamp-window-seconds:300}")
                      long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    /** Signs a raw body with the current timestamp — the outbound leg's header set. */
    public Signed sign(String secret, byte[] body) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        return new Signed(timestamp, signature(secret, timestamp, body));
    }

    /**
     * The inbound leg: timestamp window first, then the signature against any active
     * secret (constant-time per candidate). Wrong secret, stale timestamp, or a
     * mangled signature all render {@code SIGNATURE_INVALID} (§6) — the matrix's
     * indistinguishable failure.
     */
    public void verify(String timestampHeader, String signatureHeader, byte[] body,
                       List<String> activeSecrets) {
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader == null ? "" : timestampHeader.trim());
        } catch (NumberFormatException e) {
            throw signatureInvalid("timestamp header missing or malformed");
        }
        long skew = Math.abs(Instant.now().getEpochSecond() - timestamp);
        if (skew > windowSeconds) {
            throw signatureInvalid("timestamp outside the ±" + windowSeconds + "s window");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw signatureInvalid("signature header missing");
        }
        for (String secret : activeSecrets) {
            if (constantTimeEquals(signature(secret, String.valueOf(timestamp), body),
                    signatureHeader.trim())) {
                return;
            }
        }
        throw signatureInvalid("signature does not match any active secret");
    }

    /** Hex HMAC-SHA256 over {@code timestamp + "." + body}. */
    public static String signature(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return hex(mac.doFinal(body));
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "HMAC computation failed: " + e.getMessage());
        }
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static PlatformException signatureInvalid(String detail) {
        return new PlatformException(PlatformErrorCode.SIGNATURE_INVALID,
                "webhook signature verification failed: " + detail);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /** The outbound signing result — both headers, ready to attach. */
    public record Signed(String timestamp, String signature) {
    }
}

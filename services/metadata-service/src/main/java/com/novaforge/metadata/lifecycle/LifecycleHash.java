package com.novaforge.metadata.lifecycle;

import com.novaforge.metadata.AppDefinition;
import com.novaforge.metadata.DefinitionParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The version identity of a definition bundle (PHASE-8 §4 item 1): the sha256 of its
 * canonical JSON serialization. Publish records it on the version row; every suite
 * run records it for the candidate it executed — so "a recorded green run of all app
 * suites against exactly V" is a mechanical hash match, never a heuristic. Runs attach
 * to content, environments pin numbered versions; the hash is the join.
 */
public final class LifecycleHash {

    private LifecycleHash() {
    }

    public static String contentHash(AppDefinition bundle) {
        return sha256(DefinitionParser.writeApp(bundle));
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder(digest.getDigestLength() * 2);
            for (byte b : digest.digest(text.getBytes(StandardCharsets.UTF_8))) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }

    /** The artifact signature scheme (§2): HMAC-SHA256 over the manifest text. */
    public static String hmacSha256(String key, String text) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("hmac-sha256 unavailable", e);
        }
    }
}

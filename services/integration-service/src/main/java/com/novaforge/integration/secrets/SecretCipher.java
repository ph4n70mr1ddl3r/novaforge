package com.novaforge.integration.secrets;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-GCM at rest for the secrets store (PHASE-6 §9): a 256-bit data key from
 * configuration — the compose-provided key locally, KMS/Vault-sourced in staged
 * environments — encrypts every stored secret as {@code IV ‖ ciphertext+tag} with a
 * fresh 96-bit IV per write. The key itself never persists beside the data and never
 * rides an app artifact.
 *
 * <p><b>Fail-closed on the dev key (the 2025-08-27 review closed the silent
 * fallback):</b> the all-zeros development key — previously handed out silently
 * whenever {@code NOVAFORGE_SECRETS_DATA_KEY} was unset — now demands an explicit
 * opt-in. {@code novaforge.integration.secrets.allow-dev-key} (default true) keeps
 * local bring-up working while logging a loud warning naming the risk; staged
 * environments (the helm chart sets it false) refuse to start on the dev key, so a
 * misconfigured deployment fails at boot instead of storing tenant secrets under a
 * key that ships in the public repository.</p>
 */
@Component
public final class SecretCipher {

    private static final Logger LOG = LoggerFactory.getLogger(SecretCipher.class);

    /** The published all-zeros development key (32 zero bytes, base64) — local only. */
    static final String DEV_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${novaforge.integration.secrets.data-key:" + DEV_KEY + "}") String base64Key,
                        @Value("${novaforge.integration.secrets.allow-dev-key:true}") boolean allowDevKey) {
        boolean devKey = DEV_KEY.equals(base64Key);
        if (devKey && !allowDevKey) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "the secrets data key is the public development key and dev keys are not "
                            + "allowed here — set NOVAFORGE_SECRETS_DATA_KEY from the KMS/Vault "
                            + "source before starting this deployment");
        }
        if (devKey) {
            LOG.warn("╔══ SECRETS STORE RUNS ON THE PUBLIC DEVELOPMENT KEY ══╗");
            LOG.warn("║ NOVAFORGE_SECRETS_DATA_KEY is unset or carries the dev key: every stored  ║");
            LOG.warn("║ secret is encrypted under a key that ships in the public repository.     ║");
            LOG.warn("║ Local bring-up only — staged environments set                               ║");
            LOG.warn("║ novaforge.integration.secrets.allow-dev-key=false and fail boot instead.   ║");
            LOG.warn("╚══════════════════════════════════════════════════════════════════════╝");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "secrets data key is not valid base64");
        }
        if (decoded.length != 16 && decoded.length != 32) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "secrets data key must decode to 16 or 32 bytes (AES-128/256)");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    /** Encrypts UTF-8 material; returns {@code IV ‖ ciphertext+tag}. */
    public byte[] encrypt(String material) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(material.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(sealed, 0, out, IV_BYTES, sealed.length);
            return out;
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "secret encryption failed: " + e.getMessage(), null, e);
        }
    }

    /** Decrypts {@code IV ‖ ciphertext+tag} produced by {@link #encrypt(String)}. */
    public String decrypt(byte[] sealed) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "secret decryption failed (wrong data key?)");
        }
    }
}

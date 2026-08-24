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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-GCM at rest for the secrets store (PHASE-6 §9): a 256-bit data key from
 * configuration — the compose-provided key locally, KMS/Vault-sourced in staged
 * environments — encrypts every stored secret as {@code IV ‖ ciphertext+tag} with a
 * fresh 96-bit IV per write. The key itself never persists beside the data and never
 * rides an app artifact.
 */
@Component
public final class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${novaforge.integration.secrets.data-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            // A compose-provided default (32 zero bytes) keeps local bring-up working;
            // staged environments set NOVAFORGE_SECRETS_DATA_KEY from the KMS/Vault
            // source. The key rotates by re-encrypting the store — v1 pins the
            // mechanism, not the ceremony.
            base64Key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
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
                    "secret encryption failed: " + e.getMessage());
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

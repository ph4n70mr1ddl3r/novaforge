package com.novaforge.integration.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.common.error.PlatformException;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The secrets store's at-rest contract, pinned directly (re-audit): AES-GCM
 * under a configured data key with the fail-closed dev-key gate — the gate the
 * helm charts rely on in staged environments ({@code allow-dev-key=false}
 * refuses boot rather than store tenant secrets under the repository-public
 * key). Nothing else in the repo references {@code allow-dev-key}: a regression
 * that silently accepts the dev key (or a wrong-length/malformed key) passes
 * every other suite while the staged posture evaporates.
 */
class SecretCipherTest {

    /** 32 random bytes, base64 — a "real" KMS-sourced key shape. */
    private static final String DATA_KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes());

    @Test
    @DisplayName("the dev key with allow-dev-key=false refuses construction — fail closed")
    void devKeyRefusedWhenDisallowed() {
        assertThatThrownBy(() -> new SecretCipher(SecretCipher.DEV_KEY, false))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("development key");
    }

    @Test
    @DisplayName("the dev key with the explicit local opt-in still encrypts (loud warning is the contract)")
    void devKeyAllowedLocallyRoundTrips() {
        var cipher = new SecretCipher(SecretCipher.DEV_KEY, true);
        var sealed = cipher.encrypt("tenant-secret");
        assertThat(cipher.decrypt(sealed)).isEqualTo("tenant-secret");
    }

    @Test
    @DisplayName("a non-base64 key and a wrong-length key both refuse construction")
    void malformedKeysRefused() {
        assertThatThrownBy(() -> new SecretCipher("!!not-base64!!", true))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("base64");
        // 24 bytes is not one of the AES-128/256 shapes
        var twentyFour = Base64.getEncoder().encodeToString(new byte[24]);
        assertThatThrownBy(() -> new SecretCipher(twentyFour, true))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("16 or 32 bytes");
        // AES-128 (16 bytes) is a legal shape; "x" seals as 12 IV + 1 ct + 16 tag
        var sixteen = Base64.getEncoder().encodeToString(new byte[16]);
        assertThat(new SecretCipher(sixteen, true).encrypt("x")).hasSize(12 + 1 + 16);
    }

    @Test
    @DisplayName("round trip: fresh 96-bit IV per write, authenticated decryption, wrong key fails closed")
    void roundTripAndWrongKeyFailsClosed() {
        var cipher = new SecretCipher(DATA_KEY, true);
        var sealed = cipher.encrypt("invoice-total");
        // the IV prefix is fresh per write — two seals of one plaintext differ
        var again = cipher.encrypt("invoice-total");
        assertThat(Arrays.copyOfRange(sealed, 0, 12))
                .isNotEqualTo(Arrays.copyOfRange(again, 0, 12));
        assertThat(cipher.decrypt(sealed)).isEqualTo("invoice-total");
        assertThat(cipher.decrypt(again)).isEqualTo("invoice-total");

        // a ciphertext under key A never decrypts under key B — GCM auth fails
        // closed as a PlatformException, never garbage plaintext
        var other = Base64.getEncoder().encodeToString(
                "ffffffffffffffffffffffffffffffff".getBytes());
        var mismatched = new SecretCipher(other, true);
        assertThatThrownBy(() -> mismatched.decrypt(sealed))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("decryption failed");
    }
}

package com.novaforge.file.virus;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * The ClamAV hook (PHASE-6 §8/§13 Q2): config-gated — off locally, on in
 * staging/prod, and CI runs one config-on job so the scanning path stays tested.
 * The binding speaks clamd's INSTREAM protocol (length-prefixed chunks, a
 * zero-length terminator, then the verdict); the EICAR test signature quarantines
 * exactly like a live detection (§11 item 5). A skipped gate (config off) records
 * {@code virusScan: skipped}, never {@code clean} — the status never lies.
 */
public interface VirusScanner {

    /** Scans bytes; true when the payload carries a detection. */
    boolean infected(byte[] content);

    /** The clamd INSTREAM binding (wired when {@code novaforge.file.clamav.enabled}). */
    class Clamd implements VirusScanner {

        private final String host;
        private final int port;

        public Clamd(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public boolean infected(byte[] content) {
            try (Socket socket = new Socket(host, port)) {
                socket.setSoTimeout(30_000);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();
                out.write("zINSTREAM\0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                byte[] chunk = new byte[2048];
                try (ByteArrayInputStream source = new ByteArrayInputStream(content)) {
                    int read;
                    while ((read = source.read(chunk)) > 0) {
                        out.write(intToBytes(read));
                        out.write(chunk, 0, read);
                        out.flush();
                    }
                }
                out.write(intToBytes(0));   // zero-length chunk: end of stream
                out.flush();
                StringBuilder response = new StringBuilder();
                int bite;
                while ((bite = in.read()) >= 0) {
                    response.append((char) bite);
                    if (response.toString().endsWith("\0")) {
                        break;
                    }
                }
                return response.toString().contains("FOUND");
            } catch (Exception e) {
                throw new PlatformException(PlatformErrorCode.INTERNAL,
                        "clamd scan failed: " + e.getMessage());
            }
        }

        private static byte[] intToBytes(int value) {
            return new byte[] {(byte) (value >> 24), (byte) (value >> 16),
                    (byte) (value >> 8), (byte) value};
        }
    }
}

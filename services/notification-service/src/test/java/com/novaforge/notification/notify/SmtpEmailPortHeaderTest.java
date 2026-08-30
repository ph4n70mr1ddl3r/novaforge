package com.novaforge.notification.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novaforge.notification.notify.Notifier.Attachment;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Anti-regression (found in the 2026-08-31 hunt, verified against the resolved mail
 * stack): the SMTP port performed no line-discipline on header-bound values — a
 * CR/LF inside a subject or an attachment filename serialized into a real standalone
 * {@code Bcc:} header line (line-oriented MTAs honor it). Today's callers feed
 * validated values; the ${record.*} template growth path puts user free text one
 * template edit away, so the boundary itself must be safe.
 */
class SmtpEmailPortHeaderTest {

    @Test
    @DisplayName("CRLF in subject and attachment filename never becomes a header line")
    void crlfNeverRidesHeaders() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage[] captured = new MimeMessage[1];
        MimeMessage live = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(live);
        doAnswer(inv -> {
            captured[0] = inv.getArgument(0);
            return null;
        }).when(sender).send(any(MimeMessage.class));        Notifier.SmtpEmailPort port = new Notifier.SmtpEmailPort(sender, "novaforge@localhost");
        port.send("victim@example.net",
                "report ready\r\nBcc: attacker@example.net",
                "body",
                new Attachment("exports\r\nBcc: attacker2@example.net.csv",
                        "text/csv", "a,b\n".getBytes(StandardCharsets.UTF_8)));

        ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        assertThat(captured[0]).isNotNull();
        captured[0].writeTo(serialized);
        String wire = serialized.toString(StandardCharsets.UTF_8);
        assertThat(wire).doesNotContain("\r\nBcc:");
        assertThat(wire).doesNotContain("\nBcc:");
        // the sanitized values still ride (folded or spaced), nothing is dropped
        assertThat(wire).contains("report ready");
        assertThat(wire).contains("attacker2@example.net.csv");
    }
}

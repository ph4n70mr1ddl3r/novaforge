package com.novaforge.notification.notify;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivery (PHASE-4 §8): the platform inbox row plus SMTP email, filtered by the
 * user's per-category channel preferences (both on by default — v1's coarse
 * toggles). Built-in templates per category — no authoring surface in v1 — with
 * {@code ${task.field}} tokens resolved from the event payload. Every delivery
 * emits {@code notification.delivered} on the outbox; synthetic actors
 * ({@code scratch-*}/{@code actor-*} usernames, ADR-010 #3) have no channels and no
 * inbox address — the fan-out skips them entirely.
 */
@Service
public class Notifier {

    private static final Logger LOG = LoggerFactory.getLogger(Notifier.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** v1 categories (§8): later phases add built-ins as their features land. */
    public static final String TASK_ASSIGNMENT = "task-assignment";
    public static final String SLA_WARNING = "sla-warning";

    /** Scheduled report delivery (PHASE-5 §7) — the export rides inline. */
    public static final String REPORT_DELIVERY = "report-delivery";

    /** Import/export job completion (PHASE-6 §7) — delivered to the initiating user. */
    public static final String JOB_COMPLETED = "job-completed";

    private final JdbcTemplate jdbc;
    private final EmailPort email;
    private final RecipientResolver recipients;

    public Notifier(JdbcTemplate jdbc, EmailPort email, RecipientResolver recipients) {
        this.jdbc = jdbc;
        this.email = email;
        this.recipients = recipients;
    }

    /** One spine event fanned out to its recipients' channels. */
    @Transactional
    public void onEvent(String eventId, UUID tenantId, String category,
                        Map<String, Object> task, String titleTemplate, String bodyTemplate) {
        for (UUID user : recipients.of(tenantId, task)) {
            if (!recipients.hasChannels(user)) {
                continue;   // synthetic actors: no inbox entry, no email (ADR-010 #3)
            }
            String title = resolve(titleTemplate, task);
            String body = resolve(bodyTemplate, task);
            boolean inboxOn = preference(tenantId, user, category, "inbox");
            boolean emailOn = preference(tenantId, user, category, "email");
            if (inboxOn) {
                int written = jdbc.update("""
                        INSERT INTO nf_notifications (id, tenant_id, user_id, category,
                                                      title, body, event_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT DO NOTHING""",
                        UUID.randomUUID(), tenantId, user, category, title, body, eventId);
                if (written > 0) {
                    delivered(tenantId, user, "inbox", category);
                }
            }
            if (emailOn) {
                email.send(recipients.addressOf(user), title, body);
                delivered(tenantId, user, "email", category);
            }
        }
    }

    /**
     * Direct delivery for the internal send surface (PHASE-5 §7): explicit
     * recipients — resolved users — instead of a spine event's task payload. The
     * same preference filtering, inbox row, email leg (with the inline attachment),
     * and notification.delivered audit per channel; synthetic actors skip entirely.
     */
    @Transactional
    public int deliverDirect(UUID tenantId, String category, String title, String body,
                             java.util.List<UUID> users, Attachment attachment) {
        int delivered = 0;
        for (UUID user : users) {
            if (!recipients.hasChannels(user)) {
                continue;   // synthetic actors: no inbox entry, no email (ADR-010 #3)
            }
            boolean inboxOn = preference(tenantId, user, category, "inbox");
            boolean emailOn = preference(tenantId, user, category, "email");
            if (inboxOn) {
                int written = jdbc.update("""
                        INSERT INTO nf_notifications (id, tenant_id, user_id, category,
                                                      title, body, event_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT DO NOTHING""",
                        UUID.randomUUID(), tenantId, user, category, title, body,
                        UUID.randomUUID().toString());
                if (written > 0) {
                    delivered(tenantId, user, "inbox", category);
                    delivered++;
                }
            }
            if (emailOn) {
                if (attachment == null) {
                    email.send(recipients.addressOf(user), title, body);
                } else {
                    email.send(recipients.addressOf(user), title, body, attachment);
                }
                delivered(tenantId, user, "email", category);
                delivered++;
            }
        }
        return delivered;
    }

    private boolean preference(UUID tenantId, UUID user, String category, String channel) {
        return jdbc.query("""
                SELECT %s FROM nf_preferences WHERE tenant_id = ? AND user_id = ? AND category = ?
                """.formatted(channel),
                (rs, i) -> rs.getBoolean(1), tenantId, user, category)
                .stream().findFirst().orElse(true);   // both channels default on (§8)
    }

    private void delivered(UUID tenantId, UUID user, String channel, String category) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("event", "notification.delivered");
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("tenantId", tenantId.toString());
        payload.put("userId", user.toString());
        payload.put("channel", channel);
        payload.put("category", category);
        payload.put("occurredAt", java.time.Instant.now().toString());
        jdbc.update("""
                INSERT INTO nf_event_outbox (id, tenant_id, event_type, payload)
                VALUES (?, ?, 'notification.delivered', ?::jsonb)""",
                UUID.randomUUID(), tenantId, MAPPER.writeValueAsString(payload));
    }

    /** {@code ${task.field}} tokens — the event payload is the binding set (v1). */
    static String resolve(String template, Map<String, Object> task) {
        if (template == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\$\\{task\\.([a-zA-Z0-9_.]+)}").matcher(template);
        while (matcher.find()) {
            matcher.appendReplacement(result,
                    java.util.regex.Matcher.quoteReplacement(
                            String.valueOf(task.getOrDefault(matcher.group(1), ""))));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** The SMTP leg behind a port so tests observe without a broker. */
    public interface EmailPort {

        void send(String to, String subject, String body);

        /**
         * The attachment-carrying send (PHASE-5 §7): scheduled report delivery
         * streams its export inline — no File Service dependency (that lands in
         * Phase 6 for async large exports).
         */
        default void send(String to, String subject, String body, Attachment attachment) {
            send(to, subject, body);
        }
    }

    /** An inline attachment: filename, content type, and the raw bytes. */
    public record Attachment(String filename, String contentType, byte[] content) {
    }

    /** The JavaMail binding — Mailpit locally (§2), SES-shaped adapters later. */
    @org.springframework.stereotype.Component
    static class SmtpEmailPort implements EmailPort {

        private final JavaMailSender sender;
        private final String from;

        SmtpEmailPort(JavaMailSender sender,
                      @org.springframework.beans.factory.annotation.Value(
                              "${novaforge.mail.from:novaforge@localhost}") String from) {
            this.sender = sender;
            this.from = from;
        }

        @Override
        public void send(String to, String subject, String body) {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            sender.send(message);
        }

        @Override
        public void send(String to, String subject, String body, Attachment attachment) {
            try {
                jakarta.mail.internet.MimeMessage mime = sender.createMimeMessage();
                org.springframework.mail.javamail.MimeMessageHelper helper =
                        new org.springframework.mail.javamail.MimeMessageHelper(mime, true);
                helper.setFrom(from);
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(body);
                helper.addAttachment(attachment.filename(),
                        new org.springframework.core.io.ByteArrayResource(
                                attachment.content()) {
                            @Override
                            public String getFilename() {
                                return attachment.filename();
                            }
                        }, attachment.contentType());
                sender.send(mime);
            } catch (jakarta.mail.MessagingException e) {
                throw new org.springframework.mail.MailSendException(
                        "attachment email failed: " + e.getMessage(), e);
            }
        }
    }
}

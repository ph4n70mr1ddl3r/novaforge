package com.novaforge.deploy.authlistener;

import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * The Keycloak SPI provider (PHASE-3 §5's "deployed config under deploy/"): user
 * auth events publish to {@code novaforge.auth} on the spine. One producer per
 * factory lifecycle; the send is fire-and-forget with a bounded linger — a spine
 * hiccup must never fail the login it audits. Tenant resolution reads the
 * provisioned {@code tenant_id} user attribute; events without one (platform and
 * service-client logins) skip — the audit trail is tenant-scoped by construction.
 */
public class AuthEventListenerProvider implements EventListenerProvider {

    private static final System.Logger LOG = System.getLogger(AuthEventListenerProvider.class.getName());

    /** The provisioner-managed attribute the platform's users carry (PHASE-3 §7). */
    static final String TENANT_ATTRIBUTE = "tenant_id";

    private final KeycloakSession session;
    private final KafkaProducer<String, String> producer;

    public AuthEventListenerProvider(KeycloakSession session, KafkaProducer<String, String> producer) {
        this.session = session;
        this.producer = producer;
    }

    @Override
    public void onEvent(Event event) {
        if (!AuthEvents.auditable(event.getType())) {
            return;
        }
        try {
            RealmModel realm = session.realms().getRealm(event.getRealmId());
            String tenantId = tenantOf(realm, event.getUserId());
            if (tenantId == null) {
                return;   // not a tenant user (platform/service login) — not trail-scoped
            }
            String payload = Json.write(AuthEvents.envelope(event, tenantId,
                    UUID.randomUUID().toString()));
            String key = AuthEvents.key(tenantId, event.getUserId());
            ProducerRecord<String, String> record = new ProducerRecord<>(AuthEvents.TOPIC, key, payload);
            record.headers().add("X-Event-Id", UUID.randomUUID().toString().getBytes());
            record.headers().add("X-Event-Type", String.valueOf(event.getType()).getBytes());
            record.headers().add("X-Tenant-Id", tenantId.getBytes());
            producer.send(record);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "auth event publish failed (login unaffected)", e);
        }
    }

    @Override
    public void onEvent(org.keycloak.events.admin.AdminEvent adminEvent, boolean includeRepresentation) {
        // Permission changes in the platform ride the platform-admin API's
        // permission.* family (PHASE-3 §4) — realm-side admin ops stay out of v1.
    }

    private String tenantOf(RealmModel realm, String userId) {
        if (realm == null || userId == null) {
            return null;
        }
        UserModel user = session.users().getUserById(realm, userId);
        return user == null ? null : user.getFirstAttribute(TENANT_ATTRIBUTE);
    }

    @Override
    public void close() {
        // the producer is factory-scoped — nothing to release per request
    }
}

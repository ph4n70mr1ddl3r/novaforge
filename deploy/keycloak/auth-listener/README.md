# The Keycloak auth-event listener (PHASE-3 §5)

ARCHITECTURE.md §5 item 5 pins auth events into the durable audit trail, and
PHASE-3 §5 pins the producer: *a Keycloak event listener — deployed config under
`deploy/`, not bespoke service code*. This module is that deployed config: a
Keycloak SPI provider jar (`novaforge-auth`) publishing the closed v1 auth set
(`auth.login`, `auth.login.error`, `auth.logout`, `auth.logout.error`) to the
spine's `novaforge.auth` family topic, tenant-scoped keys, where the Audit
Service's `PlatformEventConsumer` lands them in the append-only trail.

- **Tenant resolution** reads the provisioned `tenant_id` user attribute (the same
  managed attribute the platform's `KeycloakUserProvisioner` ensures); events
  without one (platform and service-client logins) skip — the trail is
  tenant-scoped by construction.
- **The send is fire-and-forget** with a bounded delivery timeout: a spine hiccup
  must never fail the login it audits.

## Build + deploy (local compose)

```bash
mvn -f deploy/keycloak/auth-listener/pom.xml package
cp deploy/keycloak/auth-listener/target/auth-listener-*.jar \
   deploy/compose/keycloak/providers/          # mounted at /opt/keycloak/providers
podman compose -f deploy/compose/novaforge.yaml up -d keycloak
```

The realm export (`novaforge-realm.json`) already carries
`eventsListeners: ["novaforge-auth"]`; the Kafka bootstrap defaults to the
compose in-network listener (`kafka:29092`) — override with the
`NOVAFORGE_KAFKA_BOOTSTRAP` env var on the keycloak service for other stacks.

The module is deliberately standalone (outside the root reactor): its classpath
is Keycloak's own SPI, provided scope; the shaded jar bundles only the Kafka
client. `mvn verify` runs the envelope-mapping suite (`AuthEventsTest`).

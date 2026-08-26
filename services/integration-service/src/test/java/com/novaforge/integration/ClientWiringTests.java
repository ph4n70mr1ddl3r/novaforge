package com.novaforge.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.novaforge.integration.clients.FileClient;
import com.novaforge.integration.clients.PublishedIntegrations;
import com.novaforge.integration.clients.RuntimeClient;
import com.novaforge.testsupport.PostgresTestBase;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The production client wiring, pinned: the integration clients carry dual
 * constructors (a hermetic no-arg base for tests), and without the {@code @Autowired}
 * marker Spring falls back to the no-arg default — every field null, the inbound
 * webhook NPE-ing live while the service-level suites (which stub the legs over HTTP)
 * never noticed. Found live at the Phase 6 exit walkthrough; this test boots the real
 * context and asserts the injected beans carry their collaborators.
 */
@SpringBootTest
class ClientWiringTests extends PostgresTestBase {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("docker.io/library/redis:7.4.11")
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::jdbcUsername);
        registry.add("spring.datasource.password", PostgresTestBase::jdbcPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired PublishedIntegrations published;
    @Autowired RuntimeClient runtimeClient;
    @Autowired FileClient fileClient;

    @Test
    @DisplayName("the injected clients are the wired constructors, not the null-field no-arg base")
    void wiredConstructorsHold() throws Exception {
        assertThat(field(published, "redis")).isNotNull();
        assertThat(field(published, "metadata")).isNotNull();
        assertThat(field(runtimeClient, "runtime")).isNotNull();
        assertThat(field(fileClient, "files")).isNotNull();
    }

    private static Object field(Object bean, String name) throws Exception {
        Field field = bean.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(bean);
    }
}

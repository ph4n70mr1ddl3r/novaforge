package com.novaforge.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PHASE-0 §5.3: TenantContext thread semantics. */
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("current() is empty when nothing is bound")
    void emptyByDefault() {
        assertThat(TenantContext.current()).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("set/current/clear round-trip")
    void setCurrentClear() {
        TenantContext.set(new TenantContext.Context("tenant-1", "actor-1"));
        assertThat(TenantContext.current())
                .hasValue(new TenantContext.Context("tenant-1", "actor-1"));
        TenantContext.clear();
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("context is thread-local: another thread sees nothing")
    void threadLocal() throws InterruptedException {
        TenantContext.set(new TenantContext.Context("tenant-1", "actor-1"));
        AtomicReference<Optional<TenantContext.Context>> seen = new AtomicReference<>();
        Thread other = new Thread(() -> seen.set(TenantContext.current()));
        other.start();
        other.join();
        assertThat(seen.get()).isEmpty();
        assertThat(TenantContext.current()).isPresent();
    }

    @Test
    @DisplayName("with() restores the previous binding, including none")
    void withRestoresPrevious() {
        TenantContext.set(new TenantContext.Context("outer", "actor-o"));
        TenantContext.with(new TenantContext.Context("inner", "actor-i"),
                () -> assertThat(TenantContext.require().tenantId()).isEqualTo("inner"));
        assertThat(TenantContext.require().tenantId()).isEqualTo("outer");

        TenantContext.clear();
        TenantContext.with(new TenantContext.Context("inner", "actor-i"), () -> { });
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("with() restores the previous binding even when the action throws")
    void withRestoresOnFailure() {
        TenantContext.set(new TenantContext.Context("outer", "actor-o"));
        assertThatThrownBy(() -> TenantContext.with(new TenantContext.Context("inner", "actor-i"),
                () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.require().tenantId()).isEqualTo("outer");
    }

    @Test
    @DisplayName("require() fails closed when nothing is bound")
    void requireFailsClosed() {
        assertThatThrownBy(TenantContext::require).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Context rejects blank tenant or actor")
    void contextValidated() {
        assertThatThrownBy(() -> new TenantContext.Context("", "actor")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantContext.Context("tenant", null)).isInstanceOf(IllegalArgumentException.class);
    }
}

package com.novaforge.security;

import com.novaforge.common.context.TenantContext;
import java.util.Optional;
import org.springframework.core.task.TaskDecorator;

/**
 * Propagates the {@link TenantContext} binding into executor worker threads
 * (PHASE-1 §3): the binding is captured at submit time and restored per run, so pool
 * threads never leak a previous task's tenant.
 *
 * <pre>{@code
 * executor.setTaskDecorator(new TenantTaskDecorator());
 * }</pre>
 */
public class TenantTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Optional<TenantContext.Context> captured = TenantContext.current();
        return () -> {
            if (captured.isPresent()) {
                TenantContext.with(captured.get(), runnable);
            } else {
                TenantContext.clear();
                runnable.run();
            }
        };
    }
}

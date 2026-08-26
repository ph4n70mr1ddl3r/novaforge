package com.novaforge.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Auto-applies {@link TenantTaskDecorator} to all {@link ThreadPoolTaskExecutor} beans
 * so tenant context propagates to pooled platform threads (PHASE-1 §3).
 * Services using virtual threads (Java 21+) inherit context via {@link InheritableThreadLocal}
 * in {@link com.novaforge.common.context.TenantContext} and do not need decoration.
 */
@AutoConfiguration
@ConditionalOnClass(ThreadPoolTaskExecutor.class)
public class TenantTaskAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    TaskDecorator tenantTaskDecorator() {
        return new TenantTaskDecorator();
    }

    /**
     * Post-processes all ThreadPoolTaskExecutor beans to apply the decorator.
     * Runs after all executors are constructed but before they're used.
     */
    @Bean
    static TenantTaskExecutorPostProcessor tenantTaskExecutorPostProcessor(TaskDecorator decorator) {
        return new TenantTaskExecutorPostProcessor(decorator);
    }

    static class TenantTaskExecutorPostProcessor implements org.springframework.beans.factory.config.BeanPostProcessor {

        private final TaskDecorator decorator;

        TenantTaskExecutorPostProcessor(TaskDecorator decorator) {
            this.decorator = decorator;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof ThreadPoolTaskExecutor executor) {
                // Spring Boot 4 / Spring Framework 7: setTaskDecorator is idempotent
                // (re-setting the same decorator is a no-op).
                executor.setTaskDecorator(decorator);
            }
            return bean;
        }
    }
}
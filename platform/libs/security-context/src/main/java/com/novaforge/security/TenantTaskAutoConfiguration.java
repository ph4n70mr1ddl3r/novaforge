package com.novaforge.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Auto-applies {@link TenantTaskDecorator} so tenant context propagates to executor
 * threads (PHASE-1 §3). Two legs: the decorator bean itself is consumed by Boot's
 * auto-configured application executor — in both the pool and the virtual-thread
 * model, where {@code SimpleAsyncTaskExecutor} applies the decorator per submitted
 * task (a virtual thread inherits nothing: TenantContext is a plain ThreadLocal, and
 * the decorator is the only carrier) — and the post-processor below covers additional
 * custom {@link ThreadPoolTaskExecutor} beans.
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
                // Effective even here, after initialization: Spring Framework 7
                // resolves the decorator per submitted task, not once at pool creation.
                executor.setTaskDecorator(decorator);
            }
            return bean;
        }
    }
}
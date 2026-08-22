package com.novaforge.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * NovaForge Notification Service (port 8088, PHASE-4 §8): a pure spine consumer —
 * {@code task.*} and {@code sla.*} arrive, built-in platform templates render,
 * per-user preferences filter, and the platform inbox plus SMTP email deliver, with
 * {@code notification.delivered} riding back. Synthetic actors (ADR-010 #3) have no
 * channels — both channels skip, the triggering events remain the assertable surface.
 */
@SpringBootApplication
@EnableScheduling
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}

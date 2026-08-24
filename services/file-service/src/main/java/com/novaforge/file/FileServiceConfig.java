package com.novaforge.file;

import com.novaforge.file.storage.StoragePort;
import com.novaforge.file.virus.VirusScanner;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring (PHASE-6 §8): the wall clock bean (tests bind a movable one for the
 * §11 presign-expiry check), the storage binding (MinIO in every deployed
 * profile; tests register the in-memory twin, which wins by
 * {@code @ConditionalOnMissingBean}), and the config-gated ClamAV hook (§13 Q2 —
 * the scanner bean exists only when the gate is on).
 */
@Configuration
public class FileServiceConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            value = "novaforge.storage.binding", havingValue = "minio", matchIfMissing = true)
    StoragePort storage(@Value("${novaforge.storage.endpoint}") String endpoint,
                        @Value("${novaforge.storage.access-key}") String accessKey,
                        @Value("${novaforge.storage.secret-key}") String secretKey,
                        @Value("${novaforge.storage.bucket}") String bucket) {
        return new StoragePort.MinioStorage(endpoint, accessKey, secretKey, bucket);
    }

    /** The clamd binding — present only when the config gate is on (§13 Q2). */
    @Bean
    @ConditionalOnMissingBean(VirusScanner.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            value = "novaforge.file.clamav.enabled", havingValue = "true")
    VirusScanner clamd(@Value("${novaforge.file.clamav.host}") String host,
                       @Value("${novaforge.file.clamav.port}") int port) {
        return new VirusScanner.Clamd(host, port);
    }
}

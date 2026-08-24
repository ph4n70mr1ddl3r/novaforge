package com.novaforge.file;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * NovaForge File Service (port 8091, PHASE-6 §8): MinIO/S3 presigned
 * upload/download, the attachment metadata entity, server-side checksum
 * verification on upload completion, and the config-gated ClamAV hook —
 * infected files quarantine (download blocked) and raise an audit event.
 * The `file` field type's upload path: values are attachment ids.
 */
@SpringBootApplication
@EnableScheduling
public class FileServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileServiceApplication.class, args);
    }
}

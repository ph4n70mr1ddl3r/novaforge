package com.novaforge.file.storage;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Object storage port (PHASE-6 §8): put/get bytes under an object key, plus
 * presigned upload/download URLs with the pinned 15-minute expiry. The MinIO
 * binding speaks the S3-compatible API of the compose stack (or any S3 endpoint);
 * tests bind the in-memory twin — the service's logic (checksums, quarantine,
 * grants) is storage-agnostic by construction.
 */
public interface StoragePort {

    /** Stores {@code content} under {@code objectKey}. */
    void put(String objectKey, byte[] content, String contentType);

    /** Reads the object back (checksum verification, scanning, downloads). */
    byte[] get(String objectKey);

    /** A presigned URL for the mode and key, valid for {@code expirySeconds}. */
    String presign(String objectKey, Mode mode, int expirySeconds);

    /** Removes an object (a failed upload's rejected bytes). */
    void remove(String objectKey);

    enum Mode {UPLOAD, DOWNLOAD}

    /** The MinIO/S3 binding (compose MinIO at API 9000, §2) — wired by FileServiceConfig. */
    class MinioStorage implements StoragePort {

        private final io.minio.MinioClient client;
        private final String bucket;

        public MinioStorage(String endpoint, String accessKey, String secretKey, String bucket) {
            this.client = io.minio.MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();
            this.bucket = bucket;
            try {
                if (!client.bucketExists(io.minio.BucketExistsArgs.builder().bucket(bucket).build())) {
                    client.makeBucket(io.minio.MakeBucketArgs.builder().bucket(bucket).build());
                }
            } catch (Exception e) {
                throw new PlatformException(PlatformErrorCode.INTERNAL,
                        "storage bucket check failed: " + e.getMessage());
            }
        }

        @Override
        public void put(String objectKey, byte[] content, String contentType) {
            try {
                client.putObject(io.minio.PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(new ByteArrayInputStream(content), content.length, -1)
                        .contentType(contentType)
                        .build());
            } catch (Exception e) {
                throw new PlatformException(PlatformErrorCode.INTERNAL,
                        "storage put failed: " + e.getMessage());
            }
        }

        @Override
        public byte[] get(String objectKey) {
            try (var stream = client.getObject(
                    io.minio.GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
                return stream.readAllBytes();
            } catch (Exception e) {
                throw new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "object " + objectKey + " not readable: " + e.getMessage());
            }
        }

        @Override
        public String presign(String objectKey, Mode mode, int expirySeconds) {
            try {
                return client.getPresignedObjectUrl(io.minio.GetPresignedObjectUrlArgs.builder()
                        .method(mode == Mode.UPLOAD ? io.minio.http.Method.PUT
                                : io.minio.http.Method.GET)
                        .bucket(bucket)
                        .object(objectKey)
                        .expiry(expirySeconds)
                        .build());
            } catch (Exception e) {
                throw new PlatformException(PlatformErrorCode.INTERNAL,
                        "presign failed: " + e.getMessage());
            }
        }

        @Override
        public void remove(String objectKey) {
            try {
                client.removeObject(io.minio.RemoveObjectArgs.builder()
                        .bucket(bucket).object(objectKey).build());
            } catch (Exception e) {
                throw new PlatformException(PlatformErrorCode.INTERNAL,
                        "storage remove failed: " + e.getMessage());
            }
        }
    }

    /** The in-memory twin — hermetic tests, no MinIO dependency. */
    class InMemory implements StoragePort {

        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final Map<String, String> presigned = new ConcurrentHashMap<>();

        @Override
        public void put(String objectKey, byte[] content, String contentType) {
            objects.put(objectKey, content);
        }

        @Override
        public byte[] get(String objectKey) {
            byte[] content = objects.get(objectKey);
            if (content == null) {
                throw new PlatformException(PlatformErrorCode.NOT_FOUND,
                        "object " + objectKey + " not readable");
            }
            return content;
        }

        @Override
        public String presign(String objectKey, Mode mode, int expirySeconds) {
            String url = "http://storage.local/presigned/" + mode.name().toLowerCase()
                    + "/" + objectKey + "?expires=" + expirySeconds + "&nonce="
                    + UUID.randomUUID();
            presigned.put(url, objectKey);
            return url;
        }

        @Override
        public void remove(String objectKey) {
            objects.remove(objectKey);
        }

        public Map<String, byte[]> objects() {
            return objects;
        }
    }
}

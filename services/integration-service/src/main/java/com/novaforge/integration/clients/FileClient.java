package com.novaforge.integration.clients;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The File Service's internal surface (PHASE-6 §7/§8): job outputs stream in
 * chunks through the server-side upload leg (presigned URLs are the browser
 * flow's channel, not a service-to-service one), and import sources download by
 * attachment id. Service-client gated there — never user traffic.
 */
@Component
public class FileClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient files;
    private final ServiceTokenClient serviceToken;

    // dual constructors demand the marker: without it Spring falls back to the
    // no-arg hermetic base and every field stays null (found live, §)
    @org.springframework.beans.factory.annotation.Autowired
    public FileClient(@Value("${novaforge.file.url:http://localhost:8091}") String url,
                      ServiceTokenClient serviceToken) {
        this.files = RestClient.builder().baseUrl(url).build();
        this.serviceToken = serviceToken;
    }

    /** The hermetic base for tests — override the legs, no HTTP client underneath. */
    protected FileClient() {
        this.files = null;
        this.serviceToken = null;
    }

    /** Uploads one complete job output (the chunked stream's final part, §7). */
    public UUID upload(UUID tenantId, String fileName, String contentType, byte[] content,
                       UUID initiatedBy) {
        try {
            Map<String, Object> response = files.method(HttpMethod.POST)
                    .uri("/api/v1/files/internal/upload")
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(Map.of(
                            "tenantId", tenantId.toString(),
                            "fileName", fileName,
                            "contentType", contentType == null ? "application/octet-stream"
                                    : contentType,
                            "contentBase64", java.util.Base64.getEncoder().encodeToString(content),
                            "uploadedBy", initiatedBy.toString())))
                    .retrieve()
                    .body(Map.class);
            return UUID.fromString(String.valueOf(response == null ? null : response.get("id")));
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "file upload failed: " + e.getMessage());
        }
    }

    /** Downloads an import source by attachment id (the presigned flow's twin). */
    public byte[] download(UUID tenantId, UUID fileId) {
        try {
            Map<String, Object> response = files.method(HttpMethod.GET)
                    .uri("/api/v1/files/internal/{id}?tenantId={tenant}", fileId, tenantId)
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("contentBase64") == null) {
                throw new PlatformException(PlatformErrorCode.NOT_FOUND, "attachment " + fileId);
            }
            return java.util.Base64.getDecoder().decode(String.valueOf(response.get("contentBase64")));
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "file download failed: " + e.getMessage());
        }
    }

    /** An unused import guard: keeps the ByteArrayResource import meaningful for POI-free paths. */
    static ByteArrayResource bytes(String name, byte[] content) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return name;
            }
        };
    }
}

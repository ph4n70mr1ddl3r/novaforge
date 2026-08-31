package com.novaforge.reporting.notify;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scheduled-delivery fan-out (§7): the rendered export travels to the Notification
 * Service's internal send surface with the platform service client — the built-in
 * {@code report-delivery} template category, the attachment streaming inline from the
 * run's render (no File Service dependency). Recipients are roles and/or users; the
 * Notification Service resolves role holders and filters by preference.
 */
@Component
public class DeliveryClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient notification;
    private final ServiceTokenClient serviceToken;

    public DeliveryClient(@Value("${novaforge.notification.url:http://localhost:8088}") String url,
                          ServiceTokenClient serviceToken) {
        this.notification = RestClient.builder().baseUrl(url)
                        .requestFactory(bounded(url))
                        .build();
        this.serviceToken = serviceToken;
    }

    /** Delivers to the given recipients; returns the notification service's summary. */
    public Map<String, Object> deliver(UUID tenantId, String reportId, String appApiName,
                                       List<String> recipientRoles, List<String> recipientUsers,
                                       String format, byte[] attachment, String deliveryId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("category", "report-delivery");
        body.put("title", "Report " + reportId + " (" + appApiName + ")");
        body.put("body", "The scheduled report " + reportId + " of app " + appApiName
                + " is attached (" + format + ").");
        if (deliveryId != null && !deliveryId.isBlank()) {
            // the scheduler's fired window — a retried fire collapses, the next
            // window delivers fresh
            body.put("deliveryId", deliveryId);
        }
        Map<String, Object> recipients = new LinkedHashMap<>();
        recipients.put("roles", recipientRoles == null ? List.of() : recipientRoles);
        recipients.put("users", recipientUsers == null ? List.of() : recipientUsers);
        body.put("recipients", recipients);
        Map<String, Object> attachmentEnvelope = new LinkedHashMap<>();
        attachmentEnvelope.put("filename", reportId + "." + format);
        attachmentEnvelope.put("contentType", format.equals("csv") ? "text/csv"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        attachmentEnvelope.put("contentBase64",
                Base64.getEncoder().encodeToString(attachment));
        body.put("attachment", attachmentEnvelope);
        return notification.method(HttpMethod.POST)
                .uri("/api/v1/notifications/internal/send")
                .headers(h -> h.setBearerAuth(serviceToken.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(MAPPER.writeValueAsString(body))
                .exchange((request, response) -> {
                    String text = new String(response.getBody().readAllBytes(),
                            StandardCharsets.UTF_8);
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        JsonNode problem = MAPPER.readTree(text);
                        throw new PlatformException(PlatformErrorCode.INTERNAL,
                                "notification delivery failed: "
                                        + problem.path("detail").asString() + " (" + text + ")");
                    }
                    return MAPPER.readValue(text, Map.class);
                });
    }

    /**
     * East-west calls are bounded (the pattern the other internal clients already
     * ride): a hung upstream must fail in seconds, not hold the calling thread —
     * the job scanner runs jobs serially on one scheduler thread, so an unbounded
     * read stalls every tenant's pipeline.
     */
    private static org.springframework.http.client.SimpleClientHttpRequestFactory bounded(String ignored) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(60_000);   // the export renders the dataset inline
        return factory;
    }
}

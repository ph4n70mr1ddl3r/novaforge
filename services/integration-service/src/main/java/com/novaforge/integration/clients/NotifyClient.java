package com.novaforge.integration.clients;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.security.ServiceTokenClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Notification Service's internal send surface (PHASE-4 §8/PHASE-5 §7):
 * job-completed delivery rides the same leg the scheduled report deliveries do —
 * the built-in {@code job-completed} category joining the v1 defaults per
 * PHASE-4 §8's growth path (§7), delivered to the job's initiating user.
 */
@Component
public class NotifyClient {

    private static final Logger LOG = LoggerFactory.getLogger(NotifyClient.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public static final String CATEGORY_JOB_COMPLETED = "job-completed";

    private final RestClient notifications;
    private final ServiceTokenClient serviceToken;

    public NotifyClient(@Value("${novaforge.notification.url:http://localhost:8088}") String url,
                        ServiceTokenClient serviceToken) {
        this.notifications = RestClient.builder().baseUrl(url).build();
        this.serviceToken = serviceToken;
    }

    /** Notifies the initiating user; a notification failure never fails the job. */
    public void jobCompleted(UUID tenantId, UUID job, UUID user, String summary) {
        try {
            notifications.method(HttpMethod.POST)
                    .uri("/api/v1/notifications/internal/send")
                    .headers(headers -> headers.setBearerAuth(serviceToken.token()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(Map.of(
                            "tenantId", tenantId.toString(),
                            "category", CATEGORY_JOB_COMPLETED,
                            "title", "Job " + job + " completed",
                            "body", summary,
                            "recipients", Map.of("users", List.of(user.toString())))))
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            LOG.warn("job-completed notification for {} failed: {}", job, e.getMessage());
        }
    }
}

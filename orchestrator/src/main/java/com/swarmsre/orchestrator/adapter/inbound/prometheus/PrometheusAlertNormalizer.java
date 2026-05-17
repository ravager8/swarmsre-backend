package com.swarmsre.orchestrator.adapter.inbound.prometheus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmsre.orchestrator.adapter.inbound.IncidentNormalizer;
import com.swarmsre.orchestrator.domain.IncidentEvent;
import com.swarmsre.orchestrator.domain.Severity;
import com.swarmsre.orchestrator.domain.Source;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Translates an Alertmanager webhook payload into our canonical IncidentEvent.
 *
 * Only firing alerts are converted into incidents — resolved-only payloads are
 * passed through to the pipeline as a no-op (handled at the controller layer).
 */
@Component
@RequiredArgsConstructor
public class PrometheusAlertNormalizer implements IncidentNormalizer<PrometheusAlertPayload> {

    private final ObjectMapper objectMapper;

    @Override
    public IncidentEvent normalize(PrometheusAlertPayload payload) {
        // Take the first firing alert — Alertmanager groups related alerts but
        // for our hackathon scope we treat each group as one incident.
        PrometheusAlertPayload.Alert alert = payload.alerts().stream()
                .filter(a -> "firing".equalsIgnoreCase(a.status()))
                .findFirst()
                .orElse(payload.alerts().get(0));

        Map<String, String> labels = alert.labels() == null ? Map.of() : alert.labels();
        Map<String, String> annotations = alert.annotations() == null ? Map.of() : alert.annotations();

        String alertName = labels.getOrDefault("alertname", "unknown");
        String service = labels.getOrDefault("service",
                labels.getOrDefault("instance", "unknown"));

        String incidentId = "INC-" + (alert.fingerprint() != null
                ? alert.fingerprint()
                : UUID.randomUUID().toString().substring(0, 8));

        return new IncidentEvent(
                incidentId,
                Source.PROMETHEUS,
                Severity.fromString(labels.get("severity")),
                service,
                annotations.getOrDefault("summary", alertName),
                alert.startsAt() != null ? alert.startsAt() : Instant.now(),
                objectMapper.valueToTree(payload)
        );
    }
}

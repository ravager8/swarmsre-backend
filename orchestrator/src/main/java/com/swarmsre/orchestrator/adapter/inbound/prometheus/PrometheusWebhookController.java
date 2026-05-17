package com.swarmsre.orchestrator.adapter.inbound.prometheus;

import com.swarmsre.orchestrator.domain.IncidentEvent;
import com.swarmsre.orchestrator.pipeline.IncidentPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Receives webhook callbacks from Alertmanager. The Prometheus-specific payload
 * is normalized into an IncidentEvent and handed to the pipeline.
 *
 * Configure Alertmanager (see target-app/infra/alertmanager.yml) with:
 *   url: http://host.docker.internal:8081/webhook/prometheus
 */
@RestController
@RequestMapping("/webhook/prometheus")
@RequiredArgsConstructor
@Slf4j
public class PrometheusWebhookController {

    private final PrometheusAlertNormalizer normalizer;
    private final IncidentPipeline pipeline;

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(@RequestBody PrometheusAlertPayload payload) {
        log.info("Prometheus webhook received: status={} alertCount={}",
                payload.status(), payload.alerts() == null ? 0 : payload.alerts().size());

        if (payload.alerts() == null || payload.alerts().isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "no alerts in payload"));
        }

        // Resolved-only notifications: do not start a swarm.
        if ("resolved".equalsIgnoreCase(payload.status())) {
            log.info("Resolved-only payload — skipping pipeline dispatch");
            return ResponseEntity.ok(Map.of("status", "resolved-acknowledged"));
        }

        IncidentEvent event = normalizer.normalize(payload);
        pipeline.dispatch(event);

        return ResponseEntity.accepted().body(Map.of(
                "incidentId", event.incidentId(),
                "status", "received"
        ));
    }
}

package com.swarmsre.orchestrator.adapter.inbound.prometheus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Subset of Alertmanager's webhook payload (v4) — the fields we actually use.
 * Spec: https://prometheus.io/docs/alerting/latest/configuration/#webhook_config
 *
 * Unknown fields are ignored so Alertmanager version bumps do not break parsing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PrometheusAlertPayload(
        String status,            // "firing" or "resolved"
        String receiver,
        Integer truncatedAlerts,
        List<Alert> alerts
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Alert(
            String status,                 // "firing" or "resolved"
            Map<String, String> labels,    // alertname, severity, service, ...
            Map<String, String> annotations, // summary, description, ...
            Instant startsAt,
            Instant endsAt,
            String generatorURL,
            String fingerprint
    ) {}
}

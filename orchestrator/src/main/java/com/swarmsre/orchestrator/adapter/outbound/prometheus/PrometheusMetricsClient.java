package com.swarmsre.orchestrator.adapter.outbound.prometheus;

import com.fasterxml.jackson.databind.JsonNode;
import com.swarmsre.orchestrator.adapter.outbound.MetricsClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Calls Prometheus's HTTP query API:
 *   GET {base-url}/api/v1/query?query={expression}
 *
 * Spec: https://prometheus.io/docs/prometheus/latest/querying/api/
 */
@Component
@Slf4j
public class PrometheusMetricsClient implements MetricsClient {

    private final RestClient http;
    private final String baseUrl;

    public PrometheusMetricsClient(
            RestClient http,
            @Value("${swarmsre.metrics.prometheus.base-url}") String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl;
    }

    @Override
    public JsonNode query(String expression) {
        String url = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/api/v1/query")
                .queryParam("query", expression)
                .build()
                .toUriString();

        log.debug("Prometheus query: {}", url);

        return http.get()
                .uri(url)
                .retrieve()
                .body(JsonNode.class);
    }

    @Override
    public String name() {
        return "prometheus";
    }
}

package com.swarmsre.orchestrator.adapter.outbound;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Generic metric query interface — implementations target a specific
 * observability backend (Prometheus today; CloudWatch / Datadog later).
 *
 * Returning JsonNode keeps the contract loose so each backend can return
 * whatever shape is convenient. Agents that consume this should treat the
 * result as opaque JSON to be stringified into the LLM context.
 */
public interface MetricsClient {

    /**
     * Single point-in-time query.
     *
     * @param expression backend-specific query (PromQL for Prometheus)
     */
    JsonNode query(String expression);

    /** Identifier for tooling and logs (e.g. "prometheus"). */
    String name();
}

package com.swarmsre.orchestrator.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Canonical incident shape consumed by the orchestrator. Every inbound adapter
 * (Prometheus, Datadog, generic, future sources) MUST normalize to this type
 * before handing off to the pipeline.
 *
 * Contract is locked in project-docs/TEAM_PLAN.md §4.1.
 */
public record IncidentEvent(
        String incidentId,
        Source source,
        Severity severity,
        String service,
        String summary,
        Instant timestamp,
        JsonNode rawPayload
) {}

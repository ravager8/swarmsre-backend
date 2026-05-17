package com.swarmsre.orchestrator.adapter.inbound;

import com.swarmsre.orchestrator.domain.IncidentEvent;

/**
 * Translates a source-specific alert payload into the canonical IncidentEvent.
 * One implementation per observability source (Prometheus, Datadog, etc.).
 */
public interface IncidentNormalizer<T> {
    IncidentEvent normalize(T sourcePayload);
}

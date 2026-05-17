package com.swarmsre.orchestrator.pipeline;

import com.swarmsre.orchestrator.domain.IncidentEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Entry point for any normalized incident, regardless of source.
 *
 * Today this is a stub that just logs and acks. The agent swarm,
 * tool execution, SSE streaming, and PR creation will be wired in here.
 *
 * Adapters MUST go through this class — they should not invoke agents
 * or tools directly.
 */
@Service
@Slf4j
public class IncidentPipeline {

    public void dispatch(IncidentEvent event) {
        log.info("Pipeline dispatch: incidentId={} source={} severity={} service={} summary='{}'",
                event.incidentId(),
                event.source(),
                event.severity(),
                event.service(),
                event.summary());

        // TODO P1: kick off the agent swarm here.
        //   - record incident in memory store
        //   - emit SSE "AGENT_STEP" events
        //   - call orchestrator agent (LLM)
        //   - dispatch to specialist sub-agents
        //   - on completion, emit "PROPOSED_FIX" event
    }
}

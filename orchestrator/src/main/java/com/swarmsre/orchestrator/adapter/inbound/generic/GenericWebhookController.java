package com.swarmsre.orchestrator.adapter.inbound.generic;

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
 * Generic adapter — accepts incidents already shaped to our canonical
 * IncidentEvent. Useful for curl-driven demos and testing the pipeline
 * without a real observability tool.
 */
@RestController
@RequestMapping("/webhook/incident")
@RequiredArgsConstructor
@Slf4j
public class GenericWebhookController {

    private final IncidentPipeline pipeline;

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(@RequestBody IncidentEvent event) {
        log.info("Generic webhook received: {} severity={} service={}",
                event.incidentId(), event.severity(), event.service());
        pipeline.dispatch(event);
        return ResponseEntity.accepted().body(Map.of(
                "incidentId", event.incidentId() == null ? "auto-generated" : event.incidentId(),
                "status", "received"
        ));
    }
}

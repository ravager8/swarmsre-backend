package com.swarmsre.controller;

import com.swarmsre.dto.IncidentEvent;
import com.swarmsre.orchestrator.SwarmOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/incident")
@RequiredArgsConstructor
public class IncidentController {

    private final SwarmOrchestrator orchestrator;

    @PostMapping("/webhook")
    public ResponseEntity<String> receiveIncident(@RequestBody IncidentEvent event) {
        // Run asynchronously so we immediately return 202 Accepted to the monitoring tool
        new Thread(() -> orchestrator.processIncidentAsync(event)).start();
        return ResponseEntity.accepted().body("Incident received. Swarm deployed.");
    }
}
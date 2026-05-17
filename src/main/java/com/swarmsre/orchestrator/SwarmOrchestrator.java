package com.swarmsre.orchestrator;

import com.swarmsre.dto.IncidentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class SwarmOrchestrator {

    private final ChatClient chatClient;

    public void processIncidentAsync(IncidentEvent event) {
        log.info("Orchestrator triggered for Incident: {}", event.getIncidentId());

        // Placeholder for the actual Agent loop
        // chatClient.prompt().user("Analyze this event: " + event.getSummary()).call().content();
    }
}
package com.swarmsre.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("You are the SwarmSRE Orchestrator. Coordinate incident response by routing tasks to specialized agents.")
                .build();
    }
}
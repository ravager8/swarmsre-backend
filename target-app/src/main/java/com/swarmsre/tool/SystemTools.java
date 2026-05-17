package com.swarmsre.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

@Configuration
@Slf4j
public class SystemTools {

    @Bean
    @Description("Fetches recent system logs for a specified microservice.")
    public Function<String, String> fetchLogs() {
        return serviceName -> {
            log.info("Tool Execution: fetchLogs called for service -> {}", serviceName);
            // Hackathon mock: replace with actual Cosmos DB query later
            return "Mock logs for " + serviceName + ": [ERROR] NullPointerException at line 42";
        };
    }
}
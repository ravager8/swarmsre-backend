package com.swarmsre.orchestrator.domain;

public enum Severity {
    CRITICAL, HIGH, MEDIUM, LOW;

    public static Severity fromString(String raw) {
        if (raw == null) return MEDIUM;
        return switch (raw.trim().toLowerCase()) {
            case "critical", "p1", "page" -> CRITICAL;
            case "high", "p2", "error", "warning" -> HIGH;
            case "medium", "p3" -> MEDIUM;
            case "low", "p4", "info" -> LOW;
            default -> MEDIUM;
        };
    }
}

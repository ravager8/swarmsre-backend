package com.swarmsre.simulator;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/simulate")
@RequiredArgsConstructor
public class SimulationController {

    private final IncidentSimulatorService simulator;

    @PostMapping("/error")
    public ResponseEntity<Map<String, Object>> simulateError(
            @RequestParam(defaultValue = "1") int count) {
        simulator.recordErrors(count);
        return ResponseEntity.ok(Map.of(
                "recorded", count,
                "totalErrors", simulator.currentErrorCount()
        ));
    }
}

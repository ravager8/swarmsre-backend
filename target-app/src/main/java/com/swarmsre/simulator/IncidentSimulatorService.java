package com.swarmsre.simulator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class IncidentSimulatorService {

    private final Counter paymentErrors;

    public IncidentSimulatorService(MeterRegistry registry) {
        this.paymentErrors = Counter.builder("payment_errors_total")
                .description("Total number of payment errors (used to drive demo incidents)")
                .tag("service", "payment-service")
                .register(registry);
    }

    public void recordError() {
        paymentErrors.increment();
        log.info("Simulated payment error. Total so far: {}", (long) paymentErrors.count());
    }

    public void recordErrors(int count) {
        for (int i = 0; i < count; i++) {
            paymentErrors.increment();
        }
        log.info("Simulated {} payment errors. Total so far: {}", count, (long) paymentErrors.count());
    }

    public double currentErrorCount() {
        return paymentErrors.count();
    }
}

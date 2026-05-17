# Example artifacts

Reference files captured from real local runs. Useful for:
- Understanding what a successful startup looks like
- Verifying the format of expected log output
- Onboarding new developers without forcing them to run the full pipeline first

## Files

### `sample-startup-and-alert-simulation.log`

Full stdout from a Spring Boot run that:

1. Started the app (banner, Tomcat on 8080, JPA + Hikari init)
2. Received a webhook at `POST /api/incident/webhook` (logged `Orchestrator triggered for Incident: INC-001`)
3. Took ~35 calls to `POST /api/simulate/error?count=5` from a load generator, climbing the counter from 5 → 180
4. Was gracefully shut down via SIGTERM

This run drove `rate(payment_errors_total[1m])` above the alert threshold and caused
Prometheus to fire `HighPaymentErrorRate` and Alertmanager to mark it `active`.

If your run looks similar to this file, the local pipeline is healthy.

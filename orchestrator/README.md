# orchestrator

The SwarmSRE AI service. Receives incidents from observability tools (Prometheus today; Datadog/CloudWatch later), runs the multi-agent swarm to investigate, and proposes a remediation. The agent swarm itself is **not yet implemented** — what's here is the modular adapter scaffolding.

> **Where you are in the repo:** this README documents the `orchestrator/` subdirectory of the `swarmsre-backend` monorepo. See the top-level [`README.md`](../README.md) for the broader layout and [`../target-app/README.md`](../target-app/README.md) for the fake production service this orchestrator monitors.

---

## What's in this project today

| Layer | Status | Files |
|-------|--------|-------|
| Spring Boot scaffold | ✅ | `OrchestratorApplication.java`, `pom.xml`, `application.properties` |
| Canonical incident type | ✅ | `domain/IncidentEvent.java`, `Severity.java`, `Source.java` |
| Inbound adapters (push) | ✅ | `adapter/inbound/generic/`, `adapter/inbound/prometheus/` |
| Outbound metrics client (pull) | ✅ | `adapter/outbound/prometheus/` |
| Pipeline entry point | 🟡 stub | `pipeline/IncidentPipeline.java` (logs + TODO) |
| Agent swarm (LLM, tools, SSE) | ⚪ not started | — |
| GitHub PR creation | ⚪ not started | — |
| Memory / RAG / persistence | ⚪ not started | — |

---

## Architecture overview

```
                         ┌──────────────────────────────┐
                         │    IncidentPipeline (stub)   │
                         │  ← single entry for all      │
                         │    normalized incidents      │
                         └──────────────────────────────┘
                                     ▲
                  ┌──────────────────┼──────────────────┐
                  │                                     │
PUSH (inbound):                                  PULL (outbound):
adapters receive alerts                          agents query backends
─────────────────────────                        ─────────────────────────
adapter/inbound/                                 adapter/outbound/
├── generic/                                     ├── MetricsClient.java
│   POST /webhook/incident                       │   (interface)
└── prometheus/                                  └── prometheus/
    POST /webhook/prometheus                         PrometheusMetricsClient
    (Alertmanager payload                            GET /api/v1/query
     → IncidentEvent)
```

### Two directions of integration

| Direction | Trigger | Where it lives |
|-----------|---------|----------------|
| **PUSH** — observability tool fires an alert at us | webhook arrives | `adapter/inbound/<source>/` |
| **PULL** — an agent needs supporting data during investigation | tool call from inside the pipeline | `adapter/outbound/<source>/` |

These are independent. You can run only PUSH (alerts trigger the pipeline) or only PULL (agents query Prometheus during investigation) or both.

---

## Adding a new source

The package layout is the contract. To add e.g. Datadog:

1. **Inbound (push):**
   - Create `adapter/inbound/datadog/DatadogAlertPayload.java` (DTO matching their webhook)
   - Create `adapter/inbound/datadog/DatadogAlertNormalizer.java` implementing `IncidentNormalizer<DatadogAlertPayload>`
   - Create `adapter/inbound/datadog/DatadogWebhookController.java` exposing `POST /webhook/datadog`
2. **Outbound (pull):**
   - Create `adapter/outbound/datadog/DatadogMetricsClient.java` implementing `MetricsClient`
   - Configure base URL in `application.properties` under `swarmsre.metrics.datadog.*`
3. Add `DATADOG` enum value to `domain/Source.java`

No changes needed to the pipeline, agents, or other adapters.

---

## Endpoints exposed

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/webhook/incident` | Generic adapter — accepts `IncidentEvent` JSON directly. Use this for curl-driven demos. |
| `POST` | `/webhook/prometheus` | Prometheus adapter — accepts an Alertmanager v4 webhook payload. |
| `GET`  | `/actuator/health` | Spring Boot health check. |

Default port: **8081** (deliberately different from `target-app` on 8080).

---

## Configuration

`application.properties`:

| Property | Default | Purpose |
|----------|---------|---------|
| `server.port` | `8081` | App HTTP port |
| `swarmsre.metrics.prometheus.base-url` | `http://localhost:9090` | Where the outbound `PrometheusMetricsClient` sends queries |

Override via env var: `SWARMSRE_METRICS_PROMETHEUS_BASE_URL=...`

---

## Running locally

### Prerequisites

Same as `target-app/`: Java 21, Maven Wrapper bundled, no separate Maven install needed. See [`../target-app/README.md`](../target-app/README.md) for full prerequisite checks.

### Build

From the `orchestrator/` directory:

```bash
./mvnw clean package -DskipTests
```

### Run

```bash
java -jar target/orchestrator-0.0.1-SNAPSHOT.jar
```

Expected:
```
Started OrchestratorApplication in N seconds
Tomcat started on port 8081
```

### Smoke test — generic webhook

```bash
curl -X POST http://localhost:8081/webhook/incident \
  -H "Content-Type: application/json" \
  -d '{
    "incidentId": "INC-T1",
    "source": "GENERIC",
    "severity": "CRITICAL",
    "service": "payment-service",
    "summary": "Manual test trigger",
    "timestamp": "2026-05-17T18:00:00Z",
    "rawPayload": {}
  }'
```

Expected: `202 Accepted` and a log line:
```
Generic webhook received: INC-T1 severity=CRITICAL service=payment-service
Pipeline dispatch: incidentId=INC-T1 source=GENERIC ...
```

### Smoke test — Prometheus webhook

Simulate an Alertmanager payload:

```bash
curl -X POST http://localhost:8081/webhook/prometheus \
  -H "Content-Type: application/json" \
  -d '{
    "status": "firing",
    "receiver": "swarmsre-orchestrator",
    "alerts": [{
      "status": "firing",
      "labels": {
        "alertname": "HighPaymentErrorRate",
        "severity": "critical",
        "service": "payment-service"
      },
      "annotations": {
        "summary": "Payment error rate above threshold"
      },
      "startsAt": "2026-05-17T18:00:00Z",
      "fingerprint": "abc123def"
    }]
  }'
```

Expected: `202 Accepted` and:
```
Prometheus webhook received: status=firing alertCount=1
Pipeline dispatch: incidentId=INC-abc123def source=PROMETHEUS ...
```

### End-to-end with target-app + real Prometheus

1. Run `target-app` (port 8080) — see [`../target-app/README.md`](../target-app/README.md)
2. Run the orchestrator (this project, port 8081)
3. Edit `../target-app/infra/alertmanager.yml` and set the webhook URL to:
   ```yaml
   url: 'http://host.docker.internal:8081/webhook/prometheus'
   ```
4. Run Prometheus + Alertmanager (target-app Level 3 instructions)
5. Drive errors at the target-app — alert fires → Alertmanager → orchestrator pipeline

---

## Module layout

```
orchestrator/
├── pom.xml
├── src/main/java/com/swarmsre/orchestrator/
│   ├── OrchestratorApplication.java
│   ├── domain/
│   │   ├── IncidentEvent.java          canonical (TEAM_PLAN §4.1)
│   │   ├── Severity.java
│   │   └── Source.java
│   ├── adapter/
│   │   ├── inbound/
│   │   │   ├── IncidentNormalizer.java         interface
│   │   │   ├── generic/
│   │   │   │   └── GenericWebhookController.java
│   │   │   └── prometheus/
│   │   │       ├── PrometheusAlertPayload.java
│   │   │       ├── PrometheusAlertNormalizer.java
│   │   │       └── PrometheusWebhookController.java
│   │   └── outbound/
│   │       ├── MetricsClient.java              interface
│   │       └── prometheus/
│   │           └── PrometheusMetricsClient.java
│   ├── pipeline/
│   │   └── IncidentPipeline.java               stub today
│   └── config/
│       └── HttpClientConfig.java
└── src/main/resources/
    └── application.properties
```

---

## What's next (for whoever picks this up)

1. **Wire the agent swarm** inside `IncidentPipeline.dispatch()` — see `../project-docs/TEAM_PLAN.md` §4 for contracts (agent step events, proposed fix payload, etc.)
2. **Add SSE controller** at `GET /events/{incidentId}` to stream agent steps to the frontend
3. **Add a `LogsClient`** outbound interface + implementation that hits `target-app`'s log endpoint (once `target-app` exposes one)
4. **Plug in real LLM** (Anthropic SDK with Bedrock backend, per the team Q&A decision)
5. **Add memory store** for past incidents (Cosmos / Mongo / in-memory per Q6/Q7 decision)

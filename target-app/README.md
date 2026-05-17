# swarmsre-backend

Fake target application for the **SwarmSRE** hackathon project. This service plays the role of a "production app" that experiences incidents — it generates logs, exposes metrics, and accepts incident webhooks. It is **not** the AI orchestrator (that lives in a separate project).

This README is written so a fresh Claude Code session (or any developer) can clone the repo, verify the environment, and have the app running locally end-to-end without external context.

---

## What this app does

- Exposes `POST /api/incident/webhook` to receive a standardized `IncidentEvent` JSON payload
- Returns `202 Accepted` immediately and dispatches to a stub orchestrator on a background thread
- Exposes `/actuator/prometheus` with JVM, HTTP, disk, and tagged application metrics in Prometheus text format
- Exposes `POST /api/simulate/error` to increment a custom `payment_errors_total` counter — used to drive demo incidents and trigger Prometheus alerts
- Runs an in-memory H2 database (currently unused — JPA scaffolding is in place for future log persistence)
- Ships with `infra/` configs for Prometheus + Alertmanager so the full alert-firing pipeline can run locally via Docker

It does **not** yet:
- Call any LLM (Spring AI is wired with dummy credentials; the orchestrator method is a stub)
- Generate fake logs on a schedule (only error-counter simulation today)
- Persist logs or incident records (no JPA entities yet)
- Talk to MongoDB or Cosmos DB
- Forward the Alertmanager webhook to a real orchestrator (alertmanager.yml uses a placeholder URL)

These are planned and tracked in `Swarm-Agents/Hackathon_Project/TEAM_PLAN.md` (sibling docs project).

---

## Prerequisites

Run each check in your terminal. Install only what reports missing.

### 1. Java 21

```bash
java -version
```

**Expected:** `openjdk version "21.x.x"`

If missing or older:
```bash
brew install openjdk@21
echo 'export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"' >> ~/.zshrc
echo 'export JAVA_HOME="/opt/homebrew/opt/openjdk@21"' >> ~/.zshrc
source ~/.zshrc
java -version
```

### 2. Maven (optional)

The repo bundles `./mvnw` (Maven Wrapper), so a system Maven install is **not required**. Skip this unless you prefer using `mvn` directly.

```bash
mvn -v   # optional
```

### 3. curl

```bash
curl --version
```

Pre-installed on macOS. Used to hit the webhook endpoint.

### 4. Docker (only required for Level 2 — running Prometheus locally)

```bash
docker --version
docker ps
```

If missing:
```bash
brew install --cask docker
open -a Docker          # launch Docker Desktop and accept the EULA
```

If you see "Rosetta installation failed" on Apple Silicon, click **"Continue without Rosetta"** — the `prom/prometheus` image is multi-arch and runs natively on ARM64.

### 5. Homebrew (only if you need to install anything above)

```bash
brew --version
```

If missing, install from https://brew.sh.

---

## Project layout

```
swarmsre-backend/
├── pom.xml                                Maven build, Spring Boot 3.5.x, Java 21
├── mvnw, mvnw.cmd                         Maven Wrapper
├── infra/
│   ├── prometheus.yml                     Prometheus scrape + rules + alertmanager config
│   ├── rules.yml                          Alert rules (e.g., HighPaymentErrorRate)
│   └── alertmanager.yml                   Webhook routing config (placeholder URL today)
├── docs/examples/
│   ├── README.md                          What the sample artifacts demonstrate
│   └── sample-startup-and-alert-simulation.log   Reference output of a healthy run
├── src/main/java/com/swarmsre/
│   ├── AgentApplication.java              Spring Boot entry point
│   ├── config/AiConfig.java               ChatClient bean (currently uses dummy creds)
│   ├── controller/IncidentController.java POST /api/incident/webhook
│   ├── dto/IncidentEvent.java             Webhook payload DTO
│   ├── orchestrator/SwarmOrchestrator.java Stub — logs incident ID, no AI yet
│   ├── simulator/
│   │   ├── IncidentSimulatorService.java  Custom Micrometer counter: payment_errors_total
│   │   └── SimulationController.java      POST /api/simulate/error
│   └── tool/SystemTools.java              Mock fetchLogs tool registered with Spring AI
├── src/main/resources/
│   └── application.properties             Server port, Prometheus exposure, dummy AI config, H2
└── src/test/java/com/swarmsre/AgentApplicationTests.java
```

---

## Running the app — Level 1 (just the backend)

This runs the Spring Boot app and exposes its metrics endpoint. **No Docker required.**

### Step 1: Build

From the `swarmsre-backend/` directory:

```bash
./mvnw clean package -DskipTests
```

First build downloads dependencies (~2–3 minutes). Subsequent builds are fast.

**Verify:** `target/swarmsre-backend-0.0.1-SNAPSHOT.jar` exists.

### Step 2: Start the app

Two options.

**Option A — run the jar (recommended for stable background runs):**
```bash
java -jar target/swarmsre-backend-0.0.1-SNAPSHOT.jar
```

**Option B — run via Maven:**
```bash
./mvnw spring-boot:run
```

**Expected log output:**
```
Started AgentApplication in N seconds
Tomcat started on port 8080
```

App is now on `http://localhost:8080`.

### Step 3: Smoke test

In a new terminal:

```bash
# 1. Hit the webhook
curl -X POST http://localhost:8080/api/incident/webhook \
  -H "Content-Type: application/json" \
  -d '{"incidentId":"INC-001","source":"prometheus","severity":"critical","service":"payment-service","summary":"High error rate detected"}'
```

**Expected:** `HTTP 202` with body `Incident received. Swarm deployed.`

Watch the app logs — you should see:
```
Orchestrator triggered for Incident: INC-001
```

```bash
# 2. Confirm metrics are exposed
curl -s http://localhost:8080/actuator/prometheus | head -10
```

**Expected:** Prometheus text-format metrics with labels `application="swarmsre-backend"` and `service="payment-service"`.

```bash
# 3. Check actuator endpoints
curl -s http://localhost:8080/actuator | python3 -m json.tool
```

**Expected:** JSON listing `prometheus`, `health`, and `info` endpoints.

### Step 4: Stop the app

If running in foreground: `Ctrl+C`.

If running in background:
```bash
# Find the PID
lsof -iTCP:8080 -sTCP:LISTEN -t

# Kill it
kill <pid>

# Verify port is free
lsof -iTCP:8080 -sTCP:LISTEN || echo "Port 8080 free"
```

---

## Running the app — Level 2 (with Prometheus scraping)

This adds a Prometheus container that scrapes the app every 5 seconds. **Requires Docker.**

### Prerequisite

The Spring Boot app from Level 1 must be running (port 8080).

### Step 1: Confirm Prometheus config exists

The repo includes `infra/prometheus.yml`:

```yaml
global:
  scrape_interval: 5s
  evaluation_interval: 5s

scrape_configs:
  - job_name: 'swarmsre-backend'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']
        labels:
          environment: 'local'
```

`host.docker.internal` is the Docker-on-Mac DNS name for the host machine.

### Step 2: Run Prometheus

```bash
docker run -d \
  --name swarmsre-prometheus \
  -p 9090:9090 \
  -v "$(pwd)/infra/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
  prom/prometheus
```

First run pulls the image (~200 MB).

### Step 3: Verify Prometheus is scraping the app

```bash
# Container running?
docker ps --filter name=swarmsre-prometheus

# Prometheus reachable?
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:9090/-/ready

# Scrape target healthy?
curl -s http://localhost:9090/api/v1/targets | python3 -c "
import sys, json
d = json.load(sys.stdin)
for t in d['data']['activeTargets']:
    print(f\"  {t['labels']['job']:25} health={t['health']}\")
"
```

**Expected:** target `swarmsre-backend` with `health=up`.

### Step 4: Browse metrics in the Prometheus UI

Open `http://localhost:9090` in a browser:

- **Status → Targets** — confirms scrape is healthy
- **Graph** — try queries like:
  - `application_started_time_seconds`
  - `jvm_memory_used_bytes{area="heap"}`
  - `process_cpu_usage`
  - `http_server_requests_seconds_count` (populates after curling endpoints)

### Step 5: Stop Prometheus

```bash
# Stop (preserves the container for re-use)
docker stop swarmsre-prometheus

# Or remove entirely
docker rm -f swarmsre-prometheus
```

To restart later (same config):
```bash
docker start swarmsre-prometheus
```

---

## Running the app — Level 3 (full alert pipeline with Alertmanager)

This adds a second container — Alertmanager — which receives firing alerts from
Prometheus and dispatches them as webhooks. **Requires Levels 1 and 2 first.**

### Prerequisites

- Spring Boot app running on port 8080 (Level 1)
- Prometheus container running on port 9090 (Level 2)
- `infra/rules.yml` defines the alert rule (already in repo)
- `infra/alertmanager.yml` defines the webhook receiver (already in repo)

### Step 1: Confirm Prometheus is loading the alert rule

The repo's `infra/prometheus.yml` already references `rules.yml` and points at
Alertmanager. If you started Prometheus following the Level 2 instructions
exactly, you also need to mount `rules.yml` into the container:

```bash
docker rm -f swarmsre-prometheus 2>/dev/null

docker run -d \
  --name swarmsre-prometheus \
  -p 9090:9090 \
  -v "$(pwd)/infra/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
  -v "$(pwd)/infra/rules.yml:/etc/prometheus/rules.yml:ro" \
  prom/prometheus
```

Verify the rule loaded:

```bash
curl -s http://localhost:9090/api/v1/rules | grep HighPaymentErrorRate
```

### Step 2: Get a webhook URL for Alertmanager to send to

Until the real `swarmsre-orchestrator` project exists, point Alertmanager at
[webhook.site](https://webhook.site) so you can visually inspect the firing alert:

1. Open https://webhook.site in your browser
2. Copy the unique URL it gives you (e.g., `https://webhook.site/abc-123-...`)
3. Edit `infra/alertmanager.yml` and replace the placeholder `REPLACE_ME_WITH_YOUR_UNIQUE_URL`

### Step 3: Run Alertmanager

```bash
docker run -d \
  --name swarmsre-alertmanager \
  -p 9093:9093 \
  -v "$(pwd)/infra/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro" \
  prom/alertmanager
```

### Step 4: Verify both containers are talking

```bash
docker ps --filter name=swarmsre

curl -s -o /dev/null -w "Prometheus  HTTP %{http_code}\n" http://localhost:9090/-/ready
curl -s -o /dev/null -w "Alertmgr    HTTP %{http_code}\n" http://localhost:9093/-/ready
```

### Step 5: Drive an alert end-to-end

```bash
# Sustained errors above threshold for ~70s
for i in $(seq 1 35); do
  curl -s -X POST "http://localhost:8080/api/simulate/error?count=5" > /dev/null
  sleep 2
done
```

Check alert progression:

```bash
# Current rate (should exceed 0.5)
curl -s "http://localhost:9090/api/v1/query?query=rate(payment_errors_total[1m])"

# Rule state (inactive → pending → firing)
curl -s http://localhost:9090/api/v1/rules | grep -A2 HighPaymentErrorRate

# Active alerts in Alertmanager
curl -s http://localhost:9093/api/v2/alerts
```

Visit the webhook.site URL — you'll see the full Alertmanager payload arrive there.

A reference run is captured in `docs/examples/sample-startup-and-alert-simulation.log`.

### Step 6: Stop the alert pipeline

```bash
docker stop swarmsre-prometheus swarmsre-alertmanager
```

To restart:
```bash
docker start swarmsre-prometheus swarmsre-alertmanager
```

### What this unlocks

When the real `swarmsre-orchestrator` project exists, replace the `webhook.site`
URL in `alertmanager.yml` with `http://host.docker.internal:8081/webhook/prometheus`.
The same alert that's firing today will then trigger the AI agent swarm. No
changes needed in the target app.

---

## Common API calls

### Trigger the incident webhook

```bash
curl -X POST http://localhost:8080/api/incident/webhook \
  -H "Content-Type: application/json" \
  -d '{
    "incidentId": "INC-001",
    "source": "prometheus",
    "severity": "critical",
    "service": "payment-service",
    "summary": "High error rate detected"
  }'
```

Returns: `202 Accepted` with body `Incident received. Swarm deployed.`

### Simulate payment errors (drives Prometheus alerts)

```bash
# One error
curl -X POST http://localhost:8080/api/simulate/error

# Many at once
curl -X POST "http://localhost:8080/api/simulate/error?count=20"
```

Returns: `{"recorded": 20, "totalErrors": 20.0}`

To trip the `HighPaymentErrorRate` alert (rate > 0.5 errors/sec for 30s), drive
sustained traffic for ~70 seconds:

```bash
for i in $(seq 1 35); do
  curl -s -X POST "http://localhost:8080/api/simulate/error?count=5" > /dev/null
  sleep 2
done
```

### Check metrics

```bash
curl -s http://localhost:8080/actuator/prometheus | head -30

# Just the custom counter
curl -s http://localhost:8080/actuator/prometheus | grep payment_errors_total
```

### Check actuator discovery

```bash
curl -s http://localhost:8080/actuator
```

---

## Configuration

Defaults live in `src/main/resources/application.properties`:

| Property | Default | Notes |
|----------|---------|-------|
| `server.port` | `8080` | App HTTP port |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | Actuator endpoints exposed |
| `spring.ai.azure.openai.api-key` | `dummy-key-for-local-dev` | **Replace with real key when wiring AI** |
| `spring.ai.azure.openai.endpoint` | `https://dummy.openai.azure.com/` | **Replace** |
| `spring.ai.azure.openai.chat.options.model` | `gpt-4o` | — |
| `spring.datasource.url` | `jdbc:h2:mem:swarmdb` | In-memory H2 |

**Do not commit real credentials.** Put them in `application-local.properties` (already in `.gitignore`) and run with:
```bash
java -jar target/*.jar --spring.profiles.active=local
```

---

## Troubleshooting

### Build fails with `cannot find symbol: log`

The codebase uses Lombok's `@Slf4j` to auto-generate the `log` field. If a file imports
`reactor.netty.http.HttpConnectionLiveness.log` statically, it shadows the Lombok field
and breaks compilation. Remove the offending import.

### App starts, but webhook returns 500 / NPE

`@RequiredArgsConstructor` only injects fields marked `final`. If `IncidentController.orchestrator`
or `SwarmOrchestrator.chatClient` are not `final`, Spring won't inject them and you'll get
a NullPointerException at the first webhook call. Mark them `final`.

### `docker run` fails with `Cannot connect to the Docker daemon`

Docker Desktop is not running. Open the app manually (`open -a Docker`) and wait for the
whale icon in the menu bar to stop animating.

### Prometheus says target is `DOWN`

- Confirm the Spring app is running on port 8080: `curl http://localhost:8080/actuator/prometheus`
- The Prometheus config uses `host.docker.internal` — this only works on Docker Desktop.
  On Linux Docker, replace with the host's IP or use `--network host`.

### Spring AI complains at startup

The dummy Azure config (`https://dummy.openai.azure.com/`) is tolerated at startup but will
fail when the LLM is actually called. The current `SwarmOrchestrator` stub does not call
the LLM, so this is expected. Replace credentials only when wiring the real agent loop.

### Port 8080 or 9090 already in use

```bash
lsof -iTCP:8080 -sTCP:LISTEN
lsof -iTCP:9090 -sTCP:LISTEN
```

Kill the offending process or change the port in `application.properties` /
`docker run -p NEW:9090`.

---

## Quick command reference

```bash
# Build
./mvnw clean package -DskipTests

# Run (foreground)
java -jar target/swarmsre-backend-0.0.1-SNAPSHOT.jar

# Run (background) and capture log
nohup java -jar target/swarmsre-backend-0.0.1-SNAPSHOT.jar > /tmp/swarmsre.log 2>&1 &

# Tail logs
tail -f /tmp/swarmsre.log

# Hit webhook
curl -X POST http://localhost:8080/api/incident/webhook \
  -H "Content-Type: application/json" \
  -d '{"incidentId":"INC-001","source":"prometheus","severity":"critical","service":"payment-service","summary":"High error rate"}'

# Run Prometheus + Alertmanager (full Level 3 pipeline)
docker run -d --name swarmsre-prometheus -p 9090:9090 \
  -v "$(pwd)/infra/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
  -v "$(pwd)/infra/rules.yml:/etc/prometheus/rules.yml:ro" \
  prom/prometheus

docker run -d --name swarmsre-alertmanager -p 9093:9093 \
  -v "$(pwd)/infra/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro" \
  prom/alertmanager

# Trip the alert
for i in $(seq 1 35); do
  curl -s -X POST "http://localhost:8080/api/simulate/error?count=5" > /dev/null
  sleep 2
done

# Stop everything
kill $(lsof -iTCP:8080 -sTCP:LISTEN -t)
docker stop swarmsre-prometheus swarmsre-alertmanager
```

---

## Where to look for more context

- `Swarm-Agents/Hackathon_Project/COMPONENTS.md` — full system architecture
- `Swarm-Agents/Hackathon_Project/TEAM_PLAN.md` — three-person execution plan and contracts
- `Swarm-Agents/Hackathon_Project/TEAM_QA.md` — design decisions and Q&A history
- `docs/examples/sample-startup-and-alert-simulation.log` — reference output of a healthy run including a fired alert

# SwarmSRE - Component Breakdown

## Overview

SwarmSRE is a **standalone, plug-and-play AI incident response service**. It connects to any existing application's observability stack (Prometheus, Grafana, ELK, CloudWatch, Datadog, etc.) and autonomously triages, investigates, and proposes fixes when incidents occur — with human approval before deployment.

**Key principle:** The target application requires zero code changes. SwarmSRE is a sidecar/standalone service that watches through whatever observability tools are already in place.

---

## System Flow (End to End)

1. Target application emits logs/metrics/alerts through its existing observability stack
2. SwarmSRE's **adapter layer** picks up incident signals (webhook, polling, or stream)
3. Event normalized and queued in Azure Event Hubs
4. AI Orchestrator initializes the agent swarm
5. Agents (Triage → Researcher → Fix-IT) collaborate via LLM + tool calls
6. Each agent step is broadcast to the frontend via WebSocket in real-time
7. Final proposed fix displayed to SRE team with diff view
8. Human approves → PR created in GitHub / config deployed

---

## Components

### 1. Adapter Layer (Plug-and-Play Ingestion)

The adapter layer is what makes SwarmSRE pluggable. Each adapter knows how to connect to a specific observability source and normalize events into a common internal format.

| Adapter | Source | Connection Method |
|---------|--------|-------------------|
| Prometheus/Grafana | Alert webhooks | Receives HTTP POST from Alertmanager/Grafana alert rules |
| ELK Stack | Elasticsearch | Polls or uses Elasticsearch Watcher to detect anomalies |
| AWS CloudWatch | CloudWatch Alarms | SNS → HTTP webhook or EventBridge subscription |
| Datadog | Monitor alerts | Webhook integration from Datadog monitors |
| Custom / Generic | Any system | Generic webhook endpoint accepting a standard JSON payload |

**All adapters produce a unified `IncidentEvent`:**
```
{
  "source": "prometheus",
  "severity": "critical",
  "service": "payment-service",
  "summary": "High error rate detected (5xx > 10%)",
  "timestamp": "...",
  "metadata": { ... raw alert payload ... }
}
```

---

### 2. Event Queue

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Event Hub | Azure Event Hubs (Kafka protocol) | Async queue between adapters and orchestrator; ensures no events are lost, enables replay |

---

### 3. Backend Orchestration (Spring Boot)

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Event Consumer | `@KafkaListener` (Spring Kafka) | Consume normalized incident events from Event Hubs |
| AgentService | Spring Component | Receives incident payload, manages agent lifecycle and handoffs |
| Spring AI Orchestrator | Spring AI (`ChatClient`) | Sends prompts + tool schemas to LLM, handles the tool-call loop |
| Java Tool Beans | `@Bean @Description` methods | Executable tools the LLM can invoke: `fetchLogs`, `queryMetrics`, `searchDocs` |
| WebSocket Controller | Spring WebSocket (STOMP/SockJS) | Broadcasts agent state changes and results to connected frontend clients |

**Key Flow:**
```
adapter receives alert
  → normalize to IncidentEvent
    → publish to Event Hub
      → consumer picks up event
        → AgentService.handleIncident(event)
          → chatClient.prompt().user("Analyze INC-001").call()
            → LLM invokes tools → gets data → drafts resolution
              → broadcast via WebSocket
                → trigger next agent
```

---

### 4. AI Agent Swarm

| Agent | Role | Tools Available | Triggers Next |
|-------|------|----------------|--------------|
| **Triage Agent** | Classifies severity, identifies affected service, determines investigation direction | fetchLogs, queryMetrics | Researcher |
| **Researcher** | Deep-dives into logs, searches GitHub history for related PRs, queries documentation | fetchLogs, searchDocs, queryGitHistory | Fix-IT Agent |
| **Fix-IT Agent** | Proposes code/config fix, generates a diff, drafts a PR | createPR, generateDiff | — (awaits human approval) |

**LLM Backend:** Azure AI Foundry (GPT-4o / DeepSeek endpoint)
**Agent SDK:** Azure AI Agents SDK (multi-agent coordination)
**RAG Support:** Azure AI Search for runbook/documentation retrieval
**Memory:** Azure Cosmos DB for persisting agent context across incidents

---

### 5. Data & Memory Layer

> **Update (post-Q6 / DB integration discussion):** The original three-store design (PostgreSQL + Cosmos + AI Search) has been consolidated. We are running a **single local NoSQL container** for hackathon scope, with a clean cloud upgrade path. PostgreSQL is dropped. Azure AI Search is replaced by a local vector store.

#### Current plan

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Mock Logs | NoSQL collection `mock_logs` | Stores fake log entries that the `fetchLogs` tool queries |
| Agent Memory | NoSQL collection `agent_memory` | Persists past incident context, agent decisions, learnings |
| Documentation Index | Local FAISS / in-memory vector store | Indexes runbooks for RAG retrieval — replaces Azure AI Search |

Both NoSQL collections live in the **same database container**, populated at startup by a `MockDataSeeder` reading JSON files from `mock-data/`.

#### Database container — open decision (Option 1 vs Option 2)

We are choosing between two locally hostable NoSQL options. Both run as Docker containers, both have a clean cloud upgrade path, both are accessed via Spring Boot tool implementations only (no agent code touches the DB directly).

##### Option 1 — Azure Cosmos DB Linux Emulator

Microsoft's official local Cosmos emulator, API-identical to real Cosmos DB.

```bash
docker run -p 8081:8081 -p 10250-10255:10250-10255 \
  -e AZURE_COSMOS_EMULATOR_PARTITION_COUNT=2 \
  -e AZURE_COSMOS_EMULATOR_IP_ADDRESS_OVERRIDE=127.0.0.1 \
  --name cosmos-emulator \
  mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:latest
```

Spring config:
```yaml
spring:
  cloud:
    azure:
      cosmos:
        endpoint: https://localhost:8081
        key: <emulator default key>
        database: swarmsre
```

**Pros**
- API-identical to production Cosmos — code is fully portable to Azure if access lands
- Same `azure-cosmos` SDK works locally and in cloud
- Containers, partition keys, queries behave the same way

**Cons**
- ~2 GB RAM, ~30 second startup
- Self-signed cert hassle on first connection
- Heavier than alternatives

##### Option 2 — MongoDB

A standard MongoDB container. Cosmos DB also exposes a MongoDB-compatible API in production, so the same driver works locally and in cloud.

```bash
docker run -d -p 27017:27017 --name mongo \
  -e MONGO_INITDB_ROOT_USERNAME=admin \
  -e MONGO_INITDB_ROOT_PASSWORD=admin \
  mongo:7
```

Spring config:
```yaml
spring:
  data:
    mongodb:
      uri: mongodb://admin:admin@localhost:27017/swarmsre?authSource=admin
```

Repository layer:
```java
@Document(collection = "mock_logs")
public record LogEntry(
    @Id String id,
    String service,
    Instant timestamp,
    String level,
    String message
) {}

public interface LogRepository extends MongoRepository<LogEntry, String> {
    List<LogEntry> findByServiceOrderByTimestampDesc(String service);
}
```

**Pros**
- Lightweight (~200 MB RAM), ~5 second startup
- Spring Data MongoDB is mature — repositories give us the data layer for free
- Cloud upgrade path: Cosmos DB with MongoDB API uses the same driver
- Mongo Express web UI for visual debugging during the demo
- Simpler mental model and more familiar to most teams

**Cons**
- If we later commit to Cosmos's *native* SQL API, code is not portable (Cosmos's MongoDB API is fully supported, so this is only an issue if we deliberately switch APIs)

##### Decision matrix

| Criterion | Option 1: Cosmos Emulator | Option 2: MongoDB |
|-----------|---------------------------|-------------------|
| Startup time | ~30s | ~5s |
| RAM footprint | ~2 GB | ~200 MB |
| Setup friction | Cert handling, env vars | One docker run, done |
| Spring integration | `azure-cosmos` SDK | Spring Data MongoDB |
| Cloud upgrade path | Cosmos native (drop in) | Cosmos with MongoDB API (driver-compatible) |
| Demo UX (data browsing) | Cosmos Data Explorer (heavier) | Mongo Express (lightweight) |
| Hackathon-friendly | Acceptable | Stronger fit |

##### Fallback (if either container path is blocked)

If neither container can run reliably on a teammate's machine, both options degrade to:
- `Map<String, List<LogEntry>>` for mock logs
- `Map<String, IncidentContext>` for agent memory
- Loaded by the same `MockDataSeeder` from the same JSON files

Demo behavior is identical — only persistence between restarts is lost, which is irrelevant for a 3-minute demo.

#### Vector store (RAG)

Independent of the Option 1 / Option 2 decision:
- Local FAISS or in-memory store
- Loaded at startup from Markdown runbooks in `mock-data/runbooks/`
- Embeddings computed once on startup; queries are sub-millisecond
- No managed service required

#### How the database plugs in (architecture)

The DB is **only touched through tools**, never directly by agents.

```
Orchestrator (LLM)
   │ tool call: fetchLogs(service, timeRange)
   ↓
[Tool Layer (Spring beans)]
   │
   ├─► mock_logs collection      (Cosmos emulator OR MongoDB)
   ├─► agent_memory collection   (Cosmos emulator OR MongoDB)
   ├─► local FAISS vector store
   └─► GitHub REST API
```

This means swapping between Option 1 and Option 2 — or to the in-memory fallback — is a one-class change. Agent code, prompts, and orchestration logic are unaffected.

---

### 6. Frontend (Control Plane)

| Component | Technology | Purpose |
|-----------|-----------|---------|
| SRE Dashboard | Next.js / React | Main control plane — single page app |
| System Health Panel | React component | Displays target app's CPU, MEM, disk metrics in real-time |
| Live Logs Panel | React + WebSocket | Streaming log output from the incident |
| Agent Swarm Graph | React (node visualization) | Visual representation of agents: Incident Center → Triage → Researcher → Fix-IT |
| Proposed Fix Panel | React (diff view) | Shows the proposed code/config change as a diff |
| Approve & Deploy | React + API call | Human-in-the-loop button — triggers PR creation or config deployment |
| Status Bar | React + WebSocket | Real-time agent activity messages at the bottom |
| Adapter Config UI | React | Configure which observability sources to connect (add Prometheus endpoint, Datadog API key, etc.) |

---

### 7. External Integrations

| System | Integration Point | Purpose |
|--------|-------------------|---------|
| GitHub | REST API | Target for auto-generated PRs and fixes |
| Target App's Observability | Adapters (see section 1) | Source of incident signals |

---

## Tech Stack Summary

| Layer | Technologies |
|-------|-------------|
| Adapters | HTTP webhook receivers, REST clients, SDK integrations |
| Backend | Java 17+, Spring Boot 3.x, Spring AI, Spring Kafka, Spring WebSocket |
| AI | Azure AI Foundry (GPT-4o / DeepSeek), Azure AI Agents SDK, Azure AI Search |
| Data | Azure PostgreSQL, Azure Cosmos DB |
| Messaging | Azure Event Hubs (Kafka protocol) |
| Frontend | Next.js, React, WebSocket (STOMP/SockJS) |
| Integrations | GitHub API |
| Infrastructure | Azure Cloud |

---

## Architecture Decision: How to Split the Multi-Agent Swarm

**This needs a team decision.** We're keeping multi-agent (it's the core identity of SwarmSRE), but *how* we split the agents matters a lot. There are three approaches:

---

### Option A: Split by Phase (current design)

```
Triage Agent → Researcher → Fix-IT Agent
```

Each agent handles one stage of incident response, sequential handoff.

**Pros:**
- Matches the original architecture diagrams
- Clean narrative: classify → investigate → fix
- Easy to visualize as a pipeline in the UI

**Concerns:**
- **Overlap:** Triage needs to look at logs to classify → Researcher also looks at logs. Duplicated work.
- **Lossy handoffs:** Each handoff serializes context, nuance gets lost between agents.
- **Backtracking:** If Fix-IT needs more info, can it go back? That requires complex routing logic.
- **Real incident response is iterative, not a waterfall.** We're forcing a linear pipeline on a non-linear process.

---

### Option B: Split by Specialization (alternative)

```
          ┌→ Log Analyst Agent
Orchestrator → Metrics Agent      → Orchestrator synthesizes → proposes fix
          └→ Code Agent
```

Each agent owns a **data domain**, not a phase. An Orchestrator decides who to call and when.

| Agent | Specialty | Distinct because |
|-------|-----------|-----------------|
| **Orchestrator** | Coordinates, synthesizes, decides next step | The "brain" — no tool access itself |
| **Log Analyst** | Reads logs, stack traces, error patterns | Owns log data sources |
| **Metrics Agent** | CPU, memory, latency, dashboard data | Owns metric data sources |
| **Code Agent** | Searches codebase, reads source, generates fix/PR | Owns repo access |

**Pros:**
- No overlap — each agent owns a distinct data domain
- Backtracking is natural — orchestrator just calls the same agent again
- **Parallel execution** — Log Analyst and Metrics Agent can run simultaneously
- UI graph is more dynamic (nodes lighting up in parallel, not just left-to-right)
- Still genuinely multi-agent, still a "swarm"

**Concerns:**
- More complex coordination logic in the orchestrator
- Orchestrator prompt becomes critical — it's making all routing decisions
- More agents = more LLM calls = higher cost/latency per incident

---

### Option C: Single Agent, Multi-Agent UI

One agent with all tools, prompted to work in phases. The frontend *visualizes* it as distinct stages based on which tools it's calling — so the UI still shows a swarm.

**Pros:**
- Simplest to build and debug
- No handoff overhead or context loss
- Agent naturally iterates

**Cons:**
- Not a real multi-agent system (matters for judging? technical credibility?)
- Single prompt may get complex for long incidents
- Doesn't leverage Azure AI Agents SDK multi-agent features

---

## Missing Pieces (Regardless of Agent Architecture)

These are gaps in the current design that we need to discuss:

- [ ] **Escalation path** — What happens when the agent can't figure it out? Need a "I'm stuck, paging a human" mechanism.
- [ ] **Confidence scoring** — A shaky diagnosis shouldn't look the same as a confident one in the UI. Do we show a confidence %?
- [ ] **Incident deduplication** — Two alerts from the same root cause shouldn't spawn two swarms. How do we detect duplicates?
- [ ] **Rollback** — If the approved fix makes things worse, then what? Auto-revert? Alert?

---

## Open Questions / Decisions Needed

**Agent architecture:**
- [ ] Multi-agent pipeline vs single agent with phases vs hybrid? (see discussion above)
- [ ] If multi-agent: sequential only, or can agents run in parallel?
- [ ] Which LLM model for each agent/phase? (GPT-4o for all, or DeepSeek for cheaper/faster tasks?)

**System design:**
- [ ] How does "Approve & Deploy" execute? (GitHub API PR merge? Direct config push? Terraform apply?)
- [ ] Agent memory scope: per-incident only, or global learning across all incidents?
- [ ] How does SwarmSRE access the target app's logs? (via observability APIs only, or can it also SSH/kubectl?)
- [ ] How are adapter credentials managed? (Vault, env vars, UI config?)

**Hackathon scope:**
- [ ] Which adapter(s) to implement first for the demo?
- [ ] Do we need a real target app to demo against, or fully mock it?
- [ ] What's the "wow moment" in the demo? (The agent graph? The auto-fix? The speed?)

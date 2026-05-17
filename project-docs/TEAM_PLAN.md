# SwarmSRE — Team Execution Plan

**Audience:** Three engineers working in parallel on a hackathon timeline.
**Owner:** Team lead.
**Goal:** Ship a working, demoable multi-agent SRE assistant without anyone blocking anyone else.

This document is the single source of truth for who builds what, in what order, and against which contracts. Read it once before starting; refer back to it whenever you're unsure who owns something.

---

## 1. Project at a glance

**SwarmSRE** is a plug-and-play multi-agent incident response service. An external observability tool fires an alert; SwarmSRE receives it, an orchestrator agent coordinates specialist sub-agents (logs, metrics, code) to investigate, and a Fix-IT agent proposes a remediation. A human approves, and a GitHub PR is created.

**The thing we are demonstrating** is the AI behavior — orchestration, tool use, multi-agent coordination, human-in-loop. Plumbing is intentionally minimal.

**Stack (locked):**

| Layer | Choice |
|-------|--------|
| Backend | Java 17 + Spring Boot 3.x |
| LLM | Anthropic Claude (via AWS Bedrock today; transport-swappable) |
| LLM SDK | Anthropic Java SDK with Bedrock support |
| Frontend | Next.js + React + Tailwind + React Flow |
| Streaming | Server-Sent Events (SSE) — no STOMP/SockJS |
| Storage | Azure Cosmos DB (single account, two containers) — fallback to in-memory map if Azure access lags |
| Vector store | Local FAISS or in-memory for RAG |
| External integrations | GitHub REST API for PR creation |
| Demo trigger | curl + a generic `/webhook/incident` endpoint |

**Stack we deliberately cut:**
Kafka / Event Hubs (direct dispatch is enough), PostgreSQL (Cosmos covers logs + memory), STOMP/SockJS (SSE is simpler), Terraform / Kubernetes (run locally), real Prometheus/Datadog as required (curl mock is enough; real Prometheus is a stretch goal).

---

## 2. Guiding principles

These are non-negotiable. They are why the team can run in parallel.

1. **Contracts first, code second.** Day 0 ends with all interface contracts written down in this document and the linked `CONTRACTS.md`. After that, no contract changes without team agreement.
2. **Each person owns end-to-end of their slice.** Ownership is by feature/concern, not by language. Person 1 writes whatever code is needed for the AI core, including any backend glue specific to it. Same for the others.
3. **Mock at the boundary.** The frontend does not wait for the backend. The backend does not wait for the LLM. The AI does not wait for real data. Each person ships against mocks, then swaps to real on integration days.
4. **Demo is the spec.** Every feature is justified by whether it improves the demo. If it doesn't, defer it.
5. **No surprises.** Changing a contract, missing a deadline, or hitting a blocker — surface it within 2 hours, not at end-of-day standup.
6. **Two short standups daily.** Morning (10 min, what you'll do today + blockers) and evening (10 min, what landed + what's blocking tomorrow). Nothing longer.

---

## 3. Roles and ownership

People are labelled P1, P2, P3 for clarity. Match team members to roles based on strengths.

### P1 — AI Core

**Strongest LLM / prompt engineering skill.** This is the most leveraged seat — the project lives or dies on what P1 builds.

**Owns end-to-end:**
- Anthropic Java SDK integration with Bedrock backend
- Orchestrator agent (prompt + decision logic)
- Specialist sub-agents: Log Analyst, Metrics, Code/Fix-IT
- Tool definitions (interfaces, prompt-side wiring, schemas)
- Confidence scoring (LLM self-rating + Java post-check rules)
- Escalation logic ("needs human" decision)
- RAG over fake runbooks (vector store + retrieval)
- Prompt iteration and regression testing against fake incidents

**Does not own:** tool *implementations* (P2 owns), UI rendering of agent state (P3 owns), webhook handling (P2 owns).

**Consumes from others:**
- P2's tool implementations (initially stubbed, real by Day 2)
- P2's `IncidentEvent` payload as input to the orchestrator

**Produces for others:**
- Tool interface signatures (P2 implements)
- Agent step event payload format (P2 emits, P3 renders)
- Final fix event format (P2 emits, P3 renders)
- Escalation event format

---

### P2 — Backend & Plumbing

**Strongest Java / Spring Boot skill.**

**Owns end-to-end:**
- Spring Boot project scaffold and local dev setup
- Generic webhook adapter (`POST /webhook/incident`)
- Optional Prometheus adapter (stretch — local Alertmanager → our endpoint)
- `IncidentEvent` model, validation, and DTOs
- Direct dispatch from webhook → orchestrator (no Kafka)
- SSE controller broadcasting agent step events
- Tool implementations: `fetchLogs`, `queryMetrics`, `searchDocs`, `searchGitHistory`, `createPR`
- Cosmos DB connection and schema (`mock_logs`, `agent_memory` containers)
- GitHub PR creation via REST API
- Mock data seeding scripts (logs, runbooks, past incidents)
- Local Docker Compose for any infra that needs it

**Does not own:** prompts (P1), UI (P3).

**Consumes from others:**
- P1's orchestrator interface (initially stubbed; real by Day 2)
- P1's tool interface signatures
- P3's expected webhook event payload (so SSE format is what frontend expects)

**Produces for others:**
- `IncidentEvent` schema (everyone)
- SSE endpoint and event stream format (P3)
- Tool implementations (P1 plugs in)
- Webhook contract (used by everyone for triggering demos)

---

### P3 — Frontend (Control Plane)

**Strongest React / UX skill.**

**Owns end-to-end:**
- Next.js + Tailwind project scaffold
- Layout matching `Image 4.png` (header, system health panel, live logs, agent graph, proposed fix, status bar)
- Agent graph component (React Flow): nodes for orchestrator + sub-agents, animated state transitions
- Live logs panel streaming from SSE
- Proposed fix diff renderer
- Confidence badge (green / yellow / red)
- "Approve & Deploy" button → calls backend → shows PR link
- "Needs Human" escalation banner
- SSE client that consumes events and updates UI state
- Local mock event generator so frontend works without backend
- Adapter config UI (stretch)

**Does not own:** anything backend or AI-side.

**Consumes from others:**
- P2's SSE event stream (mocked locally on Day 1, real on Day 2+)
- P1/P2's event payload schemas

**Produces for others:**
- Almost nothing — frontend is the leaf of the dependency tree, which is why P3 can run fully parallel from hour 1.

---

## 4. Contracts (locked Day 0)

Everything below is the contract. Anyone changing these must announce it in the team channel and get a thumbs-up from the other two before merging.

### 4.1 `IncidentEvent` (input to the system)

```json
{
  "incidentId": "INC-882",
  "source": "prometheus",
  "severity": "critical",
  "service": "payment-service",
  "summary": "CPU > 90% for 5 minutes",
  "timestamp": "2026-05-16T10:00:00Z",
  "rawPayload": { }
}
```

| Field | Type | Notes |
|-------|------|-------|
| `incidentId` | string | Generated server-side if missing |
| `source` | enum | `prometheus`, `datadog`, `cloudwatch`, `generic` |
| `severity` | enum | `critical`, `high`, `medium`, `low` |
| `service` | string | Affected service name |
| `summary` | string | Human-readable description |
| `timestamp` | ISO 8601 string | When the alert fired |
| `rawPayload` | object | Original alert kept for reference; agents may inspect |

### 4.2 Agent step event (SSE → frontend)

```json
{
  "type": "AGENT_STEP",
  "incidentId": "INC-882",
  "agent": "log-analyst",
  "status": "working",
  "message": "Analyzing payment-service logs...",
  "tool": "fetchLogs",
  "timestamp": "2026-05-16T10:00:05Z"
}
```

| Field | Values |
|-------|--------|
| `type` | `AGENT_STEP`, `PROPOSED_FIX`, `NEEDS_HUMAN`, `COMPLETE` |
| `agent` | `orchestrator`, `log-analyst`, `metrics`, `fix-it` |
| `status` | `working`, `complete`, `errored` |
| `tool` | optional — name of tool currently invoked |

### 4.3 Proposed fix event

```json
{
  "type": "PROPOSED_FIX",
  "incidentId": "INC-882",
  "diff": "- thread_pool: 20\n+ thread_pool: 100",
  "rootCause": "Thread pool exhaustion under load.",
  "reasoning": "Stack trace shows...",
  "confidence": 85,
  "needsHuman": false
}
```

### 4.4 Tool interface (Java)

```java
public interface AgentTool {
    String name();
    String description();
    JsonNode schema();           // JSON schema for inputs
    JsonNode invoke(JsonNode args);
}
```

Each tool defines its own input/output JSON shape. Both P1 and P2 must agree on each tool's schema before P2 implements it.

### 4.5 Approve endpoint (frontend → backend)

```
POST /api/incidents/{incidentId}/approve
Response: { "prUrl": "https://github.com/.../pull/42" }
```

### 4.6 Webhook (external → backend)

```
POST /webhook/incident
Body: IncidentEvent
Response: { "incidentId": "INC-882", "status": "received" }
```

---

## 5. Repository layout

Single monorepo. One branch per person; PRs into `main` reviewed by at least one other.

```
swarmsre/
├── backend/                 # Spring Boot — P2 primary, P1 secondary
│   ├── src/main/java/...
│   │   ├── adapter/        # webhook receivers
│   │   ├── agent/          # orchestrator + sub-agents — P1
│   │   ├── tool/           # tool implementations — P2
│   │   ├── model/          # IncidentEvent, events
│   │   ├── sse/            # SSE controller
│   │   └── github/         # PR creation
│   └── src/main/resources/
│       └── prompts/        # Markdown prompt files — P1
├── frontend/                # Next.js — P3
│   ├── app/
│   ├── components/
│   ├── lib/
│   │   ├── sse-client.ts
│   │   └── mock-events.ts  # Day 1 local mock
│   └── public/
├── mock-data/              # P2 owns, anyone contributes
│   ├── logs/
│   ├── runbooks/
│   ├── past-incidents/
│   └── sample-incidents/   # for curl-based demo triggers
├── CONTRACTS.md            # extracted from §4 of this doc; do not drift
├── TEAM_PLAN.md            # this file
├── COMPONENTS.md           # original architecture
├── TEAM_QA.md              # decisions log
└── README.md               # how to run everything locally
```

---

## 6. Day-by-day plan

Hackathon assumed to be ~4 working days plus a buffer. Adjust dates to your timeline.

### Day 0 — Alignment (whole team, ~2 hours)

A single working session. Outcomes:

- [ ] All three roles confirmed
- [ ] Repo created, monorepo structure in place
- [ ] `CONTRACTS.md` extracted from this doc and committed
- [ ] AWS credentials with Bedrock access shared with P1
- [ ] Cosmos DB credentials with P2 (or fallback to in-memory plan agreed)
- [ ] GitHub PAT and target repo created (P2 has access)
- [ ] Local dev tools verified for everyone (JDK 17, Node 20, Maven)
- [ ] Mock data shape sketched (P2 leads, others contribute fields they need)
- [ ] Demo storyline drafted: "what does the judge see in 3 minutes?"

**Deliverable:** every person can clone the repo and have a clear, written task list for Day 1.

---

### Day 1 — Independent build with mocks

Goal: each person has something that runs and demos in isolation.

**P1**
- [ ] Bedrock + Anthropic SDK working: send a "hello" message to Claude, log response
- [ ] First tool definition and tool-calling loop (single tool: `fetchLogs`, returning canned JSON)
- [ ] Orchestrator prompt v1 — given an incident, decide what to investigate
- [ ] One sub-agent (Log Analyst) wired with the stub tool
- [ ] Output a structured "proposed fix" JSON for one fake incident

**P2**
- [ ] Spring Boot scaffold compiles, runs, has `/health`
- [ ] `IncidentEvent` model + validation
- [ ] `POST /webhook/incident` accepts the contract payload
- [ ] SSE controller emits events on a fixed schedule (hardcoded sequence: `AGENT_STEP` x3 → `PROPOSED_FIX`)
- [ ] Mock data files committed: 3 fake incidents, ~50 log entries, 3 runbooks
- [ ] Stub tool implementations returning canned responses

**P3**
- [ ] Next.js scaffold runs, layout matches `Image 4.png` mockup
- [ ] React Flow agent graph with placeholder nodes and animation states
- [ ] Live logs panel reading from a local mock event source
- [ ] Proposed fix diff panel renders a hardcoded diff
- [ ] Approve button (calls a stub endpoint, shows toast)

**End of Day 1 demo:** each person screen-shares their slice running locally. No integrations yet — that's by design.

---

### Day 2 — First integrations (the riskiest day)

Goal: end-to-end pipeline executes one incident with real LLM, one real tool, real SSE.

**Morning (4 hours)**
- [ ] **P1 ↔ P2:** P2's webhook calls P1's orchestrator (real interface, not stubbed)
- [ ] **P1 ↔ P2:** P2's `fetchLogs` becomes real (queries Cosmos / reads mock files); P1 uses it
- [ ] **P3 ↔ P2:** Frontend connects to real SSE, removes local mock event generator
- [ ] **P2:** SSE controller receives events from orchestrator (P1) and broadcasts them

**Afternoon (4 hours)**
- [ ] First end-to-end run: `curl /webhook/incident` → orchestrator → tool → SSE → frontend updates
- [ ] Debug the inevitable mismatches (event field naming, async ordering)
- [ ] **P1:** Tighten orchestrator prompt based on what shows up in the live UI
- [ ] **P2:** Wire `createPR` against the demo target repo
- [ ] **P3:** Approve button calls real `/approve` endpoint, shows real PR URL

**End of Day 2 demo:** one incident flows end-to-end. The flow is rough but real.

---

### Day 3 — Full agent flow + polish

Goal: multi-agent flow with all sub-agents, RAG, confidence, escalation.

**P1**
- [ ] Add Metrics sub-agent (real prompt, wired to `queryMetrics` tool)
- [ ] Add Fix-IT sub-agent (generates diff)
- [ ] Refine orchestrator routing — when does it call each sub-agent?
- [ ] RAG: index runbooks, retrieve relevant ones into context
- [ ] Confidence scoring (LLM self-rating + post-check rules)
- [ ] Escalation logic: emit `NEEDS_HUMAN` event when stuck

**P2**
- [ ] All tools production-ready (real Cosmos queries, real GitHub call)
- [ ] Mock data expanded: more incidents, richer logs
- [ ] Error handling + timeouts (LLM hangs, tool errors)
- [ ] Logging for demo debugging

**P3**
- [ ] Multi-node agent graph reflecting real orchestration (orchestrator + 3 specialists)
- [ ] Confidence badge on proposed fix panel
- [ ] "Needs Human" banner with partial findings
- [ ] PR link rendering and copy-to-clipboard
- [ ] Polish: animations, colors, typography matching Image 4

**End of Day 3 demo:** the full storyline runs cleanly. We could ship it as-is.

---

### Day 4 — Polish, rehearse, stretch

Whole team works together. Priorities in order:

1. **Demo script rehearsal** — actually run the demo 3 times, time it, fix what breaks
2. **Prompt tuning** — fix the 2-3 places where the agent is dumb
3. **UI polish** — anything that looks half-finished
4. **Buffer for surprises**

Stretch goals (only if everything else is solid):
- [ ] Real Prometheus adapter — local Alertmanager firing into our webhook
- [ ] Parallel sub-agent execution (Log + Metrics in parallel)
- [ ] Second incident scenario (different root cause type)
- [ ] Past-incident memory referenced by the orchestrator ("we saw this last week")

---

## 7. Communication

### Channels

- **Team chat:** real-time questions, contract-change proposals, blocker escalation.
- **Standups (sync):** 10 min morning, 10 min evening. No exceptions, no extensions.
- **Repo:** PRs and issues for anything written down.
- **Demo doc (this file + TEAM_QA.md):** decision log; updated when decisions change.

### When to escalate

Escalate to team lead within 2 hours when:
- A contract needs to change
- A dependency is broken (Bedrock access lost, Cosmos down, GitHub API rate-limited)
- You realize your slice will miss its day's deadline
- Two people disagree on something blocking work

Do **not** escalate for: a tricky bug you're working through, a small UI tweak, a question that can wait until standup.

### What "done" means

A task is done when:
1. It runs locally on its owner's laptop
2. The contract it touches is unchanged or the change is in the repo
3. Other people who depend on it have been told it's ready
4. It's on `main` (or merged to a tracked branch)

---

## 8. Risk register

Known risks with mitigation. Re-check at end of Day 1 and Day 2 standups.

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Bedrock access denied or rate-limited | Low | High | Have a personal Anthropic API key as fallback; transport-swap is one-line config |
| Cosmos DB access not granted in time | Medium | Medium | In-memory map fallback already designed (Q6 decision); seed from JSON files |
| Orchestrator prompt loops or fails to terminate | High | High | Hard cap on tool calls (e.g., 8); escalate to human if cap hit |
| GitHub API auth issues during demo | Medium | Medium | Pre-authenticate and verify on Day 3; fallback is to display the diff and PR body without creating it live |
| Frontend / backend event schema drift | Medium | High | Contracts in §4 are the law; SSE event types are validated server-side |
| LLM is slow (10s+ per step) and demo drags | Medium | Medium | Use Haiku for sub-agents; pre-stage one incident's results in cache for the demo |
| Person blocked waiting on someone else | Medium | High | Mocks at every boundary (§2 principle 3); standup catches blockers within 12 hours |
| Demo machine fails / wifi flaky | Low | Critical | Run everything locally; record a backup video on Day 4 morning |

---

## 9. Demo storyline (the real spec)

The demo is what we're optimizing for. Everyone should know it cold.

**Setup (10 sec):** "SwarmSRE watches your existing observability stack and triages incidents using a multi-agent system. Today we'll show you a real incident."

**Trigger (10 sec):** Run a curl command (or click a button in adapter config UI). An incident appears in the dashboard.

**Investigation (60 sec):** The agent graph lights up — orchestrator, then log analyst (fetching logs), then metrics agent (checking CPU/MEM). Live logs panel streams agent reasoning. Confidence badge starts low, climbs as evidence accumulates.

**Proposed fix (30 sec):** Fix-IT agent produces a diff. Confidence badge: 85% green. Reasoning summary visible.

**Human-in-loop (20 sec):** SRE clicks "Approve & Deploy". A GitHub PR is created. Click the link → real PR opens in a new tab.

**Wrap (10 sec):** "All of this took 90 seconds. Without SwarmSRE this is 30 minutes of human work."

**Total:** ~2 minutes, leaving time for Q&A.

The "wow moment" is the agent graph animating with real reasoning text streaming, ending in a clickable real GitHub PR. Everything else exists to support that moment.

---

## 10. Definition of done for the project

We ship when all of the following are true:

- [ ] curl → webhook → orchestrator → sub-agents → SSE → UI → approve → real PR
- [ ] Confidence badge shows real values from the model
- [ ] At least one path that triggers `NEEDS_HUMAN` works
- [ ] At least 2 distinct incident scenarios work end-to-end
- [ ] Demo script runs cleanly 3 times in a row
- [ ] Backup video recorded
- [ ] Slide / pitch text drafted
- [ ] README explains how to run locally in under 5 minutes

If any of these are red on Day 4 morning, cut scope.

---

## 11. Quick links

- Architecture overview: `COMPONENTS.md`
- Decisions and Q&A: `TEAM_QA.md`
- Contracts (extract of §4): `CONTRACTS.md`
- Mock data: `mock-data/`
- Demo target repo: TBD (P2 to create on Day 0)

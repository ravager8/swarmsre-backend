# swarmsre

**Multi-agent AI incident response, demoable end-to-end.**

This is a monorepo holding three projects plus shared documentation. They run together to demonstrate SwarmSRE — an autonomous SRE assistant that triages production incidents, investigates root causes, and proposes a remediation as a GitHub PR with a human in the loop.

> The repository is currently named `swarmsre-backend` for historical reasons. The plan is to rename it to `swarmsre` once we're past the hackathon — see `project-docs/` for the architecture context.

---

## Repository layout

```
swarmsre-backend/
├── target-app/         Spring Boot fake "production app". Generates metrics,
│                       exposes Prometheus endpoint, simulates errors. The thing
│                       SwarmSRE watches.  ← Owned by P2.
│
├── orchestrator/       The real SwarmSRE — multi-agent AI service that receives
│                       incidents, runs the agent swarm, calls tools, streams
│                       progress to the dashboard.  ← Owned by P1. (Empty today.)
│
├── frontend/           Next.js dashboard. Visualizes the agent swarm, live logs,
│                       proposed fixes, and the human-in-the-loop approval flow.
│                       ← Owned by P3. (Empty today.)
│
└── project-docs/       Shared team documentation: architecture, decisions,
                        execution plan, original mockups.
```

Each subproject has (or will have) its own README with build/run instructions specific to that project.

---

## Where to start

| You are... | Read first |
|-----------|------------|
| New to the project | `project-docs/COMPONENTS.md` (architecture) |
| Joining the team | `project-docs/TEAM_PLAN.md` (3-person execution plan + locked contracts) |
| Wondering why a decision was made | `project-docs/TEAM_QA.md` (decision log) |
| Running the target app locally | `target-app/README.md` |
| Building the AI orchestrator | `orchestrator/` (TODO) |
| Building the frontend | `frontend/` (TODO) |

---

## Current implementation status

### `target-app/` — ✅ runnable end-to-end

| Capability | Status |
|------------|--------|
| `POST /api/incident/webhook` accepting canonical `IncidentEvent` | ✅ |
| `POST /api/simulate/error` driving a custom counter | ✅ |
| `/actuator/prometheus` endpoint with JVM, HTTP, disk, custom metrics | ✅ |
| `infra/prometheus.yml` — scrape config + rule file refs | ✅ |
| `infra/rules.yml` — `HighPaymentErrorRate` alert (rate > 0.5/s for 30s) | ✅ |
| `infra/alertmanager.yml` — webhook receiver routing | ✅ |

### `orchestrator/` — ✅ adapter layer complete, agent swarm pending

| Capability | Status |
|------------|--------|
| Spring Boot scaffold on port 8081, health endpoint | ✅ |
| Canonical `IncidentEvent`, `Severity`, `Source` types (per `project-docs/TEAM_PLAN.md` §4.1) | ✅ |
| Modular adapter package layout (`adapter/inbound/`, `adapter/outbound/`) | ✅ |
| **Inbound** — `POST /webhook/incident` (generic) | ✅ |
| **Inbound** — `POST /webhook/prometheus` (Alertmanager v4 payload) with normalizer | ✅ |
| **Outbound** — `MetricsClient` interface + `PrometheusMetricsClient` impl (`/api/v1/query`) | ✅ |
| `IncidentPipeline.dispatch()` single entry point | 🟡 stub — logs the incident, no agent loop yet |
| Agent swarm (LLM, tool calls, sub-agents) | ⚪ not started |
| SSE controller for streaming agent steps to frontend | ⚪ not started |
| GitHub PR creation tool | ⚪ not started |
| Memory/RAG store for past incidents | ⚪ not started |

### `frontend/` — ⚪ not yet started

Placeholder directory. Owned by P3.

---

## What's been tested end-to-end

On 2026-05-17, the full chain `target-app → Prometheus → Alertmanager → orchestrator` was verified locally:

| Layer | Verified |
|-------|----------|
| target-app counter `payment_errors_total` increments via `POST /api/simulate/error` | ✅ |
| Prometheus scrapes target-app every 5s and computes `rate(payment_errors_total[1m])` | ✅ (observed 2.09/s under load) |
| Prometheus alert rule `HighPaymentErrorRate` transitions `inactive → pending → firing` | ✅ |
| Alertmanager picks up the firing alert and dispatches the configured webhook | ✅ |
| Orchestrator receives `POST /webhook/prometheus`, parses Alertmanager v4 payload | ✅ |
| Orchestrator's `PrometheusAlertNormalizer` converts to canonical `IncidentEvent` (severity from label, service from label, summary from annotation, ID from fingerprint) | ✅ |
| Orchestrator's `IncidentPipeline.dispatch()` is invoked with the canonical event | ✅ |
| Resolved-only Alertmanager payloads are acked but NOT dispatched to the pipeline | ✅ |
| Orchestrator's outbound `PrometheusMetricsClient` URL shape returns valid query results | ✅ |

What's still **untested** because not yet implemented:
- Agent reasoning loop (no LLM call yet)
- Tool execution from inside the pipeline
- SSE streaming to a frontend
- GitHub PR creation

---

## What's still to build

In rough order of priority:

1. **Agent swarm inside `IncidentPipeline.dispatch()`** — the orchestrator agent + Log Analyst + Metrics Agent + Fix-IT, with prompts and tool calls. This is the project's core value.
2. **SSE controller** at `GET /events/{incidentId}` — streams `AGENT_STEP` / `PROPOSED_FIX` / `NEEDS_HUMAN` events. Contract in `project-docs/TEAM_PLAN.md` §4.
3. **`LogsClient` outbound interface** + a target-app log endpoint to back it. Mirrors how `MetricsClient` works today.
4. **GitHub PR creation tool** — final step of the Fix-IT agent, returns a PR URL.
5. **Memory store** for past incidents (Cosmos emulator OR MongoDB — see `project-docs/COMPONENTS.md` §5 open decision).
6. **Frontend** — `npx create-next-app frontend`, dashboard matching `project-docs/images/Image 4.png`, connects to the orchestrator's SSE stream.
7. **Confidence scoring** — LLM self-rating + Java post-check, surfaced as a badge on the proposed fix (per `project-docs/TEAM_QA.md` Q4).

---

## Running everything together

### What works today (verified)

```
1. Start target-app                       (port 8080)
2. Start orchestrator                     (port 8081)
3. Start Prometheus container             (port 9090, scrapes target-app, loads rules.yml)
4. Start Alertmanager container           (port 9093, points webhook at orchestrator)
5. Drive the alert:                       curl /api/simulate/error?count=5  ×35 over 70s
6. Alert fires → Alertmanager webhook → orchestrator pipeline logs the incident
```

Concrete commands are in `target-app/README.md` (Levels 1–3) and `orchestrator/README.md`.

The placeholder URL in `target-app/infra/alertmanager.yml` must be replaced for step 4 — either with the orchestrator's webhook (`http://host.docker.internal:8081/webhook/prometheus`) or a `webhook.site` URL for visual inspection.

### What will work once agents are added

```
7. Orchestrator runs the agent swarm, streams progress to frontend over SSE
8. Frontend shows proposed fix; SRE clicks "Approve" → GitHub PR created
```

---

## Conventions

- **One branch policy:** all work merges into `main` via short-lived feature branches (`feature/<short-description>`). No long-lived parallel branches.
- **Each subproject has its own README, dependencies, build, and tests.** They share only the contracts in `project-docs/TEAM_PLAN.md` (§4).
- **Cross-project changes** (e.g., changing `IncidentEvent` schema) ship in a single PR that updates every affected subproject simultaneously.
- **Secrets never committed.** Use `application-local.properties` / `.env` files, both gitignored.

---

## Repository state notes

- This was originally a single Spring Boot project at the root. On 2026-05-17 it was reorganized into a monorepo by moving everything into `target-app/` and adding sibling directories for the orchestrator and frontend. Git history is preserved; `git log --follow target-app/<file>` will show pre-move history.
- The repo will eventually be renamed `swarmsre`. GitHub auto-redirects old URLs after a rename, so this is a low-risk change deferred to post-hackathon.

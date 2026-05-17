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

| Component | Status |
|-----------|--------|
| `target-app/` | ✅ Webhook endpoint, Prometheus metrics endpoint, custom error counter, alert rules, full Prometheus + Alertmanager pipeline |
| `orchestrator/` | ⚪ Not yet started — placeholder directory |
| `frontend/` | ⚪ Not yet started — placeholder directory |

`target-app/` is genuinely runnable today. See its README for full instructions including a sample log of a healthy run.

---

## Running everything together (planned)

When all three projects exist, the demo flow will be:

```
1. Start target-app                  (port 8080 — fake production service)
2. Start Prometheus + Alertmanager    (ports 9090 / 9093 — observability stack)
3. Start orchestrator                 (port 8081 — AI service)
4. Start frontend                     (port 3000 — dashboard)
5. Trigger an incident:               curl -X POST localhost:8080/api/simulate/error?count=20 (×35 over 70s)
6. Prometheus fires alert → Alertmanager webhook → orchestrator wakes up
7. Orchestrator runs the agent swarm, streams progress to frontend over SSE
8. Frontend shows proposed fix; SRE clicks "Approve" → GitHub PR created
```

Today only steps 1, 2, and 5 work end-to-end. Steps 3, 4, 6, 7, 8 land as we build out `orchestrator/` and `frontend/`.

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

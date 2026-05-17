# SwarmSRE — Team Q&A

This document captures clarifications raised by the team during the initial design phase. Each section restates the question, gives the resolution, and includes the reasoning so anyone joining later has full context.

---

## Q1. Adapter Layer — what is it?

**Question:** Is the Adapter Layer a tool like Grafana, or is it code we write to extract required parameters from a dashboard and forward them to the pipeline (similar to ETL validation of JSON)?

**Answer:** It is code we write, not a tool. The teammate's intuition is correct — the adapter is a small extractor, validator, and normalizer.

### What the adapter actually does

For each observability source it:
1. Receives data from the external system (Prometheus, Datadog, CloudWatch, etc.)
2. Extracts only the fields we care about
3. Validates the JSON shape
4. Transforms it into our internal standard format
5. Pushes it to the pipeline

In effect, it is ETL for alerts.

### Why we need it

Different observability tools speak different "languages." The same incident is described very differently by each tool:

**Prometheus Alertmanager payload:**
```json
{
  "status": "firing",
  "alerts": [{
    "labels": {"alertname": "HighCPU", "severity": "critical", "instance": "payment-svc"},
    "annotations": {"summary": "CPU > 90%"},
    "startsAt": "2026-05-16T10:00:00Z"
  }]
}
```

**Datadog payload:**
```json
{
  "alert_type": "error",
  "title": "[Triggered] High CPU on payment-service",
  "priority": "P1",
  "tags": ["service:payment", "env:prod"],
  "date": 1715856000
}
```

**CloudWatch payload:**
```json
{
  "AlarmName": "payment-cpu-high",
  "NewStateValue": "ALARM",
  "Trigger": {"MetricName": "CPUUtilization", "Threshold": 90}
}
```

The same event, three completely different shapes. Our agents should not have to know all three. The adapter normalizes everything to a single internal format.

### Internal standardized event

```json
{
  "incidentId": "INC-882",
  "source": "prometheus",
  "severity": "critical",
  "service": "payment-service",
  "summary": "CPU > 90%",
  "timestamp": "2026-05-16T10:00:00Z",
  "rawPayload": { ... }
}
```

Agents always see the same shape regardless of the source tool.

### What an adapter looks like in code

For each source, one small handler:

```python
# adapters/prometheus_adapter.py  (~30 lines)
@app.post("/webhook/prometheus")
def prometheus_webhook(payload: dict):
    alert = payload["alerts"][0]

    incident = {
        "incidentId": generate_id(),
        "source": "prometheus",
        "severity": map_severity(alert["labels"]["severity"]),
        "service": alert["labels"]["instance"],
        "summary": alert["annotations"]["summary"],
        "timestamp": alert["startsAt"],
        "rawPayload": payload
    }

    validate_schema(incident)
    push_to_pipeline(incident)
    return {"status": "received"}
```

That is the entire adapter — no magic, no external tool, just a webhook receiver, extractor, and validator.

### Where it sits in the architecture

```
[Prometheus running in client infra]  ─┐
[Datadog SaaS]                         ├─→ webhook POST → [ADAPTER LAYER]
[CloudWatch + SNS]                     ─┘                       │
                                                                ↓
                                                    Standardized IncidentEvent
                                                                │
                                                                ↓
                                                       [Event Hub / Pipeline]
                                                                │
                                                                ↓
                                                         [Agent Swarm]
```

We do not run Prometheus or Datadog — the client already does. We only receive what those tools send us via webhook.

### Common confusions cleared up

| Misconception | Reality |
|---------------|---------|
| Adapter is a tool we install | It is code we write |
| Adapter pulls data from Grafana dashboards | Grafana/Prometheus push alerts to us via webhook |
| We need to scrape metrics ourselves | The observability tool already does that — we just consume its alerts |
| Each adapter is large | Roughly 30–50 lines per adapter — it is just a JSON transformer |

### Quick primer on the source tools

| Tool | What it does | What we get |
|------|--------------|-------------|
| Prometheus | Collects time-series metrics (CPU, memory, request rate) | HTTP POST from Alertmanager when a threshold is breached |
| Grafana | Visualizes Prometheus data and supports alert rules | Webhook fires from a Grafana alert rule |
| Datadog | SaaS equivalent of Prometheus + Grafana | Webhook from Datadog monitor alerts |

We do not have to install or operate any of them.

### Hackathon scope decision

We will build one **generic adapter** only:

```python
@app.post("/webhook/incident")
def generic_webhook(incident: IncidentEvent):
    # Already in our standard format
    push_to_pipeline(incident)
```

For the demo we trigger it with `curl -X POST` and a fake incident payload. Judges do not need to see real Prometheus running; they need to see what happens after the alert arrives. If time permits, we add one real adapter (Prometheus is easiest) to demonstrate pluggability.

---

## Q2. Agent architecture — Orchestrator vs Specialized

**Question:** Should we go with specialized agents or an orchestrator-led design? Concern: with a purely specialized split, an agent might raise a PR without enough knowledge, or the system might fail to pull a human in when needed.

**Answer:** We go with the orchestrator-led design (Option B from COMPONENTS.md).

### Why orchestrator wins

- **No overlap:** each sub-agent owns a distinct data domain (logs, metrics, code) instead of a phase, so work is not duplicated.
- **Backtracking is natural:** the orchestrator can call the same agent again if it needs more information, without complex routing logic.
- **Parallel execution is possible:** Log Analyst and Metrics Agent can run simultaneously and have their outputs synthesized.
- **Human-in-the-loop fits cleanly:** the orchestrator decides when to pause and escalate, instead of relying on every agent to know its own limits.
- **Scalable:** adding new specialist agents (e.g., Database Agent) only requires teaching the orchestrator when to call them.

### One caution

The orchestrator prompt becomes critical. A bad prompt breaks the whole system. We should plan for multiple prompt iterations and test against several fake incident scenarios before the demo.

---

## Q3. Escalation when AI is stuck

**Question:** What happens when the AI cannot solve the issue? With an orchestrator we can route to a human directly — human intervention is needed when the AI lacks the memory, knowledge, or capability to handle the issue.

**Answer:** Agreed. The orchestrator owns the escalation decision based on simple rules.

### Escalation triggers

- More than 3 tool calls without a clear root cause → escalate
- Confidence score below 60% → escalate
- The same tool returns an error twice → escalate

### UI behavior

The dashboard shows a "Needs Human" banner with the partial findings the agent did manage to gather. The human picks up where the agent left off. This is sufficient for the demo and is honest about the system's limits.

---

## Q4. Confidence scoring

**Question:** Confidence is a good idea, but how do we implement it? Do we initialize Java rules and metric thresholds for the LLM? Could we use something like DeepEval?

**Answer:** Use a hybrid: LLM self-rating as the primary signal, with a small Java post-check for sanity. DeepEval is the wrong tool for runtime confidence.

### Why not DeepEval

DeepEval is an offline evaluation framework — essentially pytest for LLMs. It is designed to test outputs against a dataset of known-correct answers using metrics like AnswerRelevancy, Faithfulness, Hallucination, and GEval.

| Concern | DeepEval | What we need |
|---------|----------|--------------|
| When it runs | Offline, on a test dataset | Real-time, per incident |
| Needs ground truth | Yes — an expected answer to compare against | No — incidents are novel, no "correct" answer exists yet |
| Latency | Multiple LLM calls per metric (slow) | Sub-second response for the dashboard |
| Cost | Adds extra LLM calls on top of the agent's | Already paying for agent calls; multiplying cost is brutal |
| Output | Test report | A single number on the dashboard |

The core mismatch: DeepEval needs a "correct answer" to compare against. During a live incident there is no correct answer yet — that is exactly what the agent is trying to find. There is also a stack mismatch: DeepEval is Python, our backend is Spring Boot, so we would need a sidecar service just for confidence.

### Where DeepEval would actually be useful

Not at runtime, but for **pre-demo prompt regression testing**. Build a dataset of 10 fake incidents with known root causes, run the agent on each, score with DeepEval, and iterate on prompts until quality clears a bar. This is prompt engineering work, not a user-facing confidence number — keep them separate.

### Recommended runtime approach (hybrid)

**Primary signal — LLM self-rating via structured output:**

```java
// In Fix-IT agent prompt
"Return JSON: {
   rootCause: string,
   proposedFix: string,
   confidence: number (0-100),
   reasoning: string
}"
```

The model returns 85 or 40 directly. The dashboard color-codes:
- 80+: green badge
- 50–79: yellow
- below 50: red, with "Human review needed"

**Secondary signal — Java post-check (sanity adjustment):**

```java
int confidence = llmConfidence;
if (toolCallsUsed < 2) confidence -= 20;   // didn't investigate enough
if (logsFetched == 0)  confidence -= 30;   // no evidence
if (toolCallErrors > 0) confidence -= 15;  // hit failures during investigation
```

The LLM provides the main signal; the rules act as a guardrail.

### Optional upgrade: G-Eval inline

If we want stronger rigor without DeepEval's overhead, take just the G-Eval idea — LLM-as-judge with a rubric — and run it as a second LLM call after Fix-IT proposes a solution:

```
"You are a senior SRE. Rate this proposed fix 0-100 on:
- Evidence quality
- Fix specificity
- Risk level
Output: {score: N, reasoning: '...'}"
```

This is essentially what DeepEval's GEval metric does, in 20 lines of code, without pulling in a framework.

---

## Q5. Deploy mechanism — PR vs direct infra changes

**Question:** PR is better; an AI directly performing infra changes does not look good, especially for a demo. PR will look better — if we can build it.

**Answer:** Agreed. The Fix-IT Agent will create a GitHub Pull Request via the GitHub REST API.

### Why PR is the right choice

- **Safer:** humans review before anything is applied.
- **Auditable:** every change has a diff, an author, and a comment trail.
- **Demoable:** clicking the PR link and showing it on GitHub is a strong visual moment.
- **No infra access needed:** we avoid Terraform, kubectl, and cloud credentials in the demo.

### Demo flow

1. Agent generates a diff for the proposed fix.
2. Dashboard shows the diff and an "Approve & Deploy" button.
3. Approval calls the GitHub API, creating a PR on a target repo.
4. We click the PR link in the demo so judges see a real GitHub PR.

If GitHub API integration runs out of time, the fallback is to display the diff and the PR body that *would* be created — still a credible demo without the live API call.

---

## Q6. Data layer — fake data and DB consolidation

**Question:** All log and Cosmos DB data will be fake. Do we really need two databases? Can Cosmos DB handle both jobs?

**Answer:** Yes, drop PostgreSQL for the hackathon. Use Cosmos DB only.

### Plan

Use Cosmos DB with two containers:
- `mock_logs` — fake log entries used by the Log Analyst agent.
- `agent_memory` — past incident contexts used for memory and learning.

### Why one DB is enough

- Logs are fake (50–100 entries) and just need to support a demo incident.
- Agent memory needs to hold 2–3 past incidents at most.
- One database means one connection string, one auth setup, and fewer moving parts to break in the demo.
- Cosmos DB's flexible schema handles both use cases comfortably.

### How to frame it for judges

> "In production, PostgreSQL would be ideal for structured logs, but for this hackathon Cosmos DB's flexible schema lets us handle both logs and agent memory in a single store, keeping the demo focused on the AI behavior."

This turns the simplification into an explicit, defensible choice rather than a gap.

---

## Cross-cutting decision: keep infrastructure thin, spend the time on AI

**Position:** We will not over-invest in Java plumbing or supporting infrastructure. The novel value of this project is in the AI behavior, so the bulk of our time should go there.

### The trap to avoid

Most hackathon teams spend roughly 70% of their time on plumbing (Spring Boot configuration, Kafka setup, WebSocket wiring, DB schemas) and 30% on the actual AI. The result is a working pipeline with a mediocre agent — judges have already seen many of those. We invert the ratio: 70% on AI quality, 30% on the minimum plumbing required to demo it.

### Cuts we are making

| Original plan | Cut to | Why |
|---------------|--------|-----|
| Spring Boot + Kafka + Event Hubs | Single backend service with a direct controller → orchestrator path | Event Hubs adds no AI value for the demo |
| Multiple adapters (Prometheus, Datadog, ELK, CloudWatch) | One generic webhook (`POST /incident`) | Adapters are boilerplate; judges do not score on adapter count |
| PostgreSQL + Cosmos DB | One Cosmos DB (per Q6) | Two DBs is unjustified for fake data |
| WebSocket via STOMP/SockJS | Server-Sent Events (SSE) | STOMP is overkill for one-way streaming |
| Spring AI tool beans wrapped in extra abstractions | Direct Spring AI / Azure SDK function-calling | Skip the framework layer on top of the framework |
| GitHub PR creation with full auth flow | Minimal API call, fallback to displaying the diff | Demo-friendly, avoids auth issues |

The result is a far smaller surface area of glue code, freeing days of work for the AI core.

### Where the AI time goes

This is the work that wins the hackathon:

1. **Orchestrator prompt engineering** — multiple iterations against fake incidents, with chain-of-thought and self-reflection.
2. **Tool design for agents** — the right abstractions (`fetchLogs(service, timeRange)`), pre-summarized inputs, strong tool descriptions.
3. **Multi-agent coordination logic** — when the orchestrator calls Log Analyst vs Metrics Agent, how context flows between calls. This is the real "swarm" magic.
4. **Confidence and reasoning transparency** — LLM self-rating plus visible reasoning so the user understands *why* a fix was proposed.
5. **RAG over fake runbooks** — even five fake runbooks indexed in Azure AI Search demonstrate "agent retrieved doc X to inform fix Y", which is far more compelling than raw LLM output.

### How to frame this for judges

> "We deliberately kept infrastructure thin to spend our time on what is novel — the multi-agent reasoning, tool design, and human-in-the-loop flow. Everything you see is real AI work; the plumbing is just enough to show it."

This framing turns minimalism into a strength.

---

## Open items still to resolve

These are not yet decided and should be discussed next:

- Which LLM model for which agent? (GPT-4o for all, or DeepSeek for cheaper/faster sub-agents?)
- Agent memory scope: per-incident only, or shared across incidents to demonstrate learning?
- Incident deduplication strategy — do two alerts from the same root cause spawn two swarms?
- Rollback plan — if an approved fix worsens the situation, what do we do?
- Demo "wow moment" — the agent graph, the auto-fix, the speed, or the human-in-the-loop pause?

# SwarmSRE - Backend Orchestrator

SwarmSRE is an event-driven, AI-powered Site Reliability Engineering (SRE) orchestrator. It ingests system incidents, uses a Large Language Model to autonomously fetch logs, determines root causes, and proposes resolution steps before human intervention is required.

> **🤖 FOR AI AGENTS & LLMs:**
> If you are an AI assisting with this codebase, read this section carefully before modifying code:
> 1. **Hackathon Constraint:** This project heavily relies on the Microsoft/Azure ecosystem.
> 2. **The AI Backdoor (CRITICAL):** We are using **GitHub Models** (`https://models.inference.ai.azure.com`) via the standard `spring-ai-openai` dependency, NOT the `spring-ai-azure-openai` dependency. This is intentional to bypass "Azure for Students" strict quota limits on `gpt-4o`. **DO NOT** attempt to switch the `pom.xml` back to Azure OpenAI or it will break the deployment.
> 3. **Paradigm:** We use Event-Driven Architecture (Kafka) for ingestion, not synchronous REST webhooks.
> 4. **Outputs:** The AI must always return strict, structured JSON (mapped to Java Records) for UI consumption.

---

## 🏗 Architecture Flow

1. **Ingestion:** Monitoring tools (Prometheus/Grafana) push alerts to a Kafka topic (`incident-alerts`).
2. **Consumption:** Spring Boot `@KafkaListener` picks up the event asynchronously.
3. **Orchestration:** `SwarmOrchestrator` triggers a Spring AI `ChatClient` loop.
4. **Tool Calling:** The LLM requests access to `SystemTools` (e.g., fetching microservice logs).
5. **Resolution:** The LLM synthesizes the logs and generates a structured `ResolutionProposal`.
6. **Persistence:** The proposal is permanently saved to **Azure Cosmos DB** (Partitioned by `service`).
7. **Delivery (Next Step):** The backend streams the resolution to the React Frontend via Server-Sent Events (SSE).

---

## 🛠 Tech Stack

* **Core:** Java 21, Spring Boot 3.5.x
* **AI Orchestration:** Spring AI (`spring-ai-openai-spring-boot-starter`)
* **AI Model:** `gpt-4o` (Hosted on Azure Inference / GitHub Models)
* **Persistence:** Azure Cosmos DB for NoSQL (`spring-cloud-azure-starter-data-cosmos`)
* **Messaging:** Apache Kafka
* **Observability:** Micrometer / Prometheus (Endpoint: `/actuator/prometheus`)

---

## 🚀 Local Setup & Execution

### 1. Prerequisites
* JDK 21 installed.
* Local Kafka broker running on `localhost:9092`.
* An active Azure Cosmos DB for NoSQL Account.
* A GitHub Classic Personal Access Token (No scopes required).

### 2. Environment Variables
You MUST export these variables in your terminal before starting the application. Do NOT commit these to source control.

```bash
# GitHub Token for Azure Inference API
export GITHUB_TOKEN="ghp_your_classic_token_here"

# Azure Cosmos DB Credentials
export COSMOS_DB_ENDPOINT="[https://your-cosmos-db-account.documents.azure.com:443/](https://your-cosmos-db-account.documents.azure.com:443/)"
export COSMOS_DB_KEY="your_primary_key_here"

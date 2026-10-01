# PerfKit

> A free, open-source API performance testing platform powered by Spring Boot, React, and k6.

PerfKit lets you import API specifications, define multi-step API scenarios, generate k6 load tests, execute performance tests, and analyze results — **free, self-hosted, and open-source**.

## Features

* **OpenAPI 3.x Import** — Generate draft scenarios from OpenAPI JSON/YAML definitions.
* **Scenario DSL** — Define requests, think times, branching, loops, variable extraction, and assertions.
* **k6 Compilation** — Convert scenario definitions into executable k6 JavaScript.
* **Performance Metrics** — Analyze P50, P90, P95, P99 latency, throughput, error rate, and per-step metrics.
* **Performance Thresholds** — Define thresholds and get PASS/FAIL results.
* **Run Comparison** — Compare benchmark runs to identify performance changes and regressions.
* **Self-Hosted** — Run the complete platform locally or on your own infrastructure with Docker Compose.

## Quick Start

### Requirements

* Docker Desktop
* Docker Compose

### Run

```bash
git clone https://github.com/Mrsele/API-Performance-Testing-Platform.git
cd API-Performance-Testing-Platform

docker compose up --build
```

### Services

| Service          | URL                       | Description                |
| ---------------- | ------------------------- | -------------------------- |
| **UI Dashboard** | http://localhost:5173     | React web application      |
| **Backend API**  | http://localhost:8080/api | Spring Boot REST API       |
| **Demo API**     | http://localhost:8081     | Built-in system under test |

### Run Your First Test

1. Open `http://localhost:5173`.
2. Create a project using the demo API base URL.
3. Create the demo scenario and save it.
4. Select a load preset such as `smoke`.
5. Preview the generated k6 script.
6. Run the test.
7. Review latency, throughput, error rate, and per-step metrics.
8. Run another test and compare the results.

## Architecture

```text
React UI
    │
    ▼
Spring Boot API
    │
    ├── PostgreSQL
    │
    └── Scenario Compiler
            │
            ▼
        k6 Process
            │
            ▼
        Target API
            │
            ▼
        Metrics & Results
```

### Technology Stack

* **Frontend:** React, TypeScript, Vite
* **Backend:** Java, Spring Boot, Spring Data JPA
* **Database:** PostgreSQL
* **Performance Engine:** Grafana k6
* **Deployment:** Docker / Docker Compose

## Project Status

PerfKit is currently in active development.

The first vertical slice focuses on:

```text
OpenAPI
   ↓
Scenario
   ↓
Load Profile
   ↓
k6
   ↓
Metrics
   ↓
Thresholds
   ↓
Run Comparison
```

## Roadmap

* [ ] Relational test-data generation
* [ ] Reusable datasets and data seeding
* [ ] Environment and secret management
* [ ] Multi-scenario traffic mixes
* [ ] Visual scenario builder
* [ ] Distributed test execution
* [ ] CI/CD integration
* [ ] OpenTelemetry / Prometheus / Grafana integration
* [ ] Advanced performance analytics

## Open Source

PerfKit is **free and open-source software** designed to be self-hosted.

Contributions, feature requests, bug reports, and pull requests are welcome.

## License

Licensed under the MIT License. See [LICENSE](LICENSE).

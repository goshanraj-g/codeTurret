# 🛡️ CodeTurret
### **Make your vibe-coded project production-ready**
**Winner of Google Developer Groups @ McMaster Hackathon 🏆**

CodeTurret scans a GitHub repository for security vulnerabilities, streams results live, and can open a pull
request with AI-generated fixes. It does not paste whole files into an LLM. A hybrid engine (parsing, static
analysis, a trained ML model, and call-graph reachability) decides *which code* is worth an LLM's attention,
and the LLM *verifies* those candidates with real context.

---

## Results

On a benchmark of 30 labelled vulnerabilities across three apps (OWASP NodeGoat, dvpwa, and a hand-written
Flask/Express/Spring fixture), the metric is the share of real vulnerabilities whose code actually reaches
the LLM when the engine may send a fixed number of lines:

| Selector | @250 lines | @1000 lines |
|---|---:|---:|
| v1: keyword regex + file cap | 40% | 63% |
| Semgrep alone | 60% | 87% |
| Trained ML classifier alone | 43% | 77% |
| **v2 hybrid engine** | **70%** | **93%** |

The v1 pipeline could never find the other 37%: it didn't scan Java at all, and a regex bug dropped every
JavaScript function after the first. Full tables, per-repo breakdowns, and an ablation study are in
[`eval/results/`](eval/results/). How the benchmark works is described in [`eval/README.md`](eval/README.md).

---

## How it works

```
 clone ─► PARSE        tree-sitter → functions/methods (Python, JS, TS, TSX, Java) + name-based call graph
      ─► SIGNALS      Semgrep hits · ML P(vulnerable) · reachability from route handlers · dangerous sink calls · git history
      ─► RANK         weighted score per function; Semgrep hits always verified; fill a fixed line budget
      ─► VERIFY       fast LLM per file with line numbers, callers, and Semgrep hints ("confirm or reject")
                      → escalate to a strong LLM for HIGH/CRITICAL or low-confidence findings
      ─► PERSIST      each finding stores its source (AI / Semgrep + AI / Semgrep only) and why it was analysed
```

The full design, including the tradeoffs and failure modes, is in
[`docs/architecture/detection-engine.md`](docs/architecture/detection-engine.md).

### Design decisions

- **The LLM verifies; it doesn't hunt.** LLMs are good at judging whether input can reach a sink in context,
  and bad (and expensive) at scanning thousands of lines for needles. Ranking decides where to look. The LLM
  decides what's real.
- **A line budget, not a file cap.** Forty risky functions from forty files beat twenty-five whole files. It
  also makes cost per scan predictable.
- **A classifier we trained ourselves, used as a signal and not a verdict.** A logistic-regression model on
  hashed code n-grams, trained on real CVE fixes, synthetic vulnerable/fixed pairs, and ~7.9k functions from
  mature open-source projects. It's exported to ONNX and runs inside the JVM. Function-level vulnerability
  classifiers generalize poorly to unseen code, and ours does too (see the table above), which is why it only
  helps decide where to look.
- **Measure everything.** Every engine change is scored on the benchmark. The ablation showed that
  reachability (how close code is to a route handler) is the single most valuable signal. The weights were
  deliberately *not* tuned to the 30-vuln benchmark, because that would overfit it.
- **Degrade gracefully.** No Semgrep? No model file? The LLM is down? Each layer is optional, and the engine
  reports what it can.

---

## Features

- **Hybrid detection engine:** parsing, static analysis, ML, and LLM verification (above)
- **Explainable findings:** each finding shows whether it came from AI, Semgrep + AI, or Semgrep only, plus
  the signals that made the engine look at that code
- **Real-time streaming:** progress streams file by file over Server-Sent Events
- **Auto-fix PRs:** one click generates patches for every finding and opens a GitHub pull request
- **Git intelligence:** blame, hot files, and security-related commits enrich findings and ranking
- **Ask Cortex:** ask questions about a scan in plain English ("who introduced the SQL injection?"), powered
  by Snowflake Cortex

---

## Architecture

```
Next.js frontend
      │
      ├── POST /api/scan  ─────────────────►  RabbitMQ [scan.requests] ─► ScanWorker ─► DetectionEngine
      ├── GET  /api/scans/{id}/stream  ◄────  SSE ◄── RabbitMQ [scan.progress]
      ├── GET  /api/findings/{scanId}
      ├── POST /api/scans/{id}/fix  ────────►  RabbitMQ [fix.requests] ─► FixWorker ─► GitHub PR
      └── POST /api/ask  ──────────────────►  Snowflake Cortex

Spring Boot 3.3 (Java 21)            PostgreSQL + Flyway: repos, scans, findings, fix PRs
ml/ (Python): trains the classifier → backend/src/main/resources/ml/vuln-classifier.onnx
eval/: benchmarks with ground truth + EvalRunner
```

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.3, RabbitMQ, PostgreSQL + Flyway, JGit |
| Parsing | tree-sitter (JNI bindings) |
| Static analysis | Semgrep (optional) |
| ML | scikit-learn → ONNX, run with ONNX Runtime Java |
| LLM | OpenAI GPT-5.6 Luna + GPT-5.5 (default) or Google Gemini 2.5 Flash + Pro |
| Q&A | Snowflake Cortex |
| Frontend | Next.js, Tailwind CSS |

---

## Getting started

### Prerequisites
- Java 21 (Maven is bundled via `./mvnw`)
- Docker Desktop (PostgreSQL + RabbitMQ)
- Node.js 20+
- An [OpenAI API key](https://platform.openai.com/api-keys) (or a [Gemini key](https://aistudio.google.com) with `LLM_PROVIDER=gemini`)
- Optional: [Semgrep](https://semgrep.dev/docs/getting-started/) (`pip install semgrep`; on Windows, use WSL and set `SEMGREP_CMD`)
- Optional: a Snowflake account for the Ask feature

### Run locally

```bash
# 1. Infrastructure
cd backend
docker-compose up -d

# 2. Configuration: fill in OPENAI_API_KEY and ENCRYPTION_SECRET_KEY
cp .env.example .env

# 3. Backend (Flyway creates the tables on first run)
./mvnw spring-boot:run

# 4. Frontend (expects the API at http://localhost:8080; override with NEXT_PUBLIC_API_URL)
cd ../frontend
npm install && npm run dev
```

Backend: `http://localhost:8080` · Frontend: `http://localhost:3000`

### Tests, benchmark, and model

```bash
cd backend && ./mvnw test                       # backend + engine tests (no API key needed)
cd backend && ./mvnw -q compile exec:java       # run the benchmark → eval/results/candidates.md
cd ml && python train.py                         # retrain the classifier (see ml/README.md)
```

### Error reporting (optional)

Set `SENTRY_DSN` in `backend/.env` to send backend errors to [Sentry](https://sentry.io). Without it, nothing is
sent. Events are tagged with the scan ID, and credentials and scanned code are scrubbed before they leave the
server (`SentryScrubber`).

The frontend reports to its own Sentry project. Set `NEXT_PUBLIC_SENTRY_DSN` in `frontend/.env.local`, and put
`SENTRY_AUTH_TOKEN` in `frontend/.env.sentry-build-plugin` to upload source maps during `npm run build`. Session
Replay is off, and request bodies, headers and stack-frame variables are not collected (`frontend/lib/sentry.ts`).

---

## API

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/scan` | Queue a scan; returns `scanId` immediately |
| `GET` | `/api/scans/{id}/stream` | SSE stream of scan progress |
| `GET` | `/api/scans` | List recent scans |
| `GET` | `/api/findings/{scanId}` | Findings for a scan, including `source`, `cweId`, `mlScore`, `signals` |
| `POST` | `/api/scans/{id}/fix` | Queue auto-fix PR generation |
| `GET` | `/api/scans/{id}/fix` | Fix PR status |
| `POST` | `/api/ask` | Ask Cortex a question about a scan |
| `POST` | `/api/repos` | Register a repo with a GitHub PAT |

---

## Limitations and next steps

- The benchmark is small (30 vulns). Treat one-vuln differences as noise.
- End-to-end metrics (finding precision/recall after LLM verification) aren't implemented yet. Current numbers
  measure what reaches the LLM, not what it concludes.
- The call graph resolves calls by name only, and reachability is approximate.
- Next: code-embedding features (UniXcoder) as an A/B against hashed n-grams, learned ranker weights, and a
  per-file content-hash cache so re-scans only re-verify changed code.

---

## Screenshots

| Homepage | Scanner | Reports |
|---|---|---|
| ![](media/homepage.png) | ![](media/scan.png) | ![](media/reports.png) |

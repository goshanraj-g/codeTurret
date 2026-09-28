# Project: CodeTurret
AI-powered security scanner: clones a GitHub repo, finds vulnerabilities with a hybrid
static-analysis + ML + LLM engine, streams progress, and can open auto-fix PRs.

## Architecture
- **Backend:** Java 21, Spring Boot 3.3 (`backend/`). RabbitMQ workers (`ScanWorker`, `FixWorker`), SSE progress.
- **Storage:** PostgreSQL + Flyway (`backend/src/main/resources/db/migration`). Snowflake Cortex is used only by the Ask feature.
- **Detection engine:** `com.codeturret.engine`. See `docs/architecture/detection-engine.md`.
  Parse (tree-sitter) → candidates (Semgrep + ONNX classifier + git) → rank under a line budget → LLM verify (OpenAI by default, or Gemini; `llm.provider`).
- **ML:** `ml/` (Python) trains the vulnerability classifier and exports ONNX into `backend/src/main/resources/ml/`.
- **Eval:** `eval/` holds benchmarks with ground truth. Use it to measure any engine change.
- **Frontend:** Next.js (`frontend/`).

## Commands
- **Infra:** `cd backend && docker-compose up -d`
- **Backend:** `cd backend && ./mvnw spring-boot:run`
- **Backend tests:** `cd backend && ./mvnw test`
- **Frontend:** `cd frontend && npm install && npm run dev`

## Standards
- Always run `./mvnw test` after changing engine or service logic.
- Engine changes that affect selection or ranking must include before/after eval numbers in the PR.
- Parameterized queries only; no string-built SQL.
- Credentials live in `backend/.env` (see `.env.example`); never commit them.
- Tokenizer/feature-hashing changes must be made in both `ml/` and `engine.ml` and keep the golden-vector parity test green.

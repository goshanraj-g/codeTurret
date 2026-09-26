# Detection Engine v2: Architecture

**Status:** In progress (stacked PRs `engine/01` → `engine/09`)
**Date:** 2026-09-26
**Authors:** Goshanraj Govindaraj, Claude

This document describes how CodeTurret decides *which code the LLM looks at* and *how findings are
produced*, why we moved off the regex pipeline, and how we measure whether the new design is actually
better.

---

## 1. Why change anything?

The v1 pipeline (`RiskAssessorService` + `CodeExtractorService` + `GeminiService`) works like this:

```
all files ─[keyword regex score]─► top 25 files ─[12 regexes → snippets]─► Gemini Flash ─► Gemini Pro (if HIGH)
```

Its problems, in order of impact:

| # | Problem | Consequence |
|---|---|---|
| 1 | **Regex decides what the LLM ever sees.** Files are ranked by path names (`auth/`, `routes/`) and by counting keywords like `"password"` or `"select "`. Only the top 25 files are scanned. | Recall is capped *before* any AI runs. A path traversal in `lib/files.py` with no keywords scores like a benign helper. |
| 2 | **No real parsing.** Python gets 10-line windows around regex hits. JS/TS functions are found with a regex plus brace counting, and that code has a bug: every function after the first is dropped (see `CodeExtractorServiceTest.knownBug_dropsJsFunctionsAfterTheFirst`). | The LLM gets fragments with no caller context, so it guesses at whether input is attacker-controlled. |
| 3 | **When nothing matches, the whole file is sent.** | High token cost exactly where the signal is weakest. |
| 4 | **Serial scanning with `Thread.sleep(7000)` between files.** | A 25-file scan takes about 3 minutes, most of it sleeping. |
| 5 | **No measurement.** | We could not say whether a change made the scanner better or worse. |

## 2. Goals and non-goals

**Goals**
- Replace regex *selection* with a ranker that combines static analysis, a trained ML model, code
  structure, and git history.
- Give the LLM a *verifier* role with real context (the function, its callers, whether it is reachable from a
  route), not a "find anything" role on fragments.
- Make quality **measurable**: a benchmark with ground truth, reporting recall, precision, lines sent, and time.
- Degrade gracefully: every layer except the LLM is optional (Semgrep not installed, model file missing, and so on).

**Non-goals (for this stack)**
- Inter-procedural taint analysis. We use a name-based call graph, not a full data-flow engine. Semgrep
  covers the intra-procedural data-flow part.
- Changing the queue, SSE, or persistence architecture. Only `ScanWorker`'s inner loop changes.
- Replacing Gemini.

## 3. Target pipeline

```
                              ┌──────────────────────────────────────────────────────────┐
 cloned repo ─► SourceLoader ─┤ 1. PARSE      tree-sitter → CodeUnits (functions/methods,│
                              │               module bodies) + name-based CallGraph      │
                              │ 2. CANDIDATES Semgrep (optional) → StaticHits             │
                              │               VulnClassifier (ONNX) → P(vulnerable)/unit  │
                              │               GitService → hot files, security commits    │
                              │ 3. RANK       CandidateRanker: weighted signals → score   │
                              │               select top units under a line budget        │
                              │ 4. VERIFY     LlmVerifier: per-file batch, unit + callers │
                              │               + static hints → Flash; escalate to Pro     │
                              └──────────────────────────────┬───────────────────────────┘
                                                             ▼
                                       EngineFinding(source, mlScore, signals, …)
```

All of this lives in `com.codeturret.engine` behind one entry point:

```java
EngineResult DetectionEngine.run(Path repoDir, EngineOptions options, EngineListener listener)
```

`ScanWorker` shrinks to: clone, run the engine, persist, publish progress.

### 3.1 Parse: `engine.parse`

- **`CodeParser`** uses tree-sitter (`io.github.bonede:tree-sitter`, JNI bindings with prebuilt natives) for
  Python, JavaScript, TypeScript/TSX, and Java.
- It emits **`CodeUnit`**s: `{file, language, name, kind (FUNCTION|METHOD|MODULE), startLine, endLine, code,
  calls[]}`. A `MODULE` unit holds top-level statements outside any function (scripts and Flask/Express
  route registration often live there).
- It also detects **entrypoints**: route handlers (`@app.route`, `@GetMapping`/`@PostMapping`/`@RequestMapping`,
  `app.get(...)`/`router.post(...)` callbacks, aiohttp `add_route`). Entrypoints are where attacker input arrives.
- **`CallGraph`** resolves calls by simple name within the repo. It is imprecise by design (no type
  resolution), but it is enough to answer "who calls this?" and "how many hops from a route handler?".
- Unsupported languages fall back to a single `MODULE` unit per file, so nothing is silently skipped.

*Tradeoff:* tree-sitter gives us real syntax trees for about 40 languages from one library, but its Java
bindings use native code. We pin a version with prebuilt Windows, macOS, and Linux binaries and keep the
parser behind an interface.

### 3.2 Candidates: static analysis, `engine.staticanalysis`

- **`SemgrepRunner`** shells out to `semgrep scan --json --metrics=off` with the language rule packs plus
  `p/security-audit`, and maps results to `StaticHit{file, line, ruleId, message, severity, cwe}`.
- It is optional. If `semgrep` is not on `PATH` or times out, the engine logs this and continues.
  (Native Windows support is still experimental. Use WSL, Docker, or Linux CI.)

*Why Semgrep and not our own rules:* thousands of maintained rules with data-flow support. Writing our
own would just be a worse regex engine.

### 3.3 Candidates: ML classifier, `engine.ml` + `/ml`

A model **we train ourselves** estimates `P(vulnerable | function)`.

**Features:** a shared, language-agnostic tokenizer splits code into identifier sub-tokens (`getUserById`
becomes `get`, `user`, `by`, `id`), string/number placeholders, and operators. It produces unigrams and bigrams, hashed
with **FNV-1a 32-bit into 2^18 buckets**, then applies log scaling and L2 normalization. The Python training
code and the Java inference code implement the same spec. A **golden-vector test** (Python generates
vectors, Java asserts equality) keeps them in sync.

**Model:** logistic regression (scikit-learn), exported to **ONNX** and run in-process with
**ONNX Runtime Java**. There is no Python service at scan time. ONNX Runtime is pinned to 1.18: newer builds
crash on Windows inside the older `msvcp140.dll` that JDKs ship and load first.

**Data:**
| Dataset | What | Languages | Caveat |
|---|---|---|---|
| `DetectVul/CVEFixes` | Real functions from CVE fix commits, line-level labels | Python | Real, but one language |
| `CyberNative/Code_Vulnerability_Security_DPO` | Vulnerable vs. fixed pairs | JS, Java, Python, and more | **Synthetic** (LLM-generated) and short; names leak labels, so they are scrubbed |
| Benign corpus (`ml/benign_corpus.json`) | Functions from 12 mature OSS repos, extracted with our own `CodeParser` | JS, TS, Python, Java | Assumed non-vulnerable (noisy); whole repos held out for testing |

Splits are by *source record*, not by row, so a vulnerable function and its fix never land on opposite
sides of the split.

*Why not a transformer embedding model (CodeBERT/UniXcoder)?* Running one in Java needs a tokenizer
runtime plus a ~500 MB model, which would dominate scan latency on CPU. Hashed lexical features plus a linear
model is roughly 1 MB, runs in microseconds, and is an honest baseline. Because the eval harness measures
ranking quality, swapping in embeddings later is an A/B test, not a rewrite.

*Known limitation:* published results (e.g. DiverseVul, 2023) show function-level vulnerability
classifiers generalize poorly to unseen projects. So the classifier is **one ranking signal, never a
verdict**. It decides where the LLM looks, not what gets reported.

### 3.4 Rank: `engine.rank`

Each unit gets a score in [0, 1]:

```
score = 0.35·ml + 0.30·static + 0.15·reachability + 0.10·git + 0.10·structure
```

| Signal | Definition |
|---|---|
| `ml` | classifier probability |
| `static` | 1.0 if a Semgrep ERROR hit is inside the unit, 0.7 WARNING, 0.4 INFO, else 0 |
| `reachability` | 1.0 entrypoint, 0.6 called by an entrypoint, 0.3 two hops away, else 0 |
| `git` | hot-file and security-commit signals from `GitService`, normalized |
| `structure` | the unit calls a known sink (exec/query/open/eval/deserialize…) *as a call node in the AST*, not as a substring |

Selection: **every unit with a static hit is selected** (Semgrep findings always get verified), then the
highest-scoring remaining units are added until the **line budget** (`engine.line-budget`, default 3000
lines) is spent. The weights live in config. Section 4.1 shows how each one was checked with an ablation.

*Tradeoff:* a line budget instead of a file cap makes cost predictable and lets 40 small risky functions
from 40 files beat 25 whole files.

### 3.5 Verify: `engine.verify`

- `LlmVerifier` groups selected units by file (one LLM call per file) and sends, for each unit: the code
  with real line numbers, any Semgrep hits as hints ("a rule flagged line 42 as CWE-89; confirm or reject"),
  its callers' signatures, entrypoint status, and git context.
- The prompt asks the model to **reject** false positives explicitly and to report `exploitability`
  reasoning. Low-confidence or HIGH/CRITICAL results escalate to the Pro model, as in v1.
- Calls run with bounded concurrency (a semaphore sized by `gemini.max-concurrency`) and retry with backoff on
  429 responses, replacing the fixed `sleep`.
- Each finding records a **`source`**: `LLM` (found by the LLM in ML/structure-ranked code),
  `STATIC+LLM` (a Semgrep hit confirmed by the LLM), or `STATIC` (a Semgrep hit with the LLM unavailable, reported at
  reduced confidence). It also stores `ml_score` and the ranking signals, so the UI can explain *why* code was looked at.

## 4. Evaluation: `eval/`

```
eval/
  fixtures/polyglot-shop/     hand-written app: labelled vulns + safe look-alikes (py/js/ts/java)
  benchmarks/*.json           ground truth: repo (fixture path or git URL + pinned commit) + vulns[]
```

Ground truth entry: `{file, startLine, endLine, cwe, note}`.

**Metrics**
| Metric | Meaning | Needs API key? |
|---|---|---|
| **Candidate recall@budget** | Share of ground-truth vulns whose lines fall inside code *sent* to the LLM, at 500/1000/2000/3000-line budgets | No |
| **Lines sent** | Total lines of code sent to the LLM | No |
| **Finding recall / precision** | A finding matches ground truth when it is in the same file and within the labelled range ±3 lines | Yes (*planned*: not yet implemented) |
| **Wall time** | Seconds per scan | Yes (*planned*) |

End-to-end (LLM) metrics are not implemented yet. The verifier is covered by fake-LLM tests
(`LlmVerifierTest`, `DetectionEngineTest`), but nothing here measures Gemini's accuracy on the benchmark.

Candidate recall runs offline in CI (`EvalRunner --mode candidates`). It compares selectors (legacy regex,
Semgrep only, ML only, hybrid) on the same budget, which is how the ranker weights are tuned.

Results are written to `eval/results/candidates.md` (committed, so each PR diff shows the metric change) and summarized in the README.

### 4.1 Results so far (candidate recall, 30 labelled vulns)

| Selector | @250 lines | @1000 lines |
|---|---:|---:|
| legacy regex (v1) | 40% | 63% |
| structure (parse + sinks + reachability) | 63% | 83% |
| ML classifier alone | 43% | 77% |
| Semgrep alone | 60% | 87% |
| **hybrid** | **70%** | **93%** |
| hybrid without Semgrep | 70% | 90% |

**Ablation** (`eval/results/ablation.md`, each signal's weight set to zero): reachability matters most
(93% → 83% @1000 without it). ML helps only at larger budgets (97% → 93% @2000). Removing structure slightly
*helps* at 1000 lines because it overlaps with Semgrep. With 30 vulns a one-vuln change is noise, so the
default weights are **not** re-tuned to the benchmark. Doing that would overfit it.

## 5. Configuration

```yaml
engine:
  line-budget: 3000
  weights: { ml: 0.35, static: 0.30, reachability: 0.15, git: 0.10, structure: 0.10 }
  semgrep: { enabled: true, timeout-seconds: 300, configs: [p/security-audit, p/python, p/javascript, p/typescript, p/java] }
  classifier: { model-path: classpath:ml/vuln-classifier.onnx }
gemini:
  max-concurrency: 4
```

## 6. Failure modes

| Failure | Behaviour |
|---|---|
| Semgrep missing or times out | `static` = 0 for all units; logged once; scan continues |
| ONNX model missing or incompatible | `ml` = 0.5 (neutral); warning logged; scan continues |
| tree-sitter cannot parse a file | file becomes one `MODULE` unit |
| Gemini 429 | exponential backoff (max 3 tries), then that file is marked skipped in progress events |
| Gemini unavailable | static hits are reported as `STATIC` findings with capped confidence |

## 7. PR stack

| PR | Branch | Content |
|---|---|---|
| 1 | `engine/01-foundation` | This doc, accurate `CLAUDE.md`, Maven wrapper, characterization tests, CI |
| 2 | `engine/02-eval-harness` | Benchmarks + ground truth, `engine` core types, `CandidateSelector`, legacy baseline numbers |
| 3 | `engine/03-tree-sitter` | `CodeParser`, `CodeUnit`, `CallGraph`, entrypoint detection |
| 4 | `engine/04-semgrep` | `SemgrepRunner` + `StaticHit` mapping |
| 5 | `engine/05-ml-classifier` | `/ml` training pipeline, ONNX export, Java `VulnClassifier`, parity test |
| 6 | `engine/06-hybrid-ranker` | `CandidateRanker`, selector comparison table |
| 7 | `engine/07-llm-verifier` | `LlmVerifier`, `DetectionEngine`, `ScanWorker` wiring, `V2` migration (`source`, `ml_score`, `signals`) |
| 8 | `engine/08-frontend` | Findings UI: source badges, ML score, "why was this scanned" |
| 9 | `engine/09-readme` | README rewrite with architecture and benchmark results |

## 8. Future work

- Swap hashed features for code embeddings (UniXcoder via ONNX) and A/B it on the benchmark.
- Learn the ranker weights (e.g. logistic regression over signals) instead of hand-tuning.
- Per-file content-hash cache so re-scans only re-verify changed code.
- Incremental persistence and parallel per-file jobs on RabbitMQ (pipeline sub-project).

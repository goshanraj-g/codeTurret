# Eval harness

Measures the detection engine against repos with known vulnerabilities, so changes are judged by numbers
rather than by eye.

```
eval/
  benchmarks/*.json    ground truth: where the code comes from + labelled vulns (file, line range, CWE)
  fixtures/            hand-written benchmark code (intentionally vulnerable; never deploy)
  results/             generated reports (committed, so PR diffs show metric changes)
  .cache/              git benchmarks cloned at their pinned commit (ignored)
```

## Run

```bash
cd backend
./mvnw -q compile exec:java                                    # all selectors, all benchmarks
./mvnw -q compile exec:java -Dexec.args="--selectors legacy-regex --benchmark nodegoat"
```

Selectors that need Semgrep run only when `semgrep --version` works. Native Semgrep on Windows is unreliable;
use WSL instead (`SEMGREP_CMD="wsl.exe -e /home/<you>/.venvs/semgrep/bin/semgrep"`) or rely on the CI `eval` job,
which installs Semgrep on Linux and uploads the report as an artifact.

## What is measured

**Candidate recall@budget.** Each selector returns code units in priority order. We take them greedily
until N lines are spent (N = 500, 1000, 2000, 3000, or unlimited) and count a labelled vuln as covered when at
least half of its lines were sent. If code never reaches the LLM, the LLM can't find the bug, so this
is an upper bound on end-to-end recall at that cost. It needs no API key and runs in CI.

## Benchmarks

| Name | Stack | Vulns | Notes |
|---|---|---:|---|
| `polyglot-shop` | Flask, Express/React/TS, Spring | 14 | Hand-written. Probes keyword-free vulns, includes safe look-alikes. Designed with knowledge of v1's weaknesses, so treat it as a targeted test, not an unbiased one. |
| `nodegoat` | Express, MongoDB | 12 | OWASP NodeGoat, labelled from its A1-A10 tutorial. Contains hint comments. |
| `dvpwa` | aiohttp, Postgres | 4 | Damn Vulnerable Python Web App. Small, so every selector sees all of it. |

Labels are best-effort. Real repos may contain unlabelled vulns, so any future precision metric is a lower bound.

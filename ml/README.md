# ml/: vulnerability classifier

Trains the model behind the engine's `ml` signal: `P(vulnerable | code unit)`. The model is exported to ONNX
and runs inside the Java backend (`com.codeturret.engine.ml.VulnClassifier`), so there is no Python at scan time.

## Pipeline

```
DetectVul/CVEFixes (real Python CVE functions) ─┐
CyberNative DPO (synthetic vuln/fixed pairs)  ───┼─► features.py (hashed 1-2 grams) ─► logistic regression ─► ONNX
benign corpus (12 mature OSS repos)           ───┘
```

| Step | Command |
|---|---|
| Set up | `python -m venv .venv && .venv/Scripts/pip install -r requirements.txt` (use `bin/` on macOS/Linux) |
| Export benign units (uses the Java parser) | `cd ../backend && ./mvnw -q compile exec:java -Dexec.mainClass=com.codeturret.eval.UnitExporter` |
| Train, evaluate, export | `python train.py` → `backend/src/main/resources/ml/vuln-classifier.onnx` + `metrics.json` |
| Test | `python -m pytest tests` |
| Update golden vectors | `python -m codeturret_ml.golden` (only after an intentional feature-spec change) |

## Design notes

- **Same features in two languages.** `codeturret_ml/features.py` is the spec. The Java `CodeFeatures` class
  mirrors it, and golden vectors (`backend/src/test/resources/ml/golden-features.json`) are checked by both
  test suites.
- **Leakage control.** Synthetic samples name their bugs ("VulnerableClass", "You have been hacked!"), so
  those words are scrubbed from training text. Each synthetic vulnerable/fixed pair stays within one split.
  Benign repos are split by repo, so the benign test set is code from projects the model never saw.
- **Benign corpus.** Without it, the model learned "short code is vulnerable" (synthetic positives are short
  snippets) and scored `return a + b` at 0.90. Adding real functions from mature projects fixed that: 0.6% of
  held-out benign functions are flagged. Treating these as non-vulnerable is noisy, but it's a standard,
  useful assumption.
- **It is a ranking signal, not a detector.** See `metrics.json` for held-out numbers and
  `eval/results/candidates.md` for how it ranks code in unseen repos, where it is weaker than the static
  signals on its own.

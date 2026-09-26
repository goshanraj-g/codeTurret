"""Train the vulnerability classifier and export it to ONNX for the Java backend.

    python train.py            # train, evaluate, export model + metrics
    python train.py --no-export

Outputs:
    backend/src/main/resources/ml/vuln-classifier.onnx   model used by com.codeturret.engine.ml.VulnClassifier
    ml/metrics.json                                        held-out metrics (committed so PRs show changes)
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, precision_recall_fscore_support, roc_auc_score

from codeturret_ml import data, features

ROOT = Path(__file__).resolve().parent.parent
MODEL_OUT = ROOT / "backend/src/main/resources/ml/vuln-classifier.onnx"
METRICS_OUT = Path(__file__).resolve().parent / "metrics.json"
SEED = 7


def evaluate(y: np.ndarray, p: np.ndarray) -> dict:
    precision, recall, f1, _ = precision_recall_fscore_support(y, p >= 0.5, average="binary", zero_division=0)
    return {
        "n": int(len(y)),
        "positive_rate": round(float(y.mean()), 3),
        "roc_auc": round(float(roc_auc_score(y, p)), 3),
        "pr_auc": round(float(average_precision_score(y, p)), 3),
        "precision@0.5": round(float(precision), 3),
        "recall@0.5": round(float(recall), 3),
        "f1@0.5": round(float(f1), 3),
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-export", action="store_true")
    ap.add_argument("--C", type=float, default=4.0, help="inverse L2 regularization strength")
    args = ap.parse_args()

    samples = data.load()
    train = [s for s in samples if s.split == "train"]
    test = [s for s in samples if s.split == "test"]
    assert not {s.group for s in train} & {s.group for s in test}, "group leakage between splits"
    print(f"train={len(train)} test={len(test)}")

    x_train = features.matrix([s.code for s in train])
    y_train = np.array([s.label for s in train])
    model = LogisticRegression(C=args.C, class_weight="balanced", max_iter=2000, solver="liblinear", random_state=SEED)
    model.fit(x_train, y_train)

    metrics = {"model": "logistic-regression", "C": args.C, "features": f"hashed 1-2 grams, dim={features.DIM}"}
    for name in ("all", "cvefixes", "dpo"):
        subset = [s for s in test if name == "all" or s.source == name]
        p = model.predict_proba(features.matrix([s.code for s in subset]))[:, 1]
        metrics[f"test/{name}"] = evaluate(np.array([s.label for s in subset]), p)
        print(name, metrics[f"test/{name}"])

    # Held-out benign repos contain no positives, so report how often ordinary code is flagged instead.
    benign = [s for s in test if s.source == "benign"]
    p = model.predict_proba(features.matrix([s.code for s in benign]))[:, 1]
    metrics["test/benign-heldout-repos"] = {
        "n": len(benign),
        "mean_score": round(float(p.mean()), 3),
        "flagged@0.5": round(float((p >= 0.5).mean()), 3),
    }
    print("benign", metrics["test/benign-heldout-repos"])

    METRICS_OUT.write_text(json.dumps(metrics, indent=2) + "\n")

    if not args.no_export:
        export(model)


def export(model: LogisticRegression) -> None:
    from skl2onnx import convert_sklearn
    from skl2onnx.common.data_types import FloatTensorType

    onnx_model = convert_sklearn(
        model,
        initial_types=[("features", FloatTensorType([None, features.DIM]))],
        options={id(model): {"zipmap": False}},
        target_opset=17,
    )
    MODEL_OUT.parent.mkdir(parents=True, exist_ok=True)
    MODEL_OUT.write_bytes(onnx_model.SerializeToString())
    print(f"wrote {MODEL_OUT.relative_to(ROOT)} ({MODEL_OUT.stat().st_size // 1024} KiB)")


if __name__ == "__main__":
    main()

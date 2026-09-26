"""Training data: download, clean, and split into train/test without leakage."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

import pandas as pd
import requests

CACHE = Path(__file__).resolve().parent.parent / ".data"

SOURCES = {
    "cvefixes_train": "https://huggingface.co/api/datasets/DetectVul/CVEFixes/parquet/default/train/0.parquet",
    "cvefixes_test": "https://huggingface.co/api/datasets/DetectVul/CVEFixes/parquet/default/test/0.parquet",
    "dpo": "https://huggingface.co/api/datasets/CyberNative/Code_Vulnerability_Security_DPO/parquet/default/train/0.parquet",
}

# Languages from the synthetic set that share web-security idioms with what CodeTurret scans.
DPO_LANGUAGES = {"python", "javascript", "java", "c#", "php", "ruby", "go", "kotlin"}

# Synthetic samples leak their label through names and strings ("VulnerableClass", "You have been hacked!").
# Scrub those words from training text so the model has to learn from the code itself.
_LEAKY_WORDS = re.compile(
    r"(?i)(in)?secure(ly)?|(un)?safe(ly)?|vulnerab\w*|hack\w*|malicious\w*|exploit\w*|attack\w*|evil\w*|pwn\w*"
)
_FENCE = re.compile(r"^```[^\n]*\n|\n?```\s*$")


@dataclass(frozen=True)
class Sample:
    code: str
    label: int     # 1 = vulnerable
    source: str    # dataset name, for per-source metrics
    group: str     # samples sharing a group never cross the train/test boundary
    split: str     # "train" or "test"


def download(name: str) -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / f"{name}.parquet"
    if not path.exists():
        resp = requests.get(SOURCES[name], timeout=120)
        resp.raise_for_status()
        path.write_bytes(resp.content)
    return path


def scrub(code: str) -> str:
    return _LEAKY_WORDS.sub("x", code)


def _cvefixes(split: str) -> list[Sample]:
    df = pd.read_parquet(download(f"cvefixes_{split}"))
    out = []
    for i, row in df.iterrows():
        code = "".join(row["raw_lines"])
        label = int(any(int(x) != 0 for x in row["label"]))  # values 1-5 are vuln categories
        out.append(Sample(code, label, "cvefixes", f"cvefixes-{split}-{i}", split))
    return out


def _dpo(test_every: int = 5) -> list[Sample]:
    """Each row is a (vulnerable, fixed) pair; both halves go to the same split."""
    df = pd.read_parquet(download("dpo"))
    out = []
    for i, row in df.iterrows():
        if row["lang"] not in DPO_LANGUAGES:
            continue
        split = "test" if i % test_every == 0 else "train"
        group = f"dpo-{i}"
        for text, label in ((row["rejected"], 1), (row["chosen"], 0)):
            code = scrub(_FENCE.sub("", text.strip()))
            out.append(Sample(code, label, "dpo", group, split))
    return out


def _benign() -> list[Sample]:
    """Units from mature projects (see benign_corpus.json), exported by the backend's UnitExporter.

    Labelled 0 on the assumption that most functions in these projects are not vulnerable. That is noisy, but
    it teaches the model what ordinary production code looks like. Whole repos are held out for testing.
    """
    path = CACHE / "benign_units.jsonl"
    if not path.exists():
        raise FileNotFoundError(
            f"{path} missing: run `./mvnw -q compile exec:java -Dexec.mainClass=com.codeturret.eval.UnitExporter` in backend/")
    out = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            row = json.loads(line)
            out.append(Sample(row["code"], 0, "benign", f"benign-{row['repo']}", row["split"]))
    return out


def load() -> list[Sample]:
    return _cvefixes("train") + _cvefixes("test") + _dpo() + _benign()

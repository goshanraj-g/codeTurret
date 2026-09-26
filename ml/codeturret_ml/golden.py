"""Golden feature vectors shared with the Java implementation.

    python -m codeturret_ml.golden        # rewrite the golden file after an intentional spec change

The backend test ``CodeFeaturesTest`` recomputes these vectors in Java and asserts they match, and
``tests/test_features.py`` asserts the committed file matches the current Python spec.
"""
from __future__ import annotations

import json
from pathlib import Path

from codeturret_ml import features

GOLDEN_PATH = Path(__file__).resolve().parents[2] / "backend/src/test/resources/ml/golden-features.json"

SAMPLES = [
    "def load(name):\n    return send_file(os.path.join(BASE, name))  # serve it\n",
    'cur.execute(f"SELECT sku FROM sales WHERE region = \'{region}\'")',
    "exports.results = async (req, res) => {\n  res.send('<h1>' + req.query.q + '</h1>'); // echo\n};",
    "/* block\ncomment */ const x = getUserById(42.5);",
    'String sql = "SELECT * FROM t WHERE c = \'" + customer + "\'";\njdbc.query(sql, mapper);',
    "url = 'http://example.com/#anchor'  # not a comment inside the string? (spec: it is stripped)",
    "HTTPServerError __init__ $scope snake_case_name parseXMLDocument",
    "café = '…' \U0001F600",
    "",
]


def build() -> list[dict]:
    out = []
    for code in SAMPLES:
        vec = features.vectorize(code)
        out.append({
            "code": code,
            "features": features.features(code),
            "vector": {str(k): round(v, 7) for k, v in sorted(vec.items())},
        })
    return out


def main() -> None:
    GOLDEN_PATH.parent.mkdir(parents=True, exist_ok=True)
    GOLDEN_PATH.write_text(json.dumps(build(), indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {GOLDEN_PATH}")


if __name__ == "__main__":
    main()

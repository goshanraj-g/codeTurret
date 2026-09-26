"""Language-agnostic code features: tokenize, then hash n-grams into a fixed-size vector.

This module is the *specification*. ``com.codeturret.engine.ml.CodeFeatures`` in the backend implements the
same steps, and ``tests/test_features.py`` writes golden vectors that the Java test suite checks, so the
two cannot drift apart silently. Change both or neither.

Steps:
  1. Strip comments: ``/* ... */`` blocks, then ``//`` and ``#`` line comments that start a line or follow
     whitespace. This is approximate on purpose (no per-language lexer), and identical in both implementations.
  2. Split into raw tokens: identifiers ``[A-Za-z_$][A-Za-z0-9_$]*``, numbers ``[0-9]+(\\.[0-9]+)?``, or any
     single non-space, non-word character.
  3. Base tokens: identifiers lowercased, numbers become ``<num>``, punctuation kept. At most MAX_TOKENS.
  4. Features: every base token; ``sub:<part>`` for each camelCase/snake_case part of identifiers made of
     2+ parts; every adjacent base-token bigram ``"<a> <b>"``.
  5. Hash each feature with FNV-1a (32-bit, UTF-8 bytes) into DIM buckets and count.
  6. Values: ``log1p(count)``, then L2-normalize.
"""
from __future__ import annotations

import math
import re

import numpy as np
from scipy import sparse

DIM = 1 << 18
MAX_TOKENS = 2000

_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
_LINE_COMMENT = re.compile(r"(^|[ \t])(#|//)[^\n]*", re.MULTILINE)
_TOKEN = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*|[0-9]+(?:\.[0-9]+)?|[^ \t\r\n\f\u000bA-Za-z0-9_$]")
_IDENT = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")
_NUMBER = re.compile(r"[0-9]+(?:\.[0-9]+)?")
_PARTS = re.compile(r"[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|[0-9]+")

_FNV_OFFSET = 0x811C9DC5
_FNV_PRIME = 0x01000193


def strip_comments(code: str) -> str:
    code = _BLOCK_COMMENT.sub(" ", code)
    return _LINE_COMMENT.sub(lambda m: m.group(1), code)


def raw_tokens(code: str) -> list[str]:
    return _TOKEN.findall(strip_comments(code))[:MAX_TOKENS]


def normalize(raw: str) -> str:
    if _IDENT.fullmatch(raw):
        return raw.lower()
    if _NUMBER.fullmatch(raw):
        return "<num>"
    return raw


def identifier_parts(identifier: str) -> list[str]:
    parts: list[str] = []
    for chunk in re.split(r"[_$]+", identifier):
        parts.extend(p.lower() for p in _PARTS.findall(chunk))
    return parts


def features(code: str) -> list[str]:
    raws = raw_tokens(code)
    base = [normalize(r) for r in raws]
    feats = list(base)
    for raw in raws:
        if _IDENT.fullmatch(raw):
            parts = identifier_parts(raw)
            if len(parts) > 1:
                feats.extend("sub:" + p for p in parts)
    feats.extend(f"{a} {b}" for a, b in zip(base, base[1:]))
    return feats


def fnv1a(text: str) -> int:
    h = _FNV_OFFSET
    for byte in text.encode("utf-8"):
        h ^= byte
        h = (h * _FNV_PRIME) & 0xFFFFFFFF
    return h


def vectorize(code: str) -> dict[int, float]:
    """Sparse feature vector as {bucket: value}."""
    counts: dict[int, int] = {}
    for f in features(code):
        idx = fnv1a(f) & (DIM - 1)
        counts[idx] = counts.get(idx, 0) + 1
    values = {i: math.log1p(c) for i, c in counts.items()}
    norm = math.sqrt(sum(v * v for v in values.values()))
    if norm == 0:
        return {}
    return {i: v / norm for i, v in values.items()}


def matrix(codes: list[str]) -> sparse.csr_matrix:
    rows, cols, vals = [], [], []
    for r, code in enumerate(codes):
        for c, v in vectorize(code).items():
            rows.append(r)
            cols.append(c)
            vals.append(v)
    return sparse.csr_matrix((np.array(vals, dtype=np.float32), (rows, cols)), shape=(len(codes), DIM))

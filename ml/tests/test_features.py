import json
import math

from codeturret_ml import data, features, golden


def test_comments_are_stripped_but_code_is_kept():
    toks = features.raw_tokens("x = 1  # set x\n/* gone */ y = 2 // also gone")
    assert "set" not in toks and "gone" not in toks
    assert toks == ["x", "=", "1", "y", "=", "2"]


def test_identifiers_are_lowercased_and_split():
    feats = features.features("getUserById")
    assert "getuserbyid" in feats
    assert {"sub:get", "sub:user", "sub:by", "sub:id"} <= set(feats)
    assert features.identifier_parts("HTTPServerError") == ["http", "server", "error"]
    assert features.identifier_parts("snake_case_name") == ["snake", "case", "name"]


def test_numbers_are_normalized_and_bigrams_emitted():
    feats = features.features("f(42)")
    assert "<num>" in feats
    assert "f (" in feats and "( <num>" in feats


def test_fnv1a_matches_reference_values():
    assert features.fnv1a("") == 0x811C9DC5
    assert features.fnv1a("a") == 0xE40C292C
    assert features.fnv1a("foobar") == 0xBF9CF968


def test_vector_is_l2_normalized():
    vec = features.vectorize("eval(req.body.code)")
    assert math.isclose(math.sqrt(sum(v * v for v in vec.values())), 1.0, rel_tol=1e-9)
    assert features.vectorize("") == {}


def test_scrub_removes_label_leaking_words():
    assert "vulnerable" not in data.scrub("class VulnerableClass: pass").lower()
    assert "hacked" not in data.scrub("print('You have been hacked!')").lower()


def test_golden_file_is_up_to_date():
    committed = json.loads(golden.GOLDEN_PATH.read_text(encoding="utf-8"))
    assert committed == json.loads(json.dumps(golden.build(), ensure_ascii=False)), \
        "feature spec changed: run `python -m codeturret_ml.golden` and update the Java side"

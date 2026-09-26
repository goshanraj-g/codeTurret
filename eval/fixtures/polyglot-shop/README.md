# polyglot-shop (eval fixture)

**Intentionally vulnerable. Never deploy.** This is a small shop app split into a Flask API (`shop/`), an
Express/React web tier (`web/`) and a Spring billing service (`billing/`). The answer key lives in
`eval/benchmarks/polyglot-shop.json`, not in the code, so scanners can't read hints from comments.

The fixture contains safe look-alikes (parameterized queries, escaped output, keyword-heavy config) so it
measures false positives as well as recall.

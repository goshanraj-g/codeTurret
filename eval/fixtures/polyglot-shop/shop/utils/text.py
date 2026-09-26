import re
import unicodedata

_SLUG_STRIP = re.compile(r"[^a-z0-9]+")


def slugify(value: str) -> str:
    value = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode()
    return _SLUG_STRIP.sub("-", value.lower()).strip("-")


def truncate(value: str, limit: int = 80) -> str:
    return value if len(value) <= limit else value[: limit - 1] + "…"

import requests

TIMEOUT_SECONDS = 5


def ping(payload):
    target = payload["callback_url"]
    resp = requests.get(target, timeout=TIMEOUT_SECONDS)
    return {"status": resp.status_code, "body": resp.text[:200]}

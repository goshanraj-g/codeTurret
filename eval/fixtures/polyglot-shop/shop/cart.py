import base64
import pickle


def empty_cart():
    return {"items": [], "coupon": None}


def load_cart(cookie_value):
    if not cookie_value:
        return empty_cart()
    return pickle.loads(base64.b64decode(cookie_value))

import os

from flask import send_file

ASSET_DIR = "/srv/shop/assets"


def asset_path(name):
    return os.path.join(ASSET_DIR, name)


def load_asset(name):
    return send_file(asset_path(name))

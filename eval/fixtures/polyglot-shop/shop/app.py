from flask import Flask, request, jsonify

from shop import storage, reports, greetings, cart, webhooks, auth
from shop.config import settings

app = Flask(__name__)
app.config["SECRET_KEY"] = settings.secret_key


@app.route("/assets")
def asset():
    return storage.load_asset(request.args["name"])


@app.route("/reports/sales")
def sales_report():
    region = request.args.get("region", "north")
    return jsonify(reports.sales_by_region(region))


@app.route("/reports/customer/<int:customer_id>")
def customer_report(customer_id):
    return jsonify(reports.orders_for_customer(customer_id))


@app.route("/hello")
def hello():
    return greetings.welcome(request.args.get("name", "friend"))


@app.route("/cart")
def show_cart():
    return jsonify(cart.load_cart(request.cookies.get("cart", "")))


@app.route("/webhooks/test", methods=["POST"])
def webhook_test():
    return jsonify(webhooks.ping(request.get_json()))


@app.route("/login", methods=["POST"])
def login():
    body = request.get_json()
    return jsonify({"ok": auth.check_login(body["email"], body["password"])})

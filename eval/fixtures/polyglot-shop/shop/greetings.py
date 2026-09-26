from flask import render_template_string


def welcome(name):
    template = "<h1>Welcome back, " + name + "!</h1><p>Your cart is waiting.</p>"
    return render_template_string(template)

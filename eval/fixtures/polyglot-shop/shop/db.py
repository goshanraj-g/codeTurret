import psycopg2

from shop.config import settings


def connect():
    return psycopg2.connect(settings.database_url)

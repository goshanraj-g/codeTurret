import bcrypt

from shop.db import connect


def hash_password(password: str) -> bytes:
    return bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=12))


def check_login(email: str, password: str) -> bool:
    with connect() as conn:
        cur = conn.cursor()
        cur.execute("SELECT password_hash FROM users WHERE email = %s", (email,))
        row = cur.fetchone()
    if row is None:
        return False
    return bcrypt.checkpw(password.encode(), row[0])

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    secret_key: str
    database_url: str
    stripe_api_key: str
    smtp_password: str
    jwt_secret: str


settings = Settings(
    secret_key=os.environ["SECRET_KEY"],
    database_url=os.environ["DATABASE_URL"],
    stripe_api_key=os.environ["STRIPE_API_KEY"],
    smtp_password=os.environ["SMTP_PASSWORD"],
    jwt_secret=os.environ["JWT_SECRET"],
)

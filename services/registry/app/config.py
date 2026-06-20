from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str
    api_prefix: str = "/api"
    timescale_database_url: str | None = None

    class Config:
        env_file = ".env"


settings = Settings()

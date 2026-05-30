from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str
    api_prefix: str = "/api"

    class Config:
        env_file = ".env"


settings = Settings()

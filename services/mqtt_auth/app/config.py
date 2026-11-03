from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", env_file=".env", extra="ignore")

    authentik_issuer: str = "https://auth.danwils.com/application/o/phone-gateway/"
    authentik_jwks_url: str = (
        "https://auth.danwils.com/application/o/phone-gateway/.well-known/jwks.json"
    )
    authentik_audience: str = "phone-gateway"
    mqtt_ingest_topic: str = "telemetry/ingest"
    jwks_refresh_seconds: int = 3600


settings = Settings()

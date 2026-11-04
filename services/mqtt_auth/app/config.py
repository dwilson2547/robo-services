from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", env_file=".env", extra="ignore")

    authentik_issuer: str = "https://auth.danwils.com/application/o/phone-gateway/"
    # authentik serves a provider's keys at /application/o/<slug>/jwks/ (the jwks_uri
    # in its openid-configuration), not at .well-known/jwks.json
    authentik_jwks_url: str = "https://auth.danwils.com/application/o/phone-gateway/jwks/"
    authentik_audience: str = "phone-gateway"
    mqtt_ingest_topic: str = "telemetry/ingest"
    # Each user publishes to <mqtt_ingest_topic>/<their sub>, so the topic carries an
    # identity the broker checked (kreceiver stamps it). The bare topic stays allowed
    # until every app publishes per user; then set this false.
    allow_bare_ingest_topic: bool = True
    jwks_cache_seconds: int = 3600
    # phones' upload keys (docs/plans/2026-11-04-reliable-uploads.md §2), in-cluster
    registry_url: str = "http://robo-registry.robo-services.svc.cluster.local"


settings = Settings()

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str
    api_prefix: str = "/api"
    timescale_database_url: str | None = None

    # Phone app API (/api/app/*): authentik access tokens from the phone-gateway
    # provider, checked like mqtt-auth does. Keys and userinfo come from authentik's
    # in-cluster service (Cloudflare refuses Python's default user agent); the
    # token's iss is still checked against the public issuer.
    authentik_issuer: str = "https://auth.danwils.com/application/o/phone-gateway/"
    authentik_jwks_url: str = "http://authentik-server.authentik.svc.cluster.local/application/o/phone-gateway/jwks/"
    authentik_userinfo_url: str = "http://authentik-server.authentik.svc.cluster.local/application/o/userinfo/"
    authentik_audience: str = "phone-gateway"
    jwks_cache_seconds: int = 3600

    class Config:
        env_file = ".env"


settings = Settings()

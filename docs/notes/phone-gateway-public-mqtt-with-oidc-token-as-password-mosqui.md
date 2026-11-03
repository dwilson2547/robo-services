---
title: Phone gateway: public MQTT with OIDC token as password (mosquitto-go-auth HTTP backend)
date: 2026-11-02
tags: mqtt,auth,oauth,mosquitto,telemetry
---

Decision + wiring for getting a phone onto the telemetry pipeline without a VPN: Mosquitto TLS listener 8883 exposed via RouterOS dst-nat on a random high WAN port (48213) to MetalLB 192.168.0.71, cert from cert-manager letsencrypt-prod (Cloudflare DNS-01) for a grey-cloud mqtt.danwils.com record. Auth = authentik OIDC access token sent as the MQTT password (username=sub), validated broker-side by mosquitto-go-auth (iegomez) HTTP backend calling services/mqtt_auth, which checks the JWT against the provider JWKS. Plugin contract verified from backends/http.go: auth_opt_http_host/port/getuser_uri/aclcheck_uri, params_mode json, response_mode json expecting {"ok": bool}; ACL = publish-only telemetry/ingest; auth_opt_auth_cache_seconds=5 for fast revocation. MQTT 3.1.1 checks creds at CONNECT only, so mid-drive token expiry only matters at reconnect: app refreshes silently (5-min margin + on CONNACK 5), cleanSession=false for QoS1 queueing, and every frame is logged locally before publish so outages never lose data. See robotics/robo-services/docs/decisions/2026-11-03-phone-gateway-public-mqtt.md.

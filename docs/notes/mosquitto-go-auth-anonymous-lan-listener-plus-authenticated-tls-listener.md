---
title: mosquitto-go-auth (iegomez image) with an anonymous LAN listener plus an authenticated TLS listener
date: 2026-11-03
tags: mqtt,auth,mosquitto,mosquitto-go-auth
---

Verified by running iegomez/mosquitto-go-auth:3.0.0-mosquitto_2.0.18 locally with a real token round trip. (1) The image runs `mosquitto -c /etc/mosquitto/mosquitto.conf`; mount the config there, not at eclipse-mosquitto's /mosquitto/config/. (2) The plugin is /mosquitto/go-auth.so. (3) The backend option is `auth_opt_backends http` (plural); `auth_opt_backend` is a fatal "missing or blank option backends". (4) With two listeners you need `per_listener_settings true` and the auth_plugin lines under the TLS listener; otherwise the plugin and `allow_anonymous false` apply to every listener and anonymous LAN clients get CONNACK 5. (5) HTTP backend with params/response mode json: POSTs {username,password,clientid} to getuser_uri and {username,topic,clientid,acc} to aclcheck_uri, wants 2xx + {"ok":true}; acc 2 = publish, 4 = subscribe. A denied QoS 1 publish still gets PUBACK (3.1.1) and is dropped silently. Live config: infra/cluster-config/pub-sub/deployment.yml; validator: robotics/robo-services/services/mqtt_auth. Decision: robotics/robo-services/docs/decisions/2026-11-03-phone-gateway-public-mqtt.md.

---
title: apache-iggy 0.8 consumers go silent after an Iggy server restart
date: 2026-11-03
tags: iggy,gotcha,consumer-group
source: robotics/robo-services/services/session_tracker/README.md
---

After the Iggy server restarts, an apache-iggy 0.8 Python consumer-group client reconnects but never rejoins its group: consume_messages() neither delivers nor raises, so the process looks healthy (its timers keep logging) while consuming nothing. Seen 2026-11-03: session-tracker idled 2 h after iggy was redeployed. The SDK cannot report group membership. Any new consumer should use can_pub_sub_probe.consumer_watchdog (topic messages_count vs consumed offset; exit when the topic grew and the offset sat still), which makes Kubernetes restart it from the committed offset. Note messages_count is a property in 0.8, not a method. After any iggy redeploy, check that every Python consumer's offset is still moving.

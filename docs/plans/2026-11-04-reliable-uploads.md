# Reliable uploads: recording and uploading that survive bad signal and a lost sign-in

Status: step 1 **built** (2026-11-04), steps 2–3 proposed.

A drive should reach the cluster whatever happens to signal or sign-in: ending
in a garage, losing a token refresh, or not opening the app for a day.
Background: `docs/issues/2026_11_04_phone_gateway_signed_out_and_sign_in_crash.md`.

## What happens today (✅ verified 2026-11-04, from the code and authentik's logs)

**Uploading is tied to the sign-in token.**
- The phone publishes frames over MQTT (TLS, port 8883) with its authentik
  access token as the password.
- mqtt-auth checks that token against the phone-gateway provider's JWKS
  (signature, iss, aud, exp; username = `sub`).
- The broker allows publishing only on `telemetry/ingest/<sub>`.
- Access tokens last 30 minutes, so a CONNECT after that needs a token refresh.
- authentik issues a new refresh token on every refresh and revokes the old
  one, with no grace period; the server code does it at issue time.
- So if a refresh response is lost, the next refresh is refused and the app
  signs out (2026-11-04, 17:59 UTC).

**Nothing uploads after Stop.**
- Stop waits up to 5 s for the broker to ack `session_end`, then stops the
  publisher, and the service exits.
- Frames not yet sent stay in Paho's persisted disconnected buffer, which holds
  600 frames, about 10 minutes, dropping the oldest. They go out only when the
  next recording connects.
- `session_end`, if not acked, stays in the outbox until the next recording too.

**The frame log is never re-sent.**
- Every frame is written to `files/frames/session-<id>.bin` before it is
  published.
- Nothing reads it back for upload: `FrameLog.read` serves only the stats of an
  unclosed session.
- So frames that fall out of Paho's buffer are never uploaded.

## 1. Background catch-up from the frame log (built 2026-11-04)

**The registry is the source of truth, not a local ack ledger.**
- `GET /api/app/sessions/{id}/frames` lists the frames (`base_timestamp_us`)
  the pipeline has for one of the caller's sessions, and whether its end
  arrived. The data comes from `session_frames`, one row per frame.
- That catches frames the broker acked but the pipeline never took. It also
  covers sessions recorded before this existed.
- A 404 with detail "session not found" means the session isn't registered
  yet. session-tracker creates a session from a frame receipt alone, so the
  job sends all of it.

**`upload/CatchUpWorker`**, a WorkManager job with a CONNECTED constraint and
exponential backoff from 2 minutes:
1. It sends the outbox's session events.
2. For each `session-<id>.bin`, except the session being recorded and those
   already done, it asks the registry for the session's frames, then sends the
   logged frames that are missing, oldest first, at QoS 1.
3. A session is done when nothing is missing and its end has arrived. It is
   given up after 5 sends in a row that don't reduce what's missing.
4. Progress per session is kept in `files/catchup/<id>.json`.
5. The job returns retry until every session is done or given up.

It's scheduled:
- by Stop, after 2 minutes, so the live publisher's last frames clear first;
- and at every app start.

Enqueued work survives reboots. The job uses its own MQTT client id,
`<device>-catchup`, with a clean session. A second client with the recording's
id would make the broker drop the recording's connection.

**Duplicates (checked 2026-11-04, from the code):**
- session-tracker counts a frame once (`session_frames` primary key,
  `ON CONFLICT DO NOTHING`);
- vehicle-state skips a frame at or below the last second it closed for the
  session;
- drive-summary recomputes only when `frames_received` changes;
- can-discovery keeps an existing result built from more CAN frames, so late
  frames arriving as a tiny "new session" can't overwrite the whole drive's
  analysis;
- the raw topics and the archive just take the duplicates.

**Known gap:** a frame that fills a mid-drive gap is also "late" to
vehicle-state, which keeps its position in memory. So it reaches the registry
and the archive but not vehicle_state, until vehicle-state restarts (TODO.md).

## 2. A per-phone upload credential that is never sent

Uploading should not depend on the authentik session, and it should not rely on
a long-lived secret that crosses the network.

- **A device key in hardware.**
  - At sign-in, the app creates an EC P-256 key pair in the Android Keystore.
    It is hardware-backed where the phone supports it, and the private key is
    non-exportable.
  - The app registers the public key with the registry, authenticated by the
    signed-in user's access token: `PUT /api/app/devices/{device_id}/upload-key`.
  - The registry stores it on the device row, bound to that user's `sub`.
  - Signing in again replaces the key.
- **A short-lived token minted on the phone.**
  - For each MQTT CONNECT, the app signs a JWT with the device key (ES256):
    `iss` = device id, `sub` = the user's sub, `aud` = `mqtt-ingest`, `iat`,
    `exp` 10 minutes ahead, and a random `jti`.
  - That JWT is the MQTT password; the username stays the sub.
  - No refresh, no rotation, no network call to mint one.
- **mqtt-auth verification.**
  - A JWT whose `iss` is a device id is verified against that device's
    registered public key. The key is fetched from the registry and cached
    briefly, so a revoked key stops working within the cache time.
  - `sub` must match the key's owner, `exp` must be in the future, and `iat`
    can't be too far ahead.
  - The topic rule is unchanged: publish on `telemetry/ingest/<sub>` only.
  - authentik access tokens stay accepted as now, for older app builds and the
    changeover.
- **Revocation.** Removing the device's key in the registry cuts off a lost
  phone without touching the user's authentik account.

What a captured password would give: one token, valid for at most 10 minutes,
that can only publish telemetry as that user. It can't read anything, can't
call the registry, and can't mint more. The private key never leaves the
phone's hardware. TLS stays as it is.

**Open:** whether to also pin the broker's and registry's certificates in the
app, against interception by a trusted-but-wrong CA. A pin has to be updated
when the certificate's key changes. Pinning the issuing CA or the public key,
not the leaf, limits that.

## 3. Silent re-sign-in for browsing

History, Tracks and session details call the registry with the authentik
access token, and stay on authentik. When a refresh is refused:
- the app keeps the screens' cached data and marks the session as needing
  sign-in, instead of dropping straight to signed out;
- the next time it is in the foreground, it tries an authorization request with
  `prompt=none`. While authentik's browser session is alive, that returns
  without a login page;
- only if that fails does it show "Sign in".

Recording and uploading never wait on this (step 2).

## Order

1. Step 1, background catch-up, with the duplicate check. This is the biggest
   risk to data today, and it works with the current credentials while the app
   is signed in.
2. Step 2, the device credential: registry migration and endpoint, mqtt-auth,
   app.
3. Step 3, silent re-sign-in.

## Out of scope

- Patching authentik to revoke a superseded refresh token only after its
  successor is used. That's a sound pattern, but authentik 2026.8 doesn't offer
  it, and steps 2 and 3 make it unnecessary for this app.
- Changing the phone-gateway provider's token lifetimes.

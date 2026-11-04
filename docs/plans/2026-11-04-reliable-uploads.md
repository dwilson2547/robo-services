# Reliable uploads: recording and uploading that survive bad signal and a lost sign-in

Status: proposed (2026-11-04).

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

## 1. Background catch-up from the frame log

- **Ack ledger.** Paho's `deliveryComplete` already counts acks. The publisher
  will also record which frames were acked, per session, as the second offsets
  from the session's first frame, in a small file next to the frame log.
- **The job.** A WorkManager job, `UploadCatchUp`, with a `CONNECTED` network
  constraint:
  - scheduled by Stop when any frame or the `session_end` is unacked;
  - scheduled at boot, and by the app start, for any session with gaps;
  - it publishes the outbox, then every logged frame missing from the ledger,
    oldest first, QoS 1, and records acks as they come.
  - Android runs it once there is a network, with or without the app open, and
    keeps it across reboots.
- Paho's buffer stays for live publishing during a recording. It stops being
  the only copy of anything.
- **Done** when every frame of a session is acked and its end has been sent.
  The phone-storage cleanup keeps deleting logs only once the registry has the
  session complete.

**To verify before relying on it:** that a re-sent frame is harmless
downstream. The frame log's docs say replay is idempotent on
`(device_id, base_timestamp_us)`. Check this end to end:
- kreceiver and the raw topics take duplicates;
- session-tracker dedups receipts;
- vehicle-state upserts on `(source_session, device_id, t)`, but a frame
  re-sent after its first copy closed must not reopen it as incomplete or
  double its counts;
- the archive just stores duplicates.

Duplicates should be rare: only frames whose ack was lost, not every frame.

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

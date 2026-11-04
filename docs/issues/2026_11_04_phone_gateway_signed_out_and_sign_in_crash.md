# Issue: phone gateway signed out on a weak signal, sign-in then crashed, and lunch drives recorded as the wrong car

**Date:** 2026-11-04
**Component:** phone_gateway Android app (auth, Drive screen), authentik phone-gateway provider
**Symptoms:**
- The app signed out when recording was stopped in a parking garage.
- Signing in again either crashed the app or showed "sign-in failed, user
  cancelled flow".
- Drive, History and Tracks asked for sign-in, while Settings still showed the
  cached vehicles.

Separately: two drives recorded with the Blazer selected reached the registry as
the Impala.

---

## 1. Signed out: a rotated refresh token was lost (verified)

authentik's server log, 2026-11-04 (UTC):

| Time | Request from the phone (T-Mobile) | Result |
|---|---|---|
| 17:59:03 | `POST /application/o/token/` (refresh) | 200: new refresh token issued, the old one revoked |
| 17:59:07 | `POST /application/o/token/` with the **old** refresh token | 400, logged "Refresh token is revoked" (provider `phone-gateway`) and a `suspicious_request` event |

The provider rotates the refresh token on every refresh
(`refresh_token_threshold` = `seconds=0`), so each refresh revokes the token
used. In the garage the 200 reached authentik but not the phone. The app saw a
network failure, which `Auth.credentials()` treats as `Unavailable`, a retry.
The retry 4 s later sent the token authentik had just revoked. `INVALID_GRANT`
is the one case where the app drops the session, so it signed out.

This rules out a race between two app processes: the app runs in one process,
and `Auth` serializes refreshes behind a mutex.

**Follow-up:** `docs/plans/2026-11-04-reliable-uploads.md`. Uploading moves
to a per-phone credential that no refresh can revoke, and a refused refresh
leads to a silent re-sign-in instead of a sign-out. Shortening the rotation
window on authentik (`refresh_token_threshold`) was considered and not done:
it makes the risky moment rarer without removing it.

## 2. Sign-in crashed: AppAuth's redirect activity had no AppCompat theme (verified, fixed)

Crash buffer, every attempt from 12:59 local:

```
Unable to start activity ComponentInfo{com.robo.phonegateway/net.openid.appauth.RedirectUriReceiverActivity}:
java.lang.IllegalStateException: You need to use a Theme.AppCompat theme (or descendant) with this activity.
```

- **The cause:** AppAuth 0.11.1's manifest gives `AuthorizationManagementActivity`
  a theme (`Theme.AppCompat.Translucent.NoTitleBar`) but not
  `RedirectUriReceiverActivity`, so the latter inherits the application theme.
  The app rewrite on 2026-11-03 (`093b284`) changed that theme from
  `Theme.AppCompat.DayNight.NoActionBar` to the platform
  `Theme.Material.Light.NoActionBar`.
- **What the user saw:** the browser handed the result back, the receiver
  crashed, and AppAuth reported the lost result as "user cancelled flow".
- **Why it surfaced only now:** the persisted session meant nobody had signed in
  since the rewrite.

**Fix:** the app manifest merges `android:theme="@style/Theme.AppCompat.Translucent.NoTitleBar"`
into `RedirectUriReceiverActivity` (`tools:node="merge"`). The merged APK
manifest was checked with `aapt dump xmltree`, and sign-in on the phone worked
with no crash afterwards.

## 3. Drives recorded as the wrong car (verified, fixed)

- **What the registry shows:** sessions `1793812262671` and `1793814706312`,
  the lunch trips in the Blazer, have `vehicle_id` 1 (the Impala).
- **The cause:** the app remembers which car each dongle was last in
  (`vehicles.xml`: `{"58:E6:C5:1A:CE:22": 1}`). The Drive screen preselected
  that car in a `LaunchedEffect(status.dongleConnected)`, which reruns every
  time the screen is composed with the dongle connected, not only when it
  connects. Picking the Blazer, then coming back to Drive, switched it back to
  the Impala before recording.
- **The consequence:** the Blazer's CAN was decoded with the Impala's DBC.
  Had the Blazer been recorded, can-decode would have sent it to DBC discovery,
  since the Blazer has no DBC.

**Fix:** the preselect is offered once per dongle per app process
(`registry/DonglePreselect.kt`), so a car picked by hand sticks. Session start
still records the car used against the dongle (`rememberForDongle`).

**Repaired** (2026-11-04):
- the registry sessions and their 838 vehicle_state rows were moved to vehicle
  2 (Blazer);
- the CAN-derived columns were cleared: the role columns and `dbc` set null,
  and `signals`, `signal_stats` and `carried` set empty;
- the two summaries were deleted, and drive-summary rebuilt them from GPS and
  IMU.

A backup of the rows and summaries as they were was taken first, outside the
repo. Discovery runs on them via `can-discovery backfill`.

## Data

Nothing was lost. All three drives of the day were fully uploaded before the
sign-out: 391/407, 416/417 and 422/422 frames, each summarised. The upload
outbox on the phone was empty.

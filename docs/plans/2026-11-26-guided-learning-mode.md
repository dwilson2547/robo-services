# Guided learning mode: identify a car and learn its signals by asking the driver for scenarios

Status: **proposed** (2026-11-26). Step 1 of §9 is built (2026-11-27, `dbc_check.py --scenarios`); nothing else is. It sits on top of
[DBC discovery](2026-11-04-dbc-discovery.md) (server-side, no driver involved) and uses the checks in
`can_data/tools/dbc_check.py`.

Provenance: **✅** checked this session, **📋** planned, **⚠ unverified** not checked on our hardware.

## 1. What it is

A new owner plugs the dongle into a car nobody has decoded. The app:

1. works out which car it is (VIN, or the closest known DBC);
2. asks for a few short, safe scenarios ("sit still and idle", "drive straight at a steady speed");
3. runs a check per scenario against a reference it can trust;
4. builds a temporary DBC from what passed and shows how it compares with what the car really did;
5. offers to save it as a **candidate** DBC for the car.

It is the interactive half of discovery: the 2026-11-04 plan finds signals from whatever drives happen; this
asks for the drives that make each signal checkable.

## 2. What exists

- ✅ `dbc_check.py` ranks every DBC in `dbc/` by how much of the traffic it defines, then tests speed
  against GPS (correlation, fitted scale), rpm by idle band and per-gear structure, gear by speed rising across
  gears, pedal by range and movement. Results are PASS / PLAUSIBLE / FAIL / INCONCLUSIVE.
  INCONCLUSIVE means the log lacked what the check needs (for example no stopped seconds).
- ✅ On the three cars so far it found the right DBC and the gear signals without being told (F-150 at 96 %
  of ids, EcoSport and Impala by the gear/speed checks). Sample size: three cars.
- ✅ The Impala broadcasts its VIN on the bus (it decodes). Whether other cars do is **⚠ unverified**: the
  EcoSport and F-150 notes say their facts are owner-stated, not VIN-read, because nobody looked for a VIN on their buses.
- ✅ The dongle never transmits. Its firmware does not call `twai_transmit`; "ack" mode only drives ACK bits
  (`firmware/esp32_can_ble_bridge/*.ino`, header comment). It cannot ask a car anything today.
- 📋 The discovery plan has a server-side correlator (`SignalCorrelator`, in the phone companion) that
  slides candidate bit fields against a reference channel. Its reference is GPS speed or gyro.

## 3. Reference values: what a scenario can be checked against

| Reference | Gives | Needs | Status |
|---|---|---|---|
| Phone GPS speed (and dongle GPS) | speed, distance, stops, accel | a fix | ✅ used by the checker |
| Phone/dongle IMU | yaw rate, longitudinal/lateral accel | none | ✅ used for steering attempts (Impala: no match) |
| The owner's words | gear count, engine, "low idle is under 1000 rpm" | typing | ✅ already marked `owner-stated` |
| Broadcast VIN | year, make, model, engine | the car sends it | ✅ Impala only |
| OBD-II request/response (rpm, speed, coolant, VIN) | rpm, speed, coolant, VIN on any car that answers | the dongle must **transmit** | ⚠ unverified: standard OBD-II (functional request id `0x7DF`, replies `0x7E8..0x7EF`), not tried on any of our cars |

Without transmit, rpm can only be checked by idle band and per-gear structure, which is what gave the Impala a
PLAUSIBLE, not PASS. OBD-II would give rpm a real reference, and the VIN for every car. It is the biggest single
improvement, and the one that touches the hardware.

## 4. Scenarios

Each is short, safe, and exists to give one check its input. The app shows the prompt, watches for the
condition, and ticks it off. Nothing here asks for hard braking, speed or anything a passenger-less driver
shouldn't do while driving; stopped scenarios can be done in a parking spot.

| # | Prompt | Gives | Check it feeds |
|---|---|---|---|
| 1 | Engine on, stopped, 20 s | idle rpm band, stopped baseline for speed | rpm (idle), speed (zero), INCONCLUSIVE otherwise |
| 2 | Drive straight, 30–60 s at about the same speed, twice at different speeds | wheel/vehicle speed scale | speed vs GPS |
| 3 | Pull away gently and let it shift up through the gears | gear, rpm per gear | gear, rpm-by-gear |
| 4 | Press the brake at rest, release (parked, engine on) | brake on/off | candidate only |
| 5 | Press the accelerator at rest, rev gently | throttle, rpm response | candidate only (range and movement) |
| 6 | Steer lock to lock, parked | steering angle | candidate only |

- Brake pressure and pedal scales are not set from the driver's habits. Whether a range is "hard" differs by owner
  and pads, so the app records ranges and does not judge them.
- A scenario that cannot be done (no gear to shift, no GPS fix) is skipped and recorded as not tested.

## 5. Output: a temporary DBC and a comparison

- The result is a DBC in the format of `can_data/DBC_STANDARD.md`: one tag per signal, `[verified]` only where
  a check passed against an independent reference, `[candidate]` otherwise, owner statements marked.
- It is a candidate until someone reviews it. It never replaces a hand-built DBC, and the registry is not
  written until the owner saves it.
- "Compare against the real vehicle values" means, per role: the decoded signal and its reference on one chart
  (speed vs GPS speed, rpm vs OBD rpm when available), with the fitted scale and r.

## 6. Where the analysis runs

⚠ Open. The checker is Python and the app is Kotlin; CAN decoding today happens on the server (`can-decode`).

- **Server:** the frames already arrive there. The app asks the discovery service for the checks and shows the
  results. Nothing to port; the cost is a round trip, so feedback lags the 7 s the app already runs behind live.
- **Phone:** immediate feedback while the driver is at the scenario. Needs a DBC decoder and the checks in
  Kotlin. A decoder exists in the phone companion app (`data/decoder`); whether CANStream can reuse it is
  **⚠ unverified**.

Recommendation: server first. A guided session is a few minutes, a lag of seconds is fine, and the checks stay
in one place that is already tested.

## 7. What it must not do

- Ask for anything dangerous, or to be done while driving alone.
- Transmit onto a bus unless the owner turned it on, and only a read-only set of requests (see §8).
- Overwrite a DBC or a registry entry without the owner's confirmation.
- Report a PASS the data did not earn: a check that cannot run is INCONCLUSIVE, shown as "not tested".
- Treat one drive as proof across cars; the checker's thresholds are calibrated on three vehicles.

## 8. Decisions and open questions

1. **Transmit.** OBD-II requests need the firmware to transmit. That means a new command, a mode that is off by
   default, and a bounded request set (read-only Mode 01 and Mode 09 only). Cars from 2018 on may sit behind a
   security gateway on the OBD port. Whether the EcoSport does is **⚠ unverified**. Decide whether to do it,
   and test on one car with the bench setup before any friend gets a dongle that transmits.
2. **Old cars.** The Grand Marquis gave no CAN frames at all (the cause is unconfirmed; J1850 is suspected).
   The mode needs a "no CAN here" outcome that says so, not a failed scenario.
3. **Where it runs** (§6).
4. **Who gets the result.** Friends' sessions land on this cluster under their own sign-in; a saved candidate DBC
   is theirs until they choose to share it.
5. **Vehicle ID order.** Broadcast VIN, else OBD-II VIN, else the closest-DBC fingerprint, else ask. Where the
   VIN decodes, which DBC to try is a registry lookup that does not exist yet.

## 9. Order of work

1. ✅ Built 2026-11-27: `can_data/tools/scenarios.json` and `dbc_check.py --scenarios` (unit tests; run on four
   logs). Detected from GPS speed only: idle, steady speed (straightness is not checked) and pull-away. The three
   parked actions need something that marks them (an app prompt). Verdicts cover the whole log, not only the
   scenario's seconds; the detection thresholds are first guesses.
2. Vehicle ID from the VIN where the car broadcasts it, on the logs we already have.
3. Decide the transmit question (§8.1) and, if yes, prototype Mode 09 VIN and Mode 01 rpm on the bench.
4. App screens: prompt, progress, result.
5. Candidate DBC output and the save-to-registry step.

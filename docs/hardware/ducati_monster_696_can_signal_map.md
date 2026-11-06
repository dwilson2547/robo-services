# Ducati Monster 696 (2013) CAN Signal Map

Reverse-engineered from our own captures; there is no DBC yet. The bike is the test bed for
DBC discovery (`services/can_discovery`) while it is the only unmapped vehicle, so the DBC is
deliberately not written yet. Registry: vehicle 3, `dbc_id` empty, so can-decode routes its CAN to
discovery.

Status per row: **verified** = matched against a known event in a capture; **⚠ unverified** =
hypothesis from the data shape only.

## Capture 1: stationary, garage (2026-11-06)

- Session 5668, `1793985732461:phone-e17152a900ca4dfd`. 17:22:12 to 17:23:40 UTC, 88 s, 71,289
  frames, 12 ids, nothing dropped.
- Raw frames: `race-logger` bucket,
  `raw-telemetry/topic=telemetry.raw.can/source_session=1793985732461/`.
- What happened (user's account): not moving and never left neutral. Idled around 2k rpm, one short
  rev to about 5k near the end. Turn signals flipped. Mode button pressed about 12 times.
- Discovery's only candidate (0x300 vs GPS speed, r 0.85 at the +2 s lag limit) is GPS drift in
  the garage, not a signal. A stationary capture gives discovery's references (GPS speed, yaw)
  nothing to correlate against.
- Times below are seconds from the first frame.

## Signals

| ID | Rate | Bytes / bits | Signal | Scaling | Status |
|---|---|---|---|---|---|
| `0x100` | 50 Hz | bytes 3–4, big-endian, unsigned | Engine RPM | ×1 | **verified**: ~1,900–2,100 at idle; 3,838 at t=68 s and 5,208 at t=71 s during the rev; ~1,300 from t=76 s; 0 at engine-off (t=84 s) |
| `0x100` | 50 Hz | byte 7 | Engine running | 1 = running | **verified** (1 throughout, 0 at engine-off) |
| `0x100` | 50 Hz | byte 5 | Unknown, constant 35 while running, 0 at engine-off | — | ⚠ unverified |
| `0x080` | 200 Hz | byte 4 (even values 0–14) | Throttle opening, coarse; likely bits 1–3 of a wider field | unknown | **verified as throttle-driven**: steps up before each RPM rise (t=68.08 s, t=70.7 s) and peaks at 14 on the bigger rev; 2 at idle, settling 2→0 at t=76 s as the idle drops to ~1,300. Whether it is the grip, the throttle body or the idle valve is ⚠ unverified |
| `0x081` | 200 Hz | byte 0 | Same value as `0x080` byte 4 | as above | **verified** (tracks it frame for frame) |
| `0x081` | 200 Hz | byte 1 | Engine running | 1 = running | **verified** (0 at engine-off) |
| `0x211` | 10 Hz | byte 0 | An engine temperature: climbs 69 → 85 steadily over the 88 s at idle | unknown | ⚠ unverified. The bike is air-cooled (no coolant), but it has an overheat light, so something measures temperature. Possibly head or oil temperature |
| `0x211` | 10 Hz | byte 1 | ~156–166 while running, 137–146 after engine-off | unknown | ⚠ unverified; charging voltage is a guess |
| `0x211` | 10 Hz | bytes 2–4 | Constant `00 34 d0` | — | — |
| `0x020` | 50 Hz | bytes 2–4, big-endian | Constant `0x004FA1` = 20,385; possibly the odometer | — | ⚠ unverified (not compared with the dash) |
| `0x280` | 10 Hz | all 8 bytes | ASCII `" M 696 A"` | text | **verified** (decodes as text) |
| `0x290` | 10 Hz | all 8 bytes | ASCII `"BS DP  "` + `0x60`; with `0x280`, reads "M 696 ABS DP" | text | **verified** (decodes as text) |
| `0x300` | 10 Hz | bytes 0–1 | Only 4 changes: t=6.3 s (byte 0 bit 0 set, byte 1 `C0`→`00`), t=25.7 s (byte 1 bit 1 for one frame), t=66.3 s (byte 1 bit 6 set) | — | ⚠ unverified. **Not the mode button**: 3 events against ~12 presses |
| `0x010`, `0x028`, `0x029`, `0x201` | 200 / 50 / 10 / 10 Hz | — | All zero throughout | — | — |

### Not on the bus (from this capture)

- **Turn signals.** No bit in any id toggles at blinker rate. The signals are presumably wired
  directly, not over CAN.
- **Mode button.** About 12 presses produced no matching events. It is presumably handled inside
  the dash.
- **Speed and gear** were not exercised (stationary, neutral). Expect them in a moving capture,
  likely in a varying id not yet seen here or in one of the all-zero ids.

## Next capture

A short ride, moving through several gears, so discovery has GPS speed and yaw to correlate
against. Watch for:
- the all-zero ids starting to move (speed, gear);
- `0x211` byte 0 against the overheat light or an IR thermometer on the head;
- `0x020` against the dash odometer.

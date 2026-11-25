# DBC standard (draft)

Status: draft for the owner's review, written 2026-11-25 from three hand-built files: `dbc/custom/chevy_impala_2008_hscan.dbc`,
`ford_ecosport_2018_hscan.dbc` and `ford_f150_2012_hscan.dbc`. It is meant to hold for any make. Where the Fords and the Impala
differ, the rule below says which way it goes and why; nothing here depends on a Ford naming scheme.
Open decisions are listed at the end.

## 1. What a DBC here is

The ground truth the decode pipeline and the checker (`tools/dbc_check.py`) are measured against. One DBC per vehicle
configuration and bus. Hand-built DBCs live in `dbc/custom/`; `dbc/opendbc/` is vendored and read-only.

A DBC records what was *checked*, not what a similar car's DBC says. Provenance rules follow the workspace's (CONVENTIONS §10):
every signal is `[verified]`, `[candidate]` or `[unverified]`, and nothing is upgraded without a measurement or the owner's word.

## 2. Scope: what we decode

The pipeline needs these roles, whatever a vehicle's DBC calls them (`docs/plans/2026-11-03-trip-pipelines.md` §2, planned):
speed, rpm, throttle, brake on, brake pressure, gear, steering angle, odometer, coolant temperature, oil temperature.
A DBC covers a role if it has a signal for it. A signal outside these roles goes in only if a consumer needs it, and then
with the same evidence tag as the rest. A missing role is fine: unmapped roles stay empty downstream.

Which role uses which signal is chosen in the registry, not in the DBC (`dbc.roles`). A DBC may therefore hold several signals
for one role (EcoSport and F-150 each carry up to four speed sources, and two rpm sources on the EcoSport). The DBC never marks
one as primary.

| Role | 2018 EcoSport | 2012 F-150 | 2008 Impala |
|---|---|---|---|
| speed | verified: wheels `0x217`, `0x415`, `0x202`, `0x77` | verified: `0x201`, wheels `0x215`, `0x415` | verified: `0x3E9` (reads 4.8 % above GPS), wheels `0x348`/`0x34A` (about 4.2 % above) |
| rpm | verified `0x204`; alternate `0x42F` | verified `0x201` | candidate `0xC9` (idle 694 rpm; per-gear medians fall but overlap) |
| throttle | candidate (`0x204`) | candidate (`0x201`) | candidate `0xC9` byte 4 (`AcceleratorPedalPos`) |
| gear | verified 1-6, `0x171` | verified 1-4, 5-6 thin, `0x151` | verified `0x1F5` `CurrentGear`, 1-4 (14 reverse, 15 park); `PRNDL` lever position verified |
| brake on / pressure | not examined | not examined | on: verified (`0xC9`, `0xF1`); pressure: candidate (`0xF1`) |
| steering, coolant, oil temp | not examined | not examined | coolant verified (`0x4C1`); steering and oil temperature unverified (neither follows its reference); odometer verified (`0x120`) |

Brake ranges are not a rule. How hard a driver brakes, and with what pads, differs by owner and vehicle, so no brake-percent
threshold belongs in a checker or in this standard. A vehicle's own observed range goes in that vehicle's notes if wanted.

## 3. Files and names

- File: `dbc/custom/<make>_<model>_<year>_<bus>.dbc`, lower case. The bus is the physical network the data was read from
  (`hscan`, `mscan`, ...), not which module group the messages belong to. opendbc's `pt` means powertrain messages and is
  not used here; our Ford files are `hscan` because the dongle reads the HS-CAN pins on the OBD port.
- Each file carries a general comment (`CM_ "..."`) first: vehicle (with whether the facts are owner-stated or VIN-read), bus,
  source DBC it was built from if any, the evidence set (drives, dates, the speed reference used), and the tag meanings.
- A vehicle with notes gets `analysis/<make>/<model>_<year>.md`; the DBC points at evidence, the note holds the story.

## 4. Signal rules

1. **Evidence to enter.** A signal goes in only with a tag. Messages the log shows but nobody has checked stay in the analysis
   note, not the DBC. A signal carried over from an older file may stay as `[unverified]` while nothing disproves it; one the data
   disproves leaves the DBC and goes in the note with the evidence. (The Fords follow this from the start. The Impala's can_data copy was rebuilt on 2026-11-25 from the maintained DBC: 55 tagged
   signals, 8 left out because the data disproves them or they repeat a name.)
2. **Names are unique within a DBC.** The planned state service stores decoded values as `{name: value}` per DBC
   (`trip-pipelines.md` §3), so two signals with one name collide. The maintained Impala DBC repeats `RollingCounter` in three
   messages; the can_data copy drops them. Prefix with the message when a name repeats
   (`ECMEngineStatus_RollingCounter`). No placeholder names (the maintained DBC has `Unknown_MAP`); an unnamed signal waits in the note.
3. **Keep source names when building from another DBC** (opendbc names such as `EngAout_N_Actl`), so each signal traces back.
   A hand-discovered signal gets a plain descriptive CamelCase name. Neither style is preferred; mixing within one file is fine.
4. **Layout and scale are the checked ones.** Copy a layout from a neighbour platform's DBC and then test it. Same-named
   signals differ between related DBCs: the 2011 `WheelSpeed_CG1` fields start at bit 55 and the 2018 base DBC's at bit 54.
   Record the fitted scale next to the declared one.
5. **A declared unit must be true.** Test it: the signal's ratio to GPS should be constant across speeds and agree with the other
   speed signals. The Impala's `VehicleSpeed` looked wrong (fitted 1.533 km/h per declared mph, mph would give 1.609) but the
   ratio is a flat 0.952 from 20 to 100 km/h and it agrees with the wheel speeds to 1.2 %, so the unit is right and the signal
   reads 4.8 % above GPS like a speedometer. Record such an offset in the comment; do not rescale the DBC to hide it.
   Keep the native unit in the DBC and let the registry role convert (its role mapping already takes `scale`/`unit`).
   Spell units so the tools recognise them where a role check exists: speed as `km/h`, `kph`, `mph`, `m/s` or `rad/s`; rpm as `rpm`
   (any case); throttle as `%`. Other units have no check yet; use a plain SI-style spelling (`kPa`, `C`).
6. **Byte order per signal, as found.** Intel (`@1`) and Motorola (`@0`) both occur: the Impala mixes them (55 Intel, 16 Motorola);
   the Fords are all Motorola. Do not convert. Say in the general comment which appears.
7. **Counters, checksums and quality flags** stay out of the verified set unless a consumer needs them. When present,
   name them after their signal (`VehVActlBrk_No_Cnt`, `_No_Cs`, `_D_Qf`) so they stay unique.
8. **Text broadcast across bytes (VIN, calibration ID)** is an open decision (below). The Impala splits each into 8 signals.

## 5. Comments and tags

- **Per message** (`CM_ BO_`): observed rate in Hz and the sender, as in the Impala (`"... 80 Hz."`). The new Fords lack this; the
  checker already measures it (`Logs.rate`). A message comment describes that message: the Impala's `CM_ BO_ 1265` says
  "Engine oil and ambient temperatures" but `1265` is `FuelRailPressure` (the temperatures are `1233`, which has no comment).
- **Per signal** (`CM_ SG_`): `[verified]`, `[candidate]` or `[unverified]`, then the method and its numbers, then what is not covered.
  - `[verified]`: checked against an independent reference. Speed against GPS (r, fitted scale, rolling radius for wheels), rpm
    against idle and per-gear rpm/speed, gear against speed. Say which.
  - `[candidate]`: plausible, but nothing ties it to the physical quantity. Throttle is this today: the check sees a 0-100 %
    range and movement only.
  - `[unverified]`: carried over from someone else's DBC or a guess. Words like "likely" or "probably" in a comment are
    this tag written badly (the Impala's TPMS comments and its `Unknown_MAP`); use the tag.
- Owner statements are marked `owner-stated` with what was said (for example, the EcoSport's second gear is only touched
  briefly between first and third).
- Value tables (`VAL_`) list only values seen. A value copied from a source table without being seen is left out.

## 6. Checking a DBC

1. It parses (`tools/dbc_check.py` loads every file in `dbc/`).
2. Each `[verified]` speed, rpm and gear signal passes its role check on the log it cites.
3. Re-run after any edit. A role check failing on new data downgrades the tag; it does not get argued away.

A check passing is not a proof: the pedal check, for example, cannot tell a throttle from any other 0-100 % signal.

## 7. How the three files measure up

| Rule | EcoSport | F-150 | Impala (after the 2026-11-25 re-check) |
|---|---|---|---|
| Tagged signals only (§4.1) | yes | yes | yes: 55 tagged, 8 maintained-DBC signals left out |
| Unique names (§4.2) | yes | yes | yes |
| Unit is true (§4.5) | yes for the checked ones | yes for the checked ones | yes: speed is mph, reads 4.8 % above GPS; the oil temperature unit does not hold on the 2026-11-24 drives |
| Message rates and senders (§5) | no rates | no rates | yes, measured 2026-11-24 |
| Evidence in comments (§5) | yes | yes | yes |
| File bus label (§3) | `hscan` | `hscan` | `hscan` |

## Open decisions (the owner's)

1. ~~File naming~~ settled 2026-11-25: the owner chose `hscan`, the bus the data comes from; the two Ford files were renamed.
2. ~~Impala clean-up~~ done 2026-11-25 (`analysis/gm/chevy_impala_2008.md`, last section), after a first pass that audited a stale copy.
   **Which Impala file is the source of truth:** the maintained one is `embedded/phone-dbc-repo/dbcs/` (the registry holds an
   identical copy); `can_data/dbc/custom/` now holds a re-checked, tagged version of it. Two files will drift unless one is chosen.
   Not decided; the registry was only read, nothing was written to it.
3. **Text fields:** VIN and calibration ID as 8-signal blocks in the DBC, or decoded outside DBC (the pipeline already receives
   raw frames).
4. **A slim view:** the idea was a smaller DBC with only the signals used for the pipeline. With roles chosen in the registry,
   the full verified DBC can serve both; a slim file is only worth it if a viewer needs one. Not built.
5. **Rates:** add them to the two Ford files now, or only in new files.

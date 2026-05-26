Good news and a caveat upfront: the 2008 Impala is GM GlobalA architecture, which is the best-documented GM platform in the community. The core HS-CAN signals are well established. The chassis signals (steering angle, yaw) are less settled because they live on a separate expansion bus segment that fewer people have sniffed.

---

**What's confirmed across multiple community sources:**

Engine RPM is in `0x0C9` bytes 1-2 as a 16-bit big-endian value. Formula: `(byte1 * 256 + byte2) * 0.25` → rpm. That same message carries throttle pedal position in byte 4 (`byte4 / 2.55` → percent) and brake pedal state in byte 5 (bit flip between on/off). The packet name in GM's internal spec is "PPEI General Engine Status 1."

Vehicle speed is in `0x3E9` bytes 0-1, also big-endian. Formula: `(byte0 * 256 + byte1) * 0.01` → mph.

Coolant temp and intake air temp are in `0x4C1` — coolant at byte 2 (`A - 40` → °C), IAT at byte 3 (same formula), outside ambient at byte 4 (`A/2 - 40` → °C). Engine oil temp is in `0x4D1` byte 2 with the same `A - 40` formula.

MAF in grams/second is at `0x1EF` bytes 2-3: `(byte2 * 256 + byte3) / 100`. Transmission gear is a single byte at `0x135` byte 0.

One thing worth knowing: `0x0C9` started as a 6-byte packet in earlier GM GlobalA platforms and grew to 8 bytes. The 2008 model year is in the 8-byte era, so make sure your parser expects DLC=8 on that ID.

---

**Wheel speeds** — `0x348` carries the rear wheel speeds, `0x34A` the fronts (some sources swap these — verify on first capture). Same scaling as vehicle speed, individual wheel channels packed as 16-bit values.

---

**Steering angle and yaw rate — lower confidence:**

On the 2008 Impala, the EBCM, yaw rate sensor, and steering wheel angle sensor share a dedicated chassis expansion CAN segment (circuits 6432/6433), separate from the main HS-CAN. This bus is accessible from the OBD-II port on pin 1 (GMLAN single-wire) or via the chassis harness directly. The steering angle and yaw rate signals do get broadcast onto HS-CAN for consumption by other modules (ESC, instrument cluster), but the specific IDs vary more between model years than the powertrain signals.

The opendbc `gm_global_a_chassis.dbc` file is the best starting point — your agents should fetch the raw content of that file directly from GitHub. It has steering angle around `0x14A` or `0x15A` and yaw rate around `0x174`–`0x178` depending on the specific revision, but I'd treat those as starting hypotheses to validate against a live capture rather than ground truth.

---

**Primary resources for your agents to pull:**

The two files your agents should fetch via Playwright are the raw DBC contents from:

- `https://github.com/commaai/opendbc/blob/master/opendbc/dbc/gm_global_a_highspeed.dbc` — powertrain signals, all the IDs above
- `https://github.com/commaai/opendbc/blob/master/opendbc/dbc/gm_global_a_chassis.dbc` — steering angle, yaw, wheel speeds, EBCM signals

The raw view (raw.githubusercontent.com path) will give the actual DBC text. The GMW8762 document is GM's internal GMLAN specification that defines all of this authoritatively, but it's not publicly available — the opendbc files are community reverse-engineered from that spec and are the closest public equivalent.

One practical note: on first capture session with your ESP32, log everything and run the data through SavvyCAN or cabana (comma.ai's web DBC tool) before committing to the signal map. The IDs above are correct for GlobalA generally but a first-principles verification against your specific car is worth 20 minutes in the driveway.
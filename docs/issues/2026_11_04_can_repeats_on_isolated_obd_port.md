# CAN frames repeated back-to-back on a car whose OBD port has no other listener

Date: 2026-11-04. Status: root cause reproduced on the bench; fixed in dongle firmware 1.7.0, verified on the bench. Not yet run on a car.

## Symptom

Two drives in a 2024 Chevy Blazer (sessions `1793812262671`, `1793814706312`), recorded
through the listen-only dongle, gave DBC discovery nothing usable:
- 83% of the CAN frames (trip back: 372,550 frames, 62,571 unique) were copies of the frame
  just before: same id, data and millisecond, 4-10 per millisecond, median 6 copies;
- unique frames came at 150-250 a second, against about 725 on the Impala with the same ids;
- most seconds had no frames for a given id (wheel speed 0x348 was present in 8 of 420 s),
  so the correlator had nothing continuous to match against GPS.

The copies are in the phone's own frame log, so the uploads and catch-up didn't make them.
Every second reached the cluster once.

## Root cause

A CAN sender resends a frame until another node acknowledges it. The dongle runs
`TWAI_MODE_LISTEN_ONLY` and never acknowledges. On the Impala the OBD port shares a bus with
modules that do. On the Blazer, nothing on the port's segment acknowledged (⚠ unverified why;
likely a gateway in front of an otherwise empty segment). So every frame was resent and the
dongle recorded each copy. The resends also filled the bus time that other frames needed.

## Reproduction (✅ 2026-11-04)

The can-simulator (ESP32-S3 with a TJA1051, with the dongle plugged straight into its OBD
port) normally runs `TWAI_MODE_NO_ACK`, which counts a frame as sent without an
acknowledgement. Its `xiao_s3_ack` build requires acknowledgements like a real ECU. The
same replay with that build (session `1793844480724`, 95 s) gave:
- 85% copies, median 6-7 copies, all back-to-back;
- about 370 unique frames a second.

The earlier `NO_ACK` sim drives had no copies. Analysis scripts:
`docs/issues/scripts/` (`bin_dups.py`, `bin_order.py`, `bin_copies_time.py`) read a phone `session-<id>.bin` (args: the file, then a directory holding `telemetry_pb2.py` generated from `telemetry.proto`; `bin_copies_time.py` takes a dummy first arg, then that directory, then files).

## Fix (✅ bench, 2026-11-04)

Dongle firmware 1.7.0 (`embedded/dbc-forge/firmware/esp32_can_ble_bridge`) acknowledges
frames once the bus is proven: it starts listen-only, and after 200 valid frames with fewer
than one bus error per 10 it switches the TWAI controller to normal. It never transmits a
frame of its own. Errors in ack mode drop it back to listen until the next connect. Same
bench setup as the reproduction (session `1793845549320`, 150 s): 0 copies (69,411 frames,
all unique), about 463 unique frames a second, and every STAT window `ack`, 0 repeats,
0 bus errors.

Found on the way: the firmware drove the TJA1051 S pin backwards. Per NXP's datasheet
(Rev. 9, 7.1) S LOW is Normal and S HIGH is Silent, with the transmitter off. The old
`TX_ENABLE` would have silenced the transceiver, so no ACK could have reached the bus.

## Still open

- Phone-side filter: drop a frame identical (id, data, ms) to the one just before, for
  older firmware and any port where ack mode falls back.

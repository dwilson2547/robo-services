# CAN Bus Data Repository

Vehicle CAN bus captures, analysis, and DBC signal definitions for robo-services.

## Directory Structure

```
can_data/
├── dbc/
│   ├── opendbc/          # Vendored DBC files from comma.ai opendbc
│   └── custom/           # Our own DBC files
├── captures/
│   ├── gm/               # GM/Chevrolet captures
│   │   └── <model>/
│   │       └── <date>_<session>/
│   │           ├── raw/          # Raw capture files
│   │           └── session.md    # Capture notes
│   └── ford/             # Ford captures
├── tools/
│   └── dbc_check.py      # does a known DBC fit this vehicle? (see below)
├── analysis/
│   ├── gm/               # GM decode analysis documents
│   └── ford/             # Ford decode analysis documents
└── README.md
```

## Vehicle Coverage

| Make | Model | Year | Bus | Status | DBC | Signals |
|------|-------|------|-----|--------|-----|---------|
| Chevrolet | Impala | 2008 | HS-CAN | Active | `custom/chevy_impala_2008_hscan.dbc` | 17 high-confidence |
| Ford | - | - | - | Pending | See opendbc | - |

## DBC Files

### Custom (Our Decodes)

| File | Vehicle | Signals | Notes |
|------|---------|---------|-------|
| `chevy_impala_2008_hscan.dbc` | 2008 Chevy Impala | 40+ | HS-CAN 500kbps, stationary captures |

### OpenDBC (Vendored)

**Ford:**
- `ford_fusion_2018_pt.dbc` - 2018 Fusion powertrain
- `ford_fusion_2018_adas.dbc` - 2018 Fusion ADAS
- `ford_cgea1_2_ptcan_2011.dbc` - 2011 CGEA platform powertrain
- `ford_cgea1_2_bodycan_2011.dbc` - 2011 CGEA platform body
- `ford_lincoln_base_pt.dbc` - Lincoln base powertrain
- `FORD_CADS.dbc` / `FORD_CADS_64.dbc` - Ford CADS

**GM:**
- `gm_global_a_powertrain_expansion.dbc` - Global A powertrain (2016+)
- `gm_global_a_chassis.dbc` - Global A chassis
- `gm_global_a_lowspeed.dbc` - Global A low-speed CAN
- `gm_global_a_high_voltage_management.dbc` - EV/hybrid HV systems
- `gm_global_a_object.dbc` - Radar/object detection

Source: [commaai/opendbc](https://github.com/commaai/opendbc)

## Checking a new vehicle against the library

`tools/dbc_check.py` answers "does a DBC we already have fit this car?" before any discovery work.
It reads CANStream frame logs (the phone's `session-*.bin`), ranks every DBC in `dbc/` by how much of
the observed traffic it defines, then decodes the speed, rpm and pedal signals of the best fits and
tests them against physics with GPS speed as the independent reference. Standard library only,
read-only, no network.

```bash
python3 tools/dbc_check.py session-A.bin session-B.bin [--top 3] [--json]
python3 tools/test_dbc_check.py            # unit tests
```

Each check is PASS / PLAUSIBLE / FAIL / INCONCLUSIVE with its numbers; INCONCLUSIVE means the log lacks
what the check needs (for example no stopped seconds) and is never counted as a pass. What was tested
against real drives (2026-11-24, unless noted "unverified"):

| Vehicle | Best DBC | Fingerprint | speed | rpm | pedal |
|---|---|---|---|---|---|
| 2018 EcoSport (4 drives, 23 min) | `ford_lincoln_base_pt.dbc` | 76.8 % of traffic, 63/86 ids | PASS, r=0.993 (wheel speed `0x217`); `0x415` vehicle speed also r=0.993 via `ford_cgea1_2_ptcan_2011.dbc` | PASS `0x204` EngAout_N_Actl, idle 950 rpm, 4 gear-like levels | PASS (range and movement only) |
| 2008 Impala (3 drives, 30 min) | `custom/chevy_impala_2008_hscan.dbc` | 92.0 %, 21/23 ids | PASS, r=0.997 | PLAUSIBLE: idle 788 rpm, but no gear structure (torque-converter slip or 1 Hz GPS) | no pedal signal in DBC |

Limits worth knowing: the checks are calibrated on those two vehicles; the gear-structure threshold
(`CONCENTRATION_MIN`, 0.6) separated them from constant decoy signals but is not tuned beyond that; the
pedal check cannot tell a throttle pedal from any other 0-100 % signal; GPS speed is the phone's ~1 Hz
location unless the dongle's receiver was fixing. The Impala's fitted speed scale (1.533 km/h per raw
unit for `VehicleSpeed`) is not an obvious unit, so that DBC's speed scale is worth a look (unverified).

## Capture Sessions

### GM / Chevrolet Impala 2008

| Session | Date | State | Captures | Key Findings |
|---------|------|-------|----------|--------------|
| 2026-05-31_stationary | 2026-05-31 | Idle | 7 files | 50 IDs, RPM/gear/brake/temps confirmed |

## Tools

### Reading DBC files

```python
import cantools
db = cantools.database.load_file('can_data/dbc/custom/chevy_impala_2008_hscan.dbc')
msg = db.get_message_by_name('ECMEngineStatus')
print(msg.decode(bytes.fromhex('840A650000400')))
```

### Parsing captures

```python
import re

def parse_capture(filepath):
    pattern = r'\[(\d+)\] STD 0x([0-9A-F]+) DLC=(\d) DATA: (.*)'
    with open(filepath) as f:
        for line in f:
            m = re.match(pattern, line)
            if m:
                ts, can_id, dlc, data = m.groups()
                yield int(ts), int(can_id, 16), bytes.fromhex(data.replace(' ', ''))
```

## Adding New Vehicles

1. Create capture directory: `captures/<make>/<model>/YYYY-MM-DD_<session>/raw/`
2. Add `session.md` with capture conditions
3. Analyze and document in `analysis/<make>/<model>.md`
4. Create/update DBC in `dbc/custom/<make>_<model>_<year>_<bus>.dbc`
5. Update this README

## References

- [commaai/opendbc](https://github.com/commaai/opendbc) - Open vehicle DBC database
- [cantools](https://github.com/cantools/cantools) - Python CAN tools
- [DBC file format](https://www.csselectronics.com/pages/can-dbc-file-database-intro) - DBC specification

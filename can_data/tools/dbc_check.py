#!/usr/bin/env python3
"""Does a known DBC fit this vehicle? A cheap check before running discovery.

Reads CANStream frame logs (the phone's session-*.bin: [u32 BE length][TelemetryFrame]),
then, for every DBC in the library:

  1. fingerprint  how much of the observed CAN traffic (weighted by log message count)
                  the DBC defines.
  2. validate     for the best-fitting DBCs, decode the signals that play a role
                  (speed, rpm, pedal) and test them against physics, with GPS speed
                  as the independent reference.

Each check answers PASS / PLAUSIBLE / FAIL / INCONCLUSIVE with its numbers. PLAUSIBLE (rpm only) means sane idle,
range and movement but no gear structure to confirm it. INCONCLUSIVE means the
log lacks what the check needs (no stopped seconds, no moving seconds); it is never
rounded up to PASS. Standard library only; read-only; touches no network.

    dbc_check.py session-1.bin session-2.bin [--dbc-dir DIR ...] [--top 3] [--json]

The checks are the executable form of the rules in the CAN decoding notes:
wheel speed tracks GPS speed; rpm has a sane idle and is proportional to speed within
a gear; pedal position stays in 0-100 % and moves.
"""
import argparse
import collections
import glob
import json
import math
import os
import re
import statistics as st
import struct
import sys

# ---------------------------------------------------------------- frame logs

def _varint(b, i):
    r = s = 0
    while True:
        c = b[i]
        i += 1
        r |= (c & 0x7F) << s
        s += 7
        if not c & 0x80:
            return r, i


def _fields(b):
    i = 0
    while i < len(b):
        k, i = _varint(b, i)
        f, w = k >> 3, k & 7
        if w == 0:
            v, i = _varint(b, i)
        elif w == 2:
            n, i = _varint(b, i)
            v = b[i:i + n]
            i += n
        elif w == 1:
            v = b[i:i + 8]
            i += 8
        elif w == 5:
            v = b[i:i + 4]
            i += 4
        else:
            raise ValueError(f"wire type {w}")
        yield f, v


def _packed(b):
    i, out = 0, []
    while i < len(b):
        v, i = _varint(b, i)
        out.append(v)
    return out


def read_frames(path):
    """Yield each serialized TelemetryFrame; a truncated final record is ignored."""
    with open(path, "rb") as fh:
        while True:
            head = fh.read(4)
            if len(head) < 4:
                return
            n = struct.unpack(">I", head)[0]
            body = fh.read(n)
            if len(body) < n:
                return
            yield body


def _gps_speeds(section, base):
    """(t_us, km/h) per fix of a GpsSection (phone location or dongle receiver)."""
    per, dts, spd = 0, [], []
    for f, v in _fields(section):
        if f == 2:
            per = v
        elif f == 30:
            dts = _packed(v)
        elif f == 13:
            spd = _packed(v)
    return [(base + (dts[i] if dts else i * per), s / 100 * 3.6) for i, s in enumerate(spd)]


def _can_messages(section, base):
    for g, m in _fields(section):
        if g != 1:
            continue
        dt = cid = 0
        data = b""
        for h, x in _fields(m):
            if h == 1:
                dt = x
            elif h == 2:
                cid = x
            elif h == 3:
                data = x
        yield base + dt, cid & 0x1FFFFFFF, data


class Logs:
    """What the checker needs from the logs: ID counts, a GPS speed reference, raw data."""

    def __init__(self, paths):
        self.paths = paths
        self.counts = collections.Counter()
        self.seconds = 0.0
        self.t0 = {}
        gps_phone, gps_dongle = {}, {}
        for si, p in enumerate(paths):
            first = last = None
            for d in read_frames(p):
                base = None
                secs = []
                for f, v in _fields(d):
                    if f == 3:
                        base = v
                    elif f in (11, 12, 13):
                        secs.append((f, v))
                if base is None:
                    continue
                first = base if first is None else min(first, base)
                last = base if last is None else max(last, base)
                self.t0.setdefault(si, base)
                self.t0[si] = min(self.t0[si], base)
                for f, v in secs:
                    if f == 12:
                        for _, cid, _ in _can_messages(v, base):
                            self.counts[cid] += 1
            if first is not None:
                self.seconds += (last - first) / 1e6 + 1
        # second pass for GPS needs t0 known
        for si, p in enumerate(paths):
            for d in read_frames(p):
                base = None
                secs = []
                for f, v in _fields(d):
                    if f == 3:
                        base = v
                    elif f in (11, 13):
                        secs.append((f, v))
                for f, v in secs:
                    target = gps_phone if f == 11 else gps_dongle
                    for t, kmh in _gps_speeds(v, base):
                        target.setdefault((si, int((t - self.t0[si]) / 1e6)), []).append(kmh)
        gps = gps_dongle if gps_dongle else gps_phone
        self.gps_source = "dongle_gps" if gps_dongle else ("phone gps" if gps_phone else "none")
        self.gps = {k: sum(v) / len(v) for k, v in gps.items()}

    def rate(self, cid):
        return self.counts[cid] / self.seconds if self.seconds else 0.0

    def collect(self, wanted):
        """{id: [(session, second, bytes)]} for the wanted IDs, thinned to <=20 per second."""
        out = collections.defaultdict(list)
        seen = collections.Counter()
        for si, p in enumerate(self.paths):
            for d in read_frames(p):
                base = None
                cans = []
                for f, v in _fields(d):
                    if f == 3:
                        base = v
                    elif f == 12:
                        cans.append(v)
                for v in cans:
                    for t, cid, data in _can_messages(v, base):
                        if cid not in wanted:
                            continue
                        sec = int((t - self.t0[si]) / 1e6)
                        key = (cid, si, sec)
                        seen[key] += 1
                        if seen[key] <= 20:
                            out[cid].append((si, sec, data))
        return out

# ---------------------------------------------------------------------- DBCs

_BO = re.compile(r"^BO_\s+(\d+)\s+(\w+)\s*:\s*(\d+)")
_SG = re.compile(
    r'^\s*SG_\s+(\w+)\s*(M|m\d+M?)?\s*:\s*(\d+)\|(\d+)@([01])([+-])\s*'
    r'\(([^,]+),([^)]+)\)\s*\[([^|]*)\|([^\]]*)\]\s*"([^"]*)"')


class Signal:
    def __init__(self, msg, name, start, length, little, signed, scale, offset, unit):
        self.msg, self.name, self.start, self.length = msg, name, start, length
        self.little, self.signed, self.scale, self.offset, self.unit = little, signed, scale, offset, unit

    def decode(self, data):
        if self.start // 8 >= len(data):
            return None
        try:
            if self.little:
                raw = 0
                for i in range(self.length):
                    k = self.start + i
                    raw |= ((data[k // 8] >> (k % 8)) & 1) << i
            else:
                raw = 0
                byte, bit = self.start // 8, self.start % 8
                for _ in range(self.length):
                    raw = (raw << 1) | ((data[byte] >> bit) & 1)
                    if bit == 0:
                        byte, bit = byte + 1, 7
                    else:
                        bit -= 1
        except IndexError:
            return None
        if self.signed and raw >= 1 << (self.length - 1):
            raw -= 1 << self.length
        return raw * self.scale + self.offset

    @property
    def label(self):
        return f"{self.msg}.{self.name}"


def parse_dbc(path):
    """{can_id: (message name, [Signal])}. Multiplexed signals are skipped."""
    msgs, cur, skipped = {}, None, 0
    with open(path, errors="ignore") as fh:
        for line in fh:
            m = _BO.match(line)
            if m:
                cid = int(m[1]) & 0x1FFFFFFF
                cur = (m[2], [])
                msgs[cid] = cur
                continue
            s = _SG.match(line)
            if s and cur is not None:
                if s[2]:
                    skipped += 1
                    continue
                cur[1].append(Signal(cur[0], s[1], int(s[3]), int(s[4]), s[5] == "1", s[6] == "-",
                                     float(s[7]), float(s[8]), s[11]))
    return msgs


def load_library(dirs):
    lib = {}
    for d in dirs:
        for p in sorted(glob.glob(os.path.join(d, "**", "*.dbc"), recursive=True)):
            try:
                msgs = parse_dbc(p)
            except (OSError, ValueError):
                continue
            if msgs:
                lib[os.path.relpath(p, os.path.dirname(d))] = msgs
    return lib

# --------------------------------------------------------------- fingerprint

def fingerprint(logs, lib):
    """Rank DBCs by the share of observed traffic (log-weighted by message count) they define."""
    weight = {cid: math.log1p(c) for cid, c in logs.counts.items()}
    total = sum(weight.values()) or 1.0
    rows = []
    for name, msgs in lib.items():
        hit = [cid for cid in logs.counts if cid in msgs]
        rows.append({
            "dbc": name,
            "score": sum(weight[c] for c in hit) / total,
            "ids_matched": len(hit),
            "ids_observed": len(logs.counts),
            "dbc_messages": len(msgs),
        })
    return sorted(rows, key=lambda r: -r["score"])

# ---------------------------------------------------------------- validators

def _per_second(samples):
    b = collections.defaultdict(list)
    for si, sec, v in samples:
        if v is not None:
            b[(si, sec)].append(v)
    return {k: sum(v) / len(v) for k, v in b.items()}


def _pearson(x, y):
    mx, my = st.mean(x), st.mean(y)
    num = sum((a - mx) * (b - my) for a, b in zip(x, y))
    den = (sum((a - mx) ** 2 for a in x) * sum((b - my) ** 2 for b in y)) ** 0.5
    return num / den if den else 0.0


def check_speed(sig, series, gps):
    keys = [k for k in series if k in gps]
    moving = [k for k in keys if gps[k] > 8]
    if len(moving) < 30:
        return "INCONCLUSIVE", f"only {len(moving)} s moving over 8 km/h in the log", {}
    r = _pearson([series[k] for k in keys], [gps[k] for k in keys])
    den = sum(series[k] ** 2 for k in moving)
    k_fit = sum(series[k] * gps[k] for k in moving) / den if den else float("nan")
    extra = f", fitted {k_fit:.3f} km/h per unit"
    if sig.unit.lower() in ("rad/s", "rads"):
        extra += f" (rolling radius {k_fit / 3.6:.3f} m)"
    status = "PASS" if r >= 0.9 else "FAIL"
    return status, f"r={r:.3f} against {{ref}} over {len(keys)} s{extra}", {"r": round(r, 4), "k": round(k_fit, 4)}


def _concentration(series, gps, top=6):
    """How much of the moving time rpm/speed spends in a few levels (one per gear).

    In a geared drivetrain rpm is proportional to speed within a gear, so rpm/speed sits
    at a handful of values. Returns (share of moving seconds in the `top` busiest levels,
    the level medians that held >=15 s). rpm need not correlate with speed overall: every
    upshift drops rpm while speed keeps rising.
    """
    bins = collections.defaultdict(list)
    for k, v in series.items():
        s = gps.get(k, 0)
        if s > 15 and v > 300:
            bins[round(math.log(v / s) / 0.04)].append(v / s)
    total = sum(len(v) for v in bins.values())
    if total < 30:
        return 0.0, []
    busiest = sorted(bins.values(), key=len, reverse=True)[:top]
    levels = sorted(st.median(v) for v in busiest if len(v) >= 15)
    merged = []
    for lv in levels:
        if not merged or lv / merged[-1] > 1.06:
            merged.append(lv)
    return sum(len(v) for v in busiest) / total, merged


CONCENTRATION_MIN = 0.6
GEAR_MIN_SECONDS = 10      # seconds a gear value needs before it is judged
GEAR_MIN_STEP_KMH = 3.0    # adjacent gear values must differ in median speed by this much
GEAR_SEPARATION = 1.15     # adjacent gears' median rpm/speed must differ by at least this factor


GEAR_MAX = 10   # forward gears are 1..GEAR_MAX; other values (park, reverse, neutral, unknown: 0, 14, 15 ...) are not gears


def _gear_values(series, gps):
    """{rounded forward-gear value: [(speed, key)]} over seconds with a speed reading."""
    by = collections.defaultdict(list)
    for k, v in series.items():
        if k in gps and 1 <= round(v) <= GEAR_MAX:
            by[round(v)].append((gps[k], k))
    return by


def check_gear(sig, series, gps):
    """A gear signal rises with speed: more gears reached as the car goes faster.

    Uses speed only, never rpm, so it can then be used to test rpm without circularity.
    """
    moving = [k for k in series if k in gps and gps[k] > 8]
    if len(moving) < 30:
        return "INCONCLUSIVE", f"only {len(moving)} s moving over 8 km/h in the log", {}
    if len({round(v) for v in series.values()}) > 16:
        return "FAIL", "too many distinct values for a gear", {}
    by = _gear_values(series, gps)
    kept = {g: st.median(s for s, _ in v) for g, v in by.items() if len(v) >= GEAR_MIN_SECONDS}
    if len(kept) < 3:
        return "INCONCLUSIVE", f"only {len(kept)} values held >={GEAR_MIN_SECONDS} s", {}
    order = sorted(kept)
    steps = [kept[b] - kept[a] for a, b in zip(order, order[1:])]
    if min(steps) < GEAR_MIN_STEP_KMH:
        return "FAIL", ("median speed does not rise with the value: "
                        + ", ".join(f"{g}->{kept[g]:.0f}" for g in order) + " km/h"), {}
    return "PASS", ("median speed rises with the value: "
                    + ", ".join(f"{g}->{kept[g]:.0f}" for g in order) + " km/h"), {"gears": len(kept)}


def _rpm_by_gear(series, gps, gear):
    """Does rpm/speed separate cleanly by gear value? Returns (ok, note)."""
    by = collections.defaultdict(list)
    for k, v in series.items():
        g, s = gear.get(k), gps.get(k)
        if g is not None and 1 <= round(g) <= GEAR_MAX and s is not None and s > 10 and v > 300:
            by[round(g)].append(v / s)
    kept = {g: sorted(r) for g, r in by.items() if len(r) >= GEAR_MIN_SECONDS}
    if len(kept) < 3:
        return False, ""
    order = sorted(kept)
    q = lambda r, p: r[int(p * (len(r) - 1))]
    for a, b in zip(order, order[1:]):
        # rpm per km/h falls as the gear rises, and the middle halves must not overlap
        if q(kept[b], 0.75) >= q(kept[a], 0.25) or q(kept[a], 0.5) / q(kept[b], 0.5) < GEAR_SEPARATION:
            return False, ""
    return True, "rpm/speed separates by gear (" + ", ".join(f"{g}: {q(kept[g], 0.5):.1f}" for g in order) + ")"


def check_rpm(sig, series, gps, gear=None):
    keys = [k for k in series if k in gps]
    stopped = [series[k] for k in keys if gps[k] < 1.5]
    moving = [k for k in keys if gps[k] > 8]
    if len(moving) < 30:
        return "INCONCLUSIVE", f"only {len(moving)} s moving over 8 km/h in the log", {}
    vals = [series[k] for k in keys]
    problems, notes = [], []
    if max(vals) > 9000:
        problems.append(f"max {max(vals):.0f} rpm is implausible")
    if st.pstdev(vals) < 50:
        problems.append("barely changes")
    share, levels = _concentration(series, gps)
    geared = share >= CONCENTRATION_MIN
    by_gear, gear_note = _rpm_by_gear(series, gps, gear) if gear else (False, "")
    if by_gear:
        notes.append(gear_note)
    elif geared:
        notes.append(f"rpm/speed in {len(levels)} gear-like levels ({', '.join(f'{v:.1f}' for v in levels)}), top-6 share {share:.2f}")
    idle_known = len(stopped) >= 20
    idle = None
    if idle_known:
        idle = st.median(stopped)
        if not 450 <= idle <= 1500:
            problems.append(f"stopped median {idle:.0f} rpm is outside 450-1500")
        else:
            notes.append(f"idle {idle:.0f} rpm over {len(stopped)} s stopped")
    m = {"share": round(share, 3)}
    if problems:
        return "FAIL", "; ".join(problems), m
    if not idle_known:
        return "INCONCLUSIVE", "no stopped seconds to check idle" + (f"; {notes[0]}" if notes else ""), m
    m["idle"] = round(idle)
    if geared or by_gear:
        return "PASS", "; ".join(notes), m
    # idle, range and movement are sane but rpm/speed is not held at a few levels: slip in the
    # torque converter, a noisy speed reference, or a signal that is only rpm-like. Weaker than PASS.
    return "PLAUSIBLE", "; ".join(notes) + f"; no gear structure (top-6 share {share:.2f} < {CONCENTRATION_MIN})", m


def check_pedal(sig, series, gps):
    vals = list(series.values())
    if len(vals) < 30:
        return "INCONCLUSIVE", "too few samples", {}
    lo, hi = min(vals), max(vals)
    p99 = sorted(vals)[int(len(vals) * 0.99) - 1]
    problems = []
    if lo < -0.5 or hi > 100.5:
        problems.append(f"range {lo:.1f}..{hi:.1f} is outside 0-100 %")
    if p99 < 3 or st.pstdev(vals) < 0.5:
        problems.append("barely moves")
    stopped = [series[k] for k in series if gps.get(k, 99) < 1.5]
    if len(stopped) >= 20 and st.median(stopped) > 15:
        problems.append(f"{st.median(stopped):.0f} % while stopped")
    if problems:
        return "FAIL", "; ".join(problems), {}
    return "PASS", f"range {lo:.1f}..{hi:.1f} %, p99 {p99:.1f} % (range and movement only; not tied to throttle)", {}


_SPEED_UNITS = {"km/h", "kph", "mph", "m/s", "rad/s"}
ROLES = {
    "speed": (lambda s: s.unit.lower() in _SPEED_UNITS and re.search(r"whl|wheel|veh.*(spd|speed|_v_)|speed|spd", s.name, re.I), check_speed),
    "rpm": (lambda s: s.unit.lower() == "rpm" or re.search(r"rpm", s.name, re.I), check_rpm),
    "gear": (lambda s: re.search(r"gear", s.name, re.I) and not re.search(r"trg|stat|qf|cs$|cnt|lvr|lever|rng|rq|cmd", s.name, re.I), check_gear),
    "pedal": (lambda s: s.unit == "%" and re.search(r"aped|pedal|throttle|accel|tps", s.name, re.I), check_pedal),
}
_ORDER = {"PASS": 0, "PLAUSIBLE": 1, "INCONCLUSIVE": 2, "FAIL": 3}


_PROCESS_ORDER = ("speed", "gear", "pedal", "rpm")   # gear before rpm: rpm is tested per gear


def validate(logs, lib, dbc_names):
    """For each DBC, the best results per role among signals whose messages are in the log."""
    wanted = {cid for n in dbc_names for cid in lib[n] if cid in logs.counts}
    raw = logs.collect(wanted)
    results = {}
    for name in dbc_names:
        per_role = {}
        gear_series = None   # the best gear signal that passed, used to test rpm
        for role in _PROCESS_ORDER:
            match, check = ROLES[role]
            for cid, (mname, sigs) in lib[name].items():
                if cid not in raw:
                    continue
                for sig in sigs:
                    if not match(sig):
                        continue
                    series = _per_second((si, sec, sig.decode(d)) for si, sec, d in raw[cid])
                    if not series:
                        continue
                    extra = {"gear": gear_series} if role == "rpm" and gear_series else {}
                    status, detail, metrics = check(sig, series, logs.gps, **extra)
                    per_role.setdefault(role, []).append({
                        "signal": f"0x{cid:X} {sig.label}", "status": status,
                        "detail": detail.replace("{ref}", logs.gps_source), **metrics})
                    if role == "gear" and status == "PASS" and gear_series is None:
                        gear_series = series
            if role in per_role:
                per_role[role].sort(key=lambda r: (_ORDER[r["status"]], -r.get("r", r.get("share", 0))))
        results[name] = per_role
    return results

# -------------------------------------------------------------------- report

def report(logs, ranking, results, top):
    print(f"{len(logs.paths)} log(s), {logs.seconds:.0f} s, {len(logs.counts)} CAN IDs, "
          f"speed reference: {logs.gps_source}\n")
    print("Fingerprint (share of observed traffic the DBC defines):")
    for r in ranking[:max(top, 5)]:
        print(f"  {r['score']*100:5.1f}%  {r['ids_matched']:3d}/{r['ids_observed']} ids  {r['dbc']}")
    for r in ranking[:top]:
        print(f"\n== {r['dbc']}")
        roles = results[r["dbc"]]
        for role in ROLES:
            rows = roles.get(role)
            if not rows:
                print(f"  {role:6s} no candidate signal defined for the IDs in this log")
                continue
            shown = [x for x in rows if x["status"] == "PASS"][:3] or rows[:1]
            for x in shown:
                print(f"  {role:6s} {x['status']:12s} {x['signal']}: {x['detail']}")
    print()


def unexplained(logs, lib, dbc, min_hz=8.0):
    return sorted(((cid, logs.rate(cid)) for cid in logs.counts
                   if cid not in lib[dbc] and logs.rate(cid) >= min_hz), key=lambda x: -x[1])


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("logs", nargs="+", help="CANStream session-*.bin frame logs")
    ap.add_argument("--dbc-dir", action="append", help="DBC library directory (repeatable; default can_data/dbc)")
    ap.add_argument("--top", type=int, default=3, help="validate this many best-fitting DBCs")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    a = ap.parse_args(argv)
    dirs = a.dbc_dir or [os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dbc")]
    lib = load_library(dirs)
    if not lib:
        sys.exit("no DBC files found in " + ", ".join(dirs))
    logs = Logs(a.logs)
    if not logs.counts:
        sys.exit("no CAN messages in the given logs")
    ranking = fingerprint(logs, lib)
    names = [r["dbc"] for r in ranking[:a.top]]
    results = validate(logs, lib, names)
    if a.json:
        print(json.dumps({"fingerprint": ranking[:10], "validation": results,
                          "unexplained_hz": [[f"0x{c:X}", round(h, 1)] for c, h in unexplained(logs, lib, names[0])]}, indent=1))
        return
    report(logs, ranking, results, a.top)
    un = unexplained(logs, lib, names[0])
    print(f"IDs at >=8 Hz that {names[0]} does not define ({len(un)}) - the discovery list:")
    print("  " + " ".join(f"{c:03X}:{h:.0f}Hz" for c, h in un))


if __name__ == "__main__":
    main()

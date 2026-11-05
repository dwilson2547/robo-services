import sys, struct, collections
sys.path.insert(0, sys.argv[2]); import telemetry_pb2 as pb
data = open(sys.argv[1], "rb").read(); i = 0; frames = []
while i + 4 <= len(data):
    n = struct.unpack(">i", data[i:i+4])[0]; i += 4
    if n < 0 or i + n > len(data): break
    frames.append(data[i:i+n]); i += n
tot = 0; exact = collections.Counter(); bases = collections.Counter(); per_frame_dup = []
for raw in frames:
    f = pb.TelemetryFrame(); f.ParseFromString(raw); bases[f.base_timestamp_us] += 1
    seen = collections.Counter()
    for m in f.can.messages:
        k = (m.can_id if hasattr(m, "can_id") else m.id, m.dt_us, bytes(m.data)); seen[k] += 1; exact[(f.base_timestamp_us,) + k] += 1; tot += 1
    if seen: per_frame_dup.append(sum(v - 1 for v in seen.values()) / sum(seen.values()))
print("frames in file", len(frames), "distinct base seconds", len(bases), "max copies of a base", max(bases.values()))
print("CAN msgs", tot, "unique", len(exact), f"dup share {100*(1-len(exact)/max(tot,1)):.0f}%")
print("copy histogram", sorted(collections.Counter(exact.values()).items())[:12])

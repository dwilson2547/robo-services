import sys, struct, collections, statistics
sys.path.insert(0, sys.argv[2]); import telemetry_pb2 as pb
def load(p):
    d=open(p,"rb").read(); i=0; out=[]
    while i+4<=len(d):
        n=struct.unpack(">i",d[i:i+4])[0]; i+=4
        if n<0 or i+n>len(d): break
        out.append(d[i:i+n]); i+=n
    return out
for path in sys.argv[3:]:
    rows=[]
    for raw in load(path):
        f=pb.TelemetryFrame(); f.ParseFromString(raw)
        c=collections.Counter((m.can_id,m.dt_us,bytes(m.data)) for m in f.can.messages)
        rows.append((f.base_timestamp_us//1_000_000, len(f.can.messages), len(c), statistics.median(c.values()) if c else 0))
    t0=rows[0][0]
    print(path.split("/")[-1], "seconds", len(rows))
    for k in range(0,len(rows),max(1,len(rows)//25)):
        s,n,u,med=rows[k]; print(f"  t+{s-t0:4}s msgs={n:5} unique={u:4} median_copies={med}")

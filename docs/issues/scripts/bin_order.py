import sys, struct, collections
sys.path.insert(0, sys.argv[2]); import telemetry_pb2 as pb
d=open(sys.argv[1],"rb").read(); i=0; fr=[]
while i+4<=len(d):
    n=struct.unpack(">i",d[i:i+4])[0]; i+=4
    if n<0 or i+n>len(d): break
    fr.append(d[i:i+n]); i+=n
adj=0; nonadj=0; shown=0
imu_tot=0; imu_missing=0
for raw in fr:
    f=pb.TelemetryFrame(); f.ParseFromString(raw)
    imu_tot+=f.imu.count; imu_missing+=len(f.imu.missing)
    ms=[(m.can_id,m.dt_us,bytes(m.data).hex()) for m in f.can.messages]
    for a,b in zip(ms,ms[1:]):
        if a==b: adj+=1
    seen=set()
    for k,m in enumerate(ms):
        if m in seen and ms[k-1]!=m: nonadj+=1
        seen.add(m)
    if ms and shown<1 and len(ms)>40:
        shown+=1; print("sample frame order:"); [print("  ",hex(x[0]),x[1],x[2]) for x in ms[:30]]
print("adjacent identical pairs", adj, "non-adjacent repeats", nonadj)
print("imu samples", imu_tot, "filled-in (missing)", imu_missing)

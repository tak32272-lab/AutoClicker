# logcat から結果をまとめる
import re, sys, statistics as st
lines = open(sys.argv[1], encoding="utf-8", errors="ignore").read().splitlines()
sections = {}
cur = None
for l in lines:
    m = re.search(r"MARK (start|end) (\S+)", l)
    if m:
        cur = m.group(2) if m.group(1) == "start" else None
        if cur: sections[cur] = []
        continue
    m2 = re.search(r"MARK (\S+)$", l)
    if m2:
        cur = m2.group(1)
        sections[cur] = []
        continue
    if cur is not None:
        sections[cur].append(l)

def pct(a, p):
    a = sorted(a)
    return a[min(len(a) - 1, int(len(a) * p))] if a else float("nan")

for name, ls in sections.items():
    if name in ("rec", "play", "playend", "rec2", "rec2end"):
        continue
    spawn = sum(1 for l in ls if " SPAWN " in l)
    hits = [l for l in ls if " HIT " in l]
    miss = [l for l in ls if " MISS " in l]
    tout = sum(1 for l in ls if " TIMEOUT " in l)
    react = [int(re.search(r"react=(\d+)", l).group(1)) for l in hits]
    along = [float(re.search(r"along=(-?\d+)", l).group(1)) for l in hits + miss if "along=" in l]
    dist = [float(re.search(r"dist=(\d+)", l).group(1)) for l in hits]
    scans = [l for l in ls if " AC " in l and "scan " in l]
    cal = [l for l in ls if "calibration" in l]
    ntap = sum(int(re.search(r"tap n=(\d+)", l).group(1)) for l in ls if " AC " in l and "tap n=" in l)
    if cal:
        print("  " + cal[-1].split(" AC ", 1)[-1].strip())
    if hits and ntap:
        print("  taps per hit: %.2f" % (ntap / len(hits)))
    print("== %s" % name)
    print("  spawned %d  hit %d  miss-taps %d  timeout %d" % (spawn, len(hits), len(miss), tout))
    if react:
        print("  reaction ms: median %d  mean %.0f  p90 %d  min %d  max %d" % (st.median(react), st.mean(react), pct(react, 0.9), min(react), max(react)))
    if dist:
        print("  hit distance from center px: mean %.0f  max %.0f" % (st.mean(dist), max(dist)))
    if along:
        print("  tap offset along motion px (+=ahead): mean %.0f" % st.mean(along))
    if scans:
        print("  last clicker stats:", scans[-1].split("scan ", 1)[1])

def touches(sec, kind="DOWN"):
    return [l for l in sections.get(sec, []) if re.search(r"TOUCH %s " % kind, l)]

# 記録：膜をすり抜けてゲームに届いたか
rec_actions = [l for l in sections.get("rec", []) if "rec action" in l]
rec_down = touches("rec")
print("== record")
print("  recorded actions %d, game received DOWN %d, CANCEL %d, self-hit retries %d" % (
    len(rec_actions), len(rec_down), len(touches("rec", "CANCEL")), sum(1 for l in sections.get("rec", []) if "self-hit" in l)))
ups = [int(re.search(r"up=(\d+)", l).group(1)) for l in rec_actions]
gd = [int(re.search(r"t=(\d+)", l).group(1)) for l in rec_down]
lat = []
for u in ups:
    later = [g for g in gd if g >= u]
    if later: lat.append(min(later) - u)
if lat:
    print("  finger-up -> game touch latency ms: median %d  max %d" % (st.median(lat), max(lat)))
# 再生：間隔のずれ
play_down = touches("play")
print("== playback")
print("  game received DOWN %d (expected %d)" % (len(play_down), len(rec_actions)))
rt = [int(re.search(r"rec action t=(\d+)", l).group(1)) for l in rec_actions]
pt = [int(re.search(r"t=(\d+)", l).group(1)) for l in play_down]
if len(pt) == len(rt) and len(rt) > 1:
    errs = [abs((pt[i] - pt[0]) - (rt[i] - rt[0])) for i in range(len(rt))]
    print("  timing error vs recording ms: mean %.1f  max %d" % (st.mean(errs), max(errs)))
batches = [l for l in sections.get("play", []) if "play batch" in l]
for b in batches:
    print("  " + b.split(" AC ", 1)[-1].strip())
print("== record fast taps")
r2a = [l for l in sections.get("rec2", []) if "rec action" in l]
print("  recorded %d, game received DOWN %d (sent 10)" % (len(r2a), len(touches("rec2"))))
errs = [l for l in lines if "AndroidRuntime" in l or "FATAL" in l]
print("== crashes: %d lines" % len(errs))
for e in errs[:20]:
    print("  " + e)

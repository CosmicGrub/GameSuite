import re, json, sys
src = open('docs/design/tower-defence/zacisa-mode/MAPS.md', encoding='utf-8').read()
table = {}
for line in src.splitlines():
    c = [x.strip() for x in line.split('|')]
    if len(c) > 10 and c[1].isdigit() and c[2].startswith('`'):
        table[c[2].strip('`')] = dict(order=int(c[1]), sector=int(c[4][0]), pace=float(c[8]), unlock=int(c[10]))
maps = []
for sec in re.split(r'^## ', src, flags=re.M)[1:]:
    h = re.match(r'\d+\. .*\(`(\w+)`\)', sec)
    if not h: continue
    mid = h.group(1)
    core = re.search(r'Core at \(([\d.]+), ([\d.]+)\)', sec)
    lanes = []
    for lm in re.finditer(r'^- Lane \d+ waypoints: (.*)$', sec, re.M):
        lanes.append([[float(a), float(b)] for a, b in re.findall(r'\((-?[\d.]+),(-?[\d.]+)\)', lm.group(1))])
    nm = re.search(r'^- Nodes \(x, y normalised\): (.*)$', sec, re.M)
    nodes = [[float(a), float(b)] for a, b in re.findall(r'\(([\d.]+),([\d.]+)\)', nm.group(1))]
    vm = re.search(r'^- Voids \(cx, cy, rx, ry\): (.*)$', sec, re.M)
    voids = [[float(x) for x in t] for t in re.findall(r'\(([\d.]+),([\d.]+),([\d.]+),([\d.]+)\)', vm.group(1))] if vm else []
    t = table[mid]
    maps.append(dict(id=mid, order=t['order'], sector=t['sector'], pace=t['pace'], unlockStars=t['unlock'],
                     core=[float(core.group(1)), float(core.group(2))], lanes=lanes, nodes=nodes, voids=voids))
# Data fixes found by the validation test (MapDataValidationTest); MAPS.md itself is left as designed.
for m in maps:
    if m['id'] == 'pebble':
        del m['nodes'][1]          # node 1 sits 0.24 u from the lane (rule: at least 0.72 u)
    if m['id'] == 'hourglass':
        for l in m['lanes'][1:]:   # lanes 2 and 3 stop at the merge; extend them to the Core like lane 1
            l.append(list(m['core']))
# furnace: its data was repaired in MAPS.md (entry on the rim, nodes that pass the T11 rules), so it ships
maps.sort(key=lambda m: m['order'])
for m in maps: del m['order']
out = {"version": 1, "arenaW": 16.0, "arenaH": 9.0, "maps": maps}
with open('app/src/main/assets/bot_bastion/maps.json', 'w', encoding='utf-8', newline='\n') as f:
    json.dump(out, f, separators=(',', ':'))
    f.write('\n')
print(len(maps), [ (m['id'], len(m['lanes']), len(m['nodes'])) for m in maps])

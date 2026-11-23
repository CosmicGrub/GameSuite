# Executed from make_models.py (shares its globals). Sprite bake + Hearth Orbit scene renders in four sector looks.
import json as _json

# ------------------------------------------------------------------ restore visibility
for _o in list(bpy.data.objects):
    if _o.name.startswith("Picket.") or _o.name.startswith("Lobber.") or _o.name.startswith("Skiff.") or _o.name.startswith("Core.") or _o.name.startswith("Drifter."):
        bpy.data.objects.remove(_o, do_unlink=True)
for _o in list(bpy.data.objects):
    if _o.type == "MESH" and _o.name not in MODELS and _o.name != "ground" and _o.data.users == 1 and (_o.name.endswith(".001") or _o.name.endswith(".002")):
        bpy.data.objects.remove(_o, do_unlink=True)

os.makedirs(OUT + "/sprites", exist_ok=True)
os.makedirs(OUT + "/scenes", exist_ok=True)

# ------------------------------------------------------------------ transparent sprite bake (fixed tilt camera, 8 yaws)
sc.render.engine = "BLENDER_EEVEE"
sc.render.film_transparent = True
sc.render.resolution_x = sc.render.resolution_y = 256
gnd.hide_render = True
ELEV = 50.0
def sprite_cam(scale=6.4, elev=ELEV):
    cam_d.type = "ORTHO"; cam_d.ortho_scale = scale
    e = math.radians(elev)
    cam.location = (0, -10 * math.cos(e), 10 * math.sin(e))
    cam.rotation_euler = (Vector((0, 0, 0)) - cam.location).to_track_quat("-Z", "Y").to_euler()
sprite_cam()
for nm, o in MODELS.items():
    o.hide_render = True
for nm, o in MODELS.items():
    o.hide_render = False
    for k in range(8):
        o.rotation_euler = (0, 0, math.radians(45 * k))
        sc.render.filepath = f"{OUT}/sprites/{nm.lower()}_{k}.png"
        bpy.ops.render.render(write_still=True)
        if nm == "Core": break
    o.rotation_euler = (0, 0, 0)
    o.hide_render = True

# ------------------------------------------------------------------ scene renders
sc.render.film_transparent = False
SECTORS = {
    # name: (world rgb, ground rgb, lane rgb, key colour, rim colour, accent emission, prop kind)
    "cradle": ("#0E1226", "#1A2352", "#10173A", (1.0, 0.93, 0.82), (0.45, 0.7, 1.0), "#4FE3FF", "rock"),
    "glass":  ("#0A1A24", "#16384A", "#0C2230", (0.85, 0.97, 1.0), (0.5, 0.9, 1.0), "#9DEBFF", "crystal"),
    "ember":  ("#1A0C0C", "#3A1A18", "#241010", (1.0, 0.75, 0.55), (1.0, 0.45, 0.25), "#FF8A3D", "vent"),
    "hush":   ("#100A22", "#2A1E52", "#170F36", (0.85, 0.8, 1.0), (0.7, 0.5, 1.0), "#B58CFF", "lantern"),
}
NODES = [(0.050,0.256),(0.154,0.256),(0.258,0.256),(0.363,0.256),(0.467,0.256),(0.572,0.256),(0.676,0.256),(0.780,0.256),(0.909,0.150),(0.909,0.341),(0.791,0.405),(0.909,0.532),(0.791,0.595),(0.909,0.723)]
LANE = [(-0.02,0.15),(0.85,0.15),(0.85,0.85),(0.15,0.85),(0.15,0.35),(0.62,0.35),(0.62,0.50)]
CORE_N = (0.62, 0.50)
def W(p): return ((p[0] - 0.5) * 16.0, (0.5 - p[1]) * 9.0)

def clear_scene_extras():
    for o in list(bpy.data.objects):
        if o.get("extra"): bpy.data.objects.remove(o, do_unlink=True)

def ex(o):
    o["extra"] = 1; return o

def inst(src, loc, rotz=0.0, scale=1.0):
    o = src.copy(); o.data = src.data  # linked data -> instancing-like
    bpy.context.collection.objects.link(o)
    o.hide_render = False; o.hide_viewport = False
    o.location = (loc[0], loc[1], loc[2] if len(loc) > 2 else 0)
    o.rotation_euler = (0, 0, rotz); o.scale = (scale,) * 3
    return ex(o)

def add_box(nm, sx, sy, sz, loc, rotz, m):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc, rotation=(0, 0, rotz))
    o = bpy.context.active_object; o.name = nm; o.scale = (sx, sy, sz); finish(o, m); return ex(o)

def build_scene(sec, shot):
    clear_scene_extras()
    wcol, gcol, lcol, key, rim, acc, prop = SECTORS[sec]
    bg.inputs[0].default_value = (*hexrgb(wcol), 1)
    for nm_, c_ in (("key", key), ("rim", rim)):
        bpy.data.objects[nm_].data.color = c_
    gm = mat("g_" + sec, hexrgb(gcol), 0.92); lm = mat("l_" + sec, hexrgb(lcol), 0.8)
    em = mat("e_" + sec, hexrgb(acc), 0.4, 0, hexrgb(acc), 1.1)
    # arena plate
    ex(add_box("plate", 17.2, 10.2, 0.3, (0, 0, -0.15), 0, gm))
    ex(add_box("plate_rim", 17.6, 10.6, 0.2, (0, 0, -0.35), 0, DARK))
    # lane strips
    pts = [W(p) for p in LANE]
    for (a, b) in zip(pts[:-1], pts[1:]):
        dx, dy = b[0] - a[0], b[1] - a[1]; L = math.hypot(dx, dy); ang = math.atan2(dy, dx)
        mx, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
        ex(add_box("lane", L + 0.95, 0.95, 0.05, (mx, my, 0.02), ang, lm))
        for s in (-1, 1):
            ox, oy = -math.sin(ang) * 0.5 * s, math.cos(ang) * 0.5 * s
            ex(add_box("lane_edge", L, 0.05, 0.07, (mx + ox, my + oy, 0.03), ang, em))
    # nodes
    occupied = {0: "Picket", 2: "Lobber", 4: "Picket", 6: "Skiff", 9: "Picket", 10: "Lobber", 12: "Picket", 5: "Picket"}
    for i, nn in enumerate(NODES):
        x, y = W(nn)
        bpy.ops.mesh.primitive_cone_add(vertices=6, radius1=0.42, radius2=0.42, depth=0.08, location=(x, y, 0.04), rotation=(0, 0, math.radians(30)))
        o = bpy.context.active_object; o.name = "node"; finish(o, HULL2 if i not in occupied else DARK); ex(o)
        bpy.ops.mesh.primitive_cone_add(vertices=6, radius1=0.46, radius2=0.46, depth=0.03, location=(x, y, 0.02), rotation=(0, 0, math.radians(30)))
        o = bpy.context.active_object; o.name = "node_ring"; finish(o, em); ex(o)
        if i in occupied:
            m = MODELS[occupied[i]]
            sca = 0.44 if occupied[i] != "Skiff" else 0.42
            # aim at nearest lane point
            best = min(pts, key=lambda p: (p[0] - x) ** 2 + (p[1] - y) ** 2)
            aim = math.atan2(best[1] - y, best[0] - x) + math.pi / 2  # model forward is -Y; rotate so forward -> target
            inst(m, (x, y, 0.07 + (0.35 if occupied[i] == "Skiff" else 0)), aim, sca)
    # core
    cx, cy = W(CORE_N)
    inst(MODELS["Core"], (cx, cy, 0.0), 0, 0.5)
    # enemies along lane
    import random as _r
    rr = _r.Random(3)
    def lane_pt(t):
        tot = sum(math.hypot(b[0]-a[0], b[1]-a[1]) for a, b in zip(pts[:-1], pts[1:])); d = t * tot
        for a, b in zip(pts[:-1], pts[1:]):
            L = math.hypot(b[0]-a[0], b[1]-a[1])
            if d <= L: return (a[0]+(b[0]-a[0])*d/L, a[1]+(b[1]-a[1])*d/L, math.atan2(b[1]-a[1], b[0]-a[0]))
            d -= L
        return (*pts[-1], 0)
    for t in (0.07, 0.14, 0.24, 0.30, 0.52, 0.57, 0.64, 0.80):
        x, y, a = lane_pt(t)
        inst(MODELS["Drifter"], (x + rr.uniform(-.1, .1), y + rr.uniform(-.1, .1), 0.0), a + math.pi / 2, 0.4 + rr.random() * 0.08)
    # projectiles (emissive beads) from some pickets toward lane
    for (i, col) in ((0, CY), (4, CY), (9, CY), (5, CY)):
        x, y = W(NODES[i]); best = min(pts, key=lambda p: (p[0]-x)**2 + (p[1]-y)**2)
        for f in (0.35, 0.6):
            bpy.ops.mesh.primitive_uv_sphere_add(segments=8, ring_count=4, radius=0.09, location=(x + (best[0]-x)*f, y + (best[1]-y)*f, 0.62))
            o = bpy.context.active_object; o.name = "shot"; finish(o, col); ex(o)
    # range ring (selected picket, node 5): flat torus
    sx, sy = W(NODES[5])
    bpy.ops.mesh.primitive_torus_add(major_segments=64, minor_segments=3, major_radius=3.2, minor_radius=0.025, location=(sx, sy, 0.06))
    o = bpy.context.active_object; o.name = "range_ring"; finish(o, em, flat=False); ex(o)
    # props per sector (outside the lanes)
    pr = _r.Random(11 + len(sec))
    spots = [(-7.3, -3.9), (7.2, -3.9), (-7.4, 3.8), (-2.2, -3.9), (3.5, -4.0), (-5.0, 4.0), (1.0, 4.1), (7.5, 1.0), (-7.6, -1.0)]
    for (x, y) in spots:
        if prop == "rock":
            bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=1, radius=0.35 + pr.random() * 0.25, location=(x, y, 0.2)); o = bpy.context.active_object; finish(o, ROCK2 if pr.random() < .5 else ROCK); ex(o)
        elif prop == "crystal":
            for k in range(3):
                bpy.ops.mesh.primitive_cone_add(vertices=5, radius1=0.16, radius2=0.0, depth=0.9 + pr.random() * 0.7, location=(x + pr.uniform(-.3, .3), y + pr.uniform(-.3, .3), 0.5), rotation=(pr.uniform(-.25, .25), pr.uniform(-.25, .25), 0))
                o = bpy.context.active_object; finish(o, em); ex(o)
        elif prop == "vent":
            bpy.ops.mesh.primitive_cone_add(vertices=7, radius1=0.45, radius2=0.22, depth=0.5, location=(x, y, 0.25)); o = bpy.context.active_object; finish(o, DARK); ex(o)
            bpy.ops.mesh.primitive_uv_sphere_add(segments=8, ring_count=4, radius=0.2, location=(x, y, 0.55)); o = bpy.context.active_object; finish(o, em); ex(o)
        else:
            bpy.ops.mesh.primitive_cylinder_add(vertices=6, radius=0.05, depth=1.3, location=(x, y, 0.65)); o = bpy.context.active_object; finish(o, HULL2); ex(o)
            bpy.ops.mesh.primitive_uv_sphere_add(segments=8, ring_count=4, radius=0.2, location=(x, y, 1.4)); o = bpy.context.active_object; finish(o, em); ex(o)
    return

for nm, o in MODELS.items():
    o.hide_render = True
for o in bpy.data.objects:
    if o.get("extra"): o.hide_render = False
sc.render.resolution_x, sc.render.resolution_y = 1280, 720

def scene_cam(kind):
    if kind == "tilt":
        cam_d.type = "ORTHO"; cam_d.ortho_scale = 17.6
        e = math.radians(50); cam.location = (0, -14 * math.cos(e), 14 * math.sin(e) + 0.0)
        cam.rotation_euler = (Vector((0, 0.3, 0)) - cam.location).to_track_quat("-Z", "Y").to_euler()
    elif kind == "persp":
        cam_d.type = "PERSP"; cam_d.lens = 38; cam_d.sensor_width = 36
        e = math.radians(48); cam.location = (0.0, -17.5 * math.cos(e), 17.5 * math.sin(e))
        cam.rotation_euler = (Vector((0, -0.6, 0)) - cam.location).to_track_quat("-Z", "Y").to_euler()
    elif kind == "top":
        cam_d.type = "ORTHO"; cam_d.ortho_scale = 17.6
        cam.location = (0, 0, 16); cam.rotation_euler = (0, 0, 0)
    elif kind == "low":
        cam_d.type = "PERSP"; cam_d.lens = 45
        cam.location = (3.5, -9.0, 3.4); cam.rotation_euler = (Vector((2.5, -0.5, 0.6)) - cam.location).to_track_quat("-Z", "Y").to_euler()

# ground disc no longer needed in scene renders
gnd.hide_render = True
for sec in SECTORS:
    build_scene(sec, None)
    for o in bpy.data.objects:
        if o.get("extra"): o.hide_render = False
    for shot in ("tilt", "persp") if sec == "cradle" else ("tilt",):
        scene_cam(shot)
        sc.render.filepath = f"{OUT}/scenes/hearth_{sec}_{shot}.png"
        bpy.ops.render.render(write_still=True)
    if sec == "cradle":
        for shot in ("top", "low"):
            scene_cam(shot)
            sc.render.filepath = f"{OUT}/scenes/hearth_{sec}_{shot}.png"
            bpy.ops.render.render(write_still=True)
print("SCENE_DONE")

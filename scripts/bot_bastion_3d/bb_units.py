"""Original low-poly unit models for the Bot Bastion sprite pack: bots (5 levels), Aegis, Core, enemies.
Every builder returns joined mesh objects built facing +Y at the origin. Imported by bb_bake.py (Blender)."""
import math
from bb_kit import *

R = math.radians


# ------------------------------------------------------------------------------------------------ bots
def picket(L):
    b = [cyl("base", 1.0, 0.32, 6, (0, 0, 0.16), (0, 0, R(30)), HULL),
         cyl("base_ring", 1.05, 0.08, 6, (0, 0, 0.36), (0, 0, R(30)), GOLD if L >= 5 else HULL2),
         sph("dome", 0.62, (0, 0, 0.45), 10, 5, (1, 1, 0.8), HULL)]
    for i in range(6):
        a = R(60 * i)
        b.append(box(f"led{i}", 0.12, 0.12, 0.07, (0.92 * math.cos(a), 0.92 * math.sin(a), 0.38), (0, 0, a), CY))
    if L >= 4:
        for i in range(6):
            a = R(60 * i + 30)
            b.append(box(f"led2_{i}", 0.09, 0.09, 0.06, (0.78 * math.cos(a), 0.78 * math.sin(a), 0.4), (0, 0, a), CY))
    if L >= 3:
        for i in range(3):
            a = R(120 * i + 90 + 180)
            b.append(box(f"vent{i}", 0.34, 0.06, 0.12, (0.86 * math.cos(a), 0.86 * math.sin(a), 0.2), (0, 0, a + R(90)), DARK))
    h = [box("barrel_sleeve", 0.34, 0.5, 0.3, (0, 0.55, 0.78), (0, 0, 0), HULL2),
         box("barrel", 0.18, 0.9, 0.18, (0, 1.0, 0.78), (0, 0, 0), GOLD if L >= 5 else HULL2),
         box("muzzle", 0.28, 0.16, 0.28, (0, 1.5, 0.78), (0, 0, 0), DARK),
         box("muzzle_glow", 0.1 + 0.03 * (L >= 5), 0.05, 0.1 + 0.03 * (L >= 5), (0, 1.6, 0.78), (0, 0, 0), CY),
         sph("eye", 0.16, (0, 0.36, 0.88), 8, 4, (1, 1, 1), CY)]
    if L >= 2:
        h.append(box("sleeve2", 0.4, 0.14, 0.36, (0, 0.95, 0.78), (0, 0, 0), HULL))
    if L >= 3:
        h.append(box("sleeve3", 0.38, 0.1, 0.34, (0, 1.2, 0.78), (0, 0, 0), HULL2))
    return join(b, "pk_base"), join(h, "pk_head")


def lobber(L):
    b = [cyl("base", 1.2, 0.34, 8, (0, 0, 0.17), (0, 0, R(22.5)), BRONZE),
         cyl("base_top", 0.95, 0.2, 8, (0, 0, 0.44), (0, 0, R(22.5)), GOLD if L >= 5 else HULL2)]
    for i in range(4):
        a = R(45 + 90 * i)
        b.append(box(f"foot{i}", 0.42, 0.42, 0.2, (1.12 * math.cos(a), 1.12 * math.sin(a), 0.1), (0, 0, a), HULL2))
        b.append(box(f"foot_led{i}", 0.1, 0.1, 0.05, (1.12 * math.cos(a), 1.12 * math.sin(a), 0.22), (0, 0, a), AM))
    if L >= 3:
        for i in range(4):
            a = R(90 * i)
            b.append(box(f"plate{i}", 0.5, 0.12, 0.1, (1.0 * math.cos(a), 1.0 * math.sin(a), 0.5), (0, 0, a + R(90)), BRONZE))
    t = R(-55)
    d = Vector((0, math.sin(R(55)), math.cos(R(55))))
    ext = 0.18 * (L - 1)
    ctr = Vector((0, 0.42, 1.55)) + d * (ext / 2)
    end = Vector((0, 0.42, 1.55)) + d * (0.85 + ext)
    h = [box("trunnion", 1.0, 0.5, 0.6, (0, 0, 0.8), (0, 0, 0), BRONZE),
         cyl("tube", 0.34, 1.7 + ext, 8, tuple(ctr), (t, 0, 0), HULL2, r2=0.42),
         cyl("tube_collar", 0.5, 0.2, 8, tuple(end - d * 0.05), (t, 0, 0), GOLD if L >= 5 else BRONZE),
         cyl("tube_mouth", 0.3, 0.1, 8, tuple(end - d * 0.0), (t, 0, 0), DARK),
         sph("shell_glow", 0.2 + 0.04 * (L >= 5), tuple(end - d * 0.08), 8, 4, (1, 1, 1), AM)]
    for i in range(1, min(L, 4)):
        c = Vector((0, 0.42, 1.55)) + d * (0.85 + ext - 0.34 * i)
        h.append(cyl(f"ring{i}", 0.47, 0.1, 8, tuple(c), (t, 0, 0), BRONZE))
    return join(b, "lb_base"), join(h, "lb_head")


def haze(L):
    b = [cyl("base", 1.05, 0.36, 8, (0, 0, 0.18), (0, 0, R(22.5)), VIOHULL),
         cyl("base_ring", 1.1, 0.08, 8, (0, 0, 0.4), (0, 0, R(22.5)), GOLD if L >= 5 else HULL2),
         cyl("pedestal", 0.5, 0.35, 8, (0, 0, 0.55), (0, 0, 0), HULL)]
    for i in range(4):
        a = R(45 + 90 * i)
        b.append(box(f"led{i}", 0.11, 0.11, 0.06, (0.95 * math.cos(a), 0.95 * math.sin(a), 0.38), (0, 0, a), VI))
    if L >= 3:
        for i in range(3):
            a = R(120 * i + 270)
            b.append(cyl(f"coil{i}", 0.12, 0.5, 6, (0.78 * math.cos(a), 0.78 * math.sin(a), 0.6), (0, 0, 0), HULL2))
    h = []
    for k, ang in enumerate((-42, 0, 42)):  # three arc fins fanned like a dish, tilted back
        a = R(ang)
        x, y = 0.55 * math.sin(a), 0.55 * math.cos(a)
        h.append(box(f"fin{k}", 0.1, 0.75, 0.9, (x, y + 0.2, 1.1), (R(-18), 0, -a), HULL2 if k != 1 else VIOHULL))
        h.append(box(f"fin_tip{k}", 0.12, 0.12, 0.12, (x * 1.3, y * 1.3 + 0.45, 1.5), (0, 0, -a), VI))
    h.append(torus("dish_ring", 0.72, 0.08, (0, 0.55, 1.05), (R(72), 0, 0), GOLD if L >= 5 else HULL2, 14, 4))
    h.append(sph("emitter", 0.2 + 0.03 * L / 5, (0, 0.4, 1.05), 8, 5, (1, 1, 1), VI))
    if L >= 2:
        h.append(torus("dish_ring2", 0.5, 0.06, (0, 0.5, 1.05), (R(72), 0, 0), HULL, 12, 4))
    if L >= 4:
        h.append(torus("dish_ring3", 0.9, 0.06, (0, 0.62, 1.05), (R(72), 0, 0), VI, 16, 4))
    return join(b, "hz_base"), join(h, "hz_head")


def tender(L):
    b = [cyl("base", 1.05, 0.34, 10, (0, 0, 0.17), (0, 0, 0), MINTHULL),
         cyl("base_ring", 1.1, 0.08, 10, (0, 0, 0.38), (0, 0, 0), GOLD if L >= 5 else HULL2),
         cyl("stem", 0.38, 0.4, 10, (0, 0, 0.58), (0, 0, 0), HULL),
         sph("lens", 0.3 + 0.02 * L, (0, 0, 0.92), 10, 6, (1, 1, 1), MINT, smooth=True)]
    for i in range(5):
        a = R(72 * i + 18)
        b.append(box(f"led{i}", 0.1, 0.1, 0.06, (0.92 * math.cos(a), 0.92 * math.sin(a), 0.4), (0, 0, a), MINT))
    if L >= 2:
        b.append(torus("pulse_ring", 0.98, 0.04, (0, 0, 0.44), (0, 0, 0), MINT, 20, 3))
    return join(b, "td_base")


def tender_rotor(L, phase_deg):
    r = []
    n = 6
    for i in range(n):
        a = R(60 * i + phase_deg)
        r.append(box(f"petal{i}", 0.34, 0.5, 0.06, (0.68 * math.cos(a), 0.68 * math.sin(a), 0.78), (0, 0, a + R(90)), HULL2 if (i % 2 == 0 or L < 3) else MINTHULL))
        if L >= 5:
            r.append(box(f"petal_tip{i}", 0.12, 0.12, 0.08, (0.94 * math.cos(a), 0.94 * math.sin(a), 0.78), (0, 0, a), GOLD))
        elif L >= 2:
            r.append(box(f"petal_tip{i}", 0.1, 0.1, 0.08, (0.94 * math.cos(a), 0.94 * math.sin(a), 0.78), (0, 0, a), MINT))
    if L >= 4:
        for i in range(6):
            a = R(60 * i + 30 + phase_deg)
            r.append(box(f"petal2_{i}", 0.2, 0.3, 0.05, (0.4 * math.cos(a), 0.4 * math.sin(a), 1.0), (0, 0, a + R(90)), WHITE))
    return join(r, "td_rotor")


def skiff(L):
    import bmesh
    bm = bmesh.new()
    top = [(0, 1.3, 0.25), (0.8, -0.6, 0.2), (0, -0.25, 0.3), (-0.8, -0.6, 0.2)]
    bot = [(0, 1.3, -0.05), (0.8, -0.6, -0.05), (0, -0.25, -0.1), (-0.8, -0.6, -0.05)]
    tv = [bm.verts.new(v) for v in top]
    bv = [bm.verts.new(v) for v in bot]
    bm.faces.new(tv)
    bm.faces.new(bv[::-1])
    for i in range(4):
        j = (i + 1) % 4
        bm.faces.new((tv[j], bv[j], bv[i], tv[i]))
    me = bpy.data.meshes.new("skiff_hull")
    bm.to_mesh(me)
    bm.free()
    o = bpy.data.objects.new("hull", me)
    bpy.context.collection.objects.link(o)
    finish(o, PLUM)
    bpy.context.view_layer.objects.active = o
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.normals_make_consistent(inside=False)
    bpy.ops.object.mode_set(mode="OBJECT")
    p = [o, sph("canopy", 0.3, (0, 0.3, 0.3), 8, 5, (0.8, 1.5, 0.7), CY),
         box("wing_l", 0.5, 0.35, 0.08, (-0.78, -0.4, 0.12), (0, 0, R(8)), GOLD if L >= 5 else HULL2),
         box("wing_r", 0.5, 0.35, 0.08, (0.78, -0.4, 0.12), (0, 0, R(-8)), GOLD if L >= 5 else HULL2),
         cyl("gun_l", 0.07, 0.9, 6, (-0.55, 0.45, 0.1), (R(90), 0, 0), DARK),
         cyl("gun_r", 0.07, 0.9, 6, (0.55, 0.45, 0.1), (R(90), 0, 0), DARK),
         box("thruster_l", 0.2, 0.22, 0.2, (-0.32, -0.62, 0.12), (0, 0, 0), DARK),
         box("thruster_r", 0.2, 0.22, 0.2, (0.32, -0.62, 0.12), (0, 0, 0), DARK),
         box("flame_l", 0.14 + 0.02 * L, 0.12 + 0.03 * L, 0.14, (-0.32, -0.76 - 0.015 * L, 0.12), (0, 0, 0), MG),
         box("flame_r", 0.14 + 0.02 * L, 0.12 + 0.03 * L, 0.14, (0.32, -0.76 - 0.015 * L, 0.12), (0, 0, 0), MG)]
    if L >= 2:
        for s in (-1, 1):
            p.append(box(f"stripe{s}", 0.06, 0.8, 0.03, (0.34 * s, 0.1, 0.31), (0, 0, R(-8) * s), MG))
    if L >= 3:
        for s in (-1, 1):
            p.append(cyl(f"gun2_{s}", 0.06, 0.7, 6, (0.8 * s, 0.1, 0.1), (R(90), 0, 0), DARK))
    if L >= 4:
        for s in (-1, 1):
            p.append(box(f"fin{s}", 0.05, 0.45, 0.32, (0.2 * s, -0.5, 0.42), (0, 0, 0), GOLD))
    ob = join(p, "sk")
    ob.location.z = 0.5
    bpy.ops.object.transform_apply(location=True)
    return ob


def aegis():
    p = [cyl("base", 0.95, 0.3, 6, (0, 0, 0.15), (0, 0, R(30)), TEAL),
         cyl("base_ring", 1.0, 0.07, 6, (0, 0, 0.33), (0, 0, R(30)), HULL2),
         cyl("column", 0.34, 0.6, 6, (0, 0, 0.7), (0, 0, 0), HULL, r2=0.5),
         sph("node", 0.36, (0, 0, 1.25), 8, 5, (1, 1, 1), MINT, smooth=True),
         torus("halo", 0.55, 0.05, (0, 0, 1.25), (R(80), 0, R(15)), WHITE, 14, 4)]
    for i in range(3):
        a = R(120 * i + 90)
        x, y = 0.78 * math.cos(a), 0.78 * math.sin(a)
        p.append(cyl(f"pylon{i}", 0.1, 1.0, 6, (x, y, 0.8), (0, 0, 0), HULL2, r2=0.07))
        p.append(sph(f"tip{i}", 0.1, (x, y, 1.35), 6, 4, (1, 1, 1), MINT))
    return join(p, "aegis")


# ------------------------------------------------------------------------------------------------ core
def core(state):
    """state 0 intact, 1 damaged (50), 2 critical (25), 3 fallen."""
    orb = {0: MINT, 1: gl("#6BFF9E", 0.9), 2: gl("#FFB547", 0.7), 3: gl("#2A5C48", 0.12)}[state]
    tip = {0: MINT, 1: MINT, 2: RED, 3: DARK}[state]
    p = [cyl("plinth", 2.1, 0.4, 12, (0, 0, 0.2), (0, 0, 0), HULL),
         cyl("plinth_ring", 2.2, 0.1, 12, (0, 0, 0.45), (0, 0, 0), HULL2),
         cyl("drum", 1.3, 0.7, 12, (0, 0, 0.85), (0, 0, 0), WHITE if state < 3 else HULL2, r2=1.05),
         cyl("drum_band", 1.08, 0.12, 12, (0, 0, 1.25), (0, 0, 0), MINT if state < 2 else (RED if state == 2 else DARK)),
         sph("core_orb", 0.75 if state < 3 else 0.6, (0, 0, 1.95 if state < 3 else 1.8), 12, 7, (1, 1, 1), orb, smooth=True),
         torus("cradle_ring", 1.05, 0.07, (0, 0, 1.95), (R(70), 0, R(20)), WHITE if state < 3 else HULL2)]
    for i in range(4):
        a = R(45 + 90 * i)
        x, y = 1.75 * math.cos(a), 1.75 * math.sin(a)
        toppled = (state == 3 and i == 1)
        if toppled:
            p.append(cyl(f"pylon{i}", 0.14, 1.5, 6, (x * 1.15, y * 1.15, 0.2), (R(90), 0, a), HULL2, r2=0.09))
        else:
            p.append(cyl(f"pylon{i}", 0.14, 1.5, 6, (x, y, 1.1), (0, 0, 0), HULL2, r2=0.09))
            if not (state == 1 and i == 2):
                p.append(sph(f"tip{i}", 0.12, (x, y, 1.9), 6, 4, (1, 1, 1), tip))
            p.append(box(f"strut{i}", 0.08, 0.9, 0.08, (x * 0.62, y * 0.62, 1.7), (0, 0, a + R(90)), HULL2))
    if state < 3:
        p.append(box("antenna", 0.06, 0.06, 0.9, (0, 0, 2.95), (0, 0, 0), HULL2))
        p.append(sph("antenna_tip", 0.1, (0, 0, 3.45), 6, 4, (1, 1, 1), tip))
    else:
        p.append(box("antenna", 0.06, 0.06, 0.9, (0.3, 0, 2.7), (0, R(25), 0), HULL2))
    if state >= 1:
        for i, (a, z) in enumerate(((20, 0.9), (140, 0.75), (250, 0.95))[:(2 if state == 1 else 3)]):
            ar = R(a)
            p.append(box(f"crack{i}", 0.05, 0.05, 0.5, (1.25 * math.cos(ar), 1.25 * math.sin(ar), z), (0, R(10), ar), DARK))
    if state >= 2:
        for i, a in enumerate((70, 200, 320)):
            ar = R(a)
            p.append(sph(f"glint{i}", 0.07, (1.12 * math.cos(ar), 1.12 * math.sin(ar), 1.0), 6, 4, (1, 1, 1), RED))
    return join(p, f"core{state}")


# ------------------------------------------------------------------------------------------------ enemies
def eyes(p, y=0.82, z=0.82, s=1.0, col=None):
    col = col or RED
    p.append(sph("eye_l", 0.16 * s, (-0.28 * s, y, z), 8, 5, (1, 0.7, 1), col))
    p.append(sph("eye_r", 0.12 * s, (0.26 * s, y + 0.02, z - 0.16 * s), 8, 5, (1, 0.7, 1), col))
    p.append(box("brow", 0.7 * s, 0.1, 0.1, (0, y + 0.04, z + 0.14), (R(8), 0, 0), DARK))


def drifter():
    o = ico("rock", 0.9, (0, 0, 0.7), ROCK, 1, (1.15, 0.95, 0.8), jitter=0.5, seed=7)
    p = [o]
    for i, (x, y, z, r) in enumerate([(-0.5, 0.35, 1.2, 0.3), (0.35, -0.5, 1.15, 0.24), (-0.1, -0.75, 0.55, 0.2)]):
        p.append(ico(f"boulder{i}", r, (x, y, z), ROCK2))
    eyes(p)
    return join(p, "drifter")


def darter():
    p = [ico("shard", 0.8, (0, 0, 0.6), ROCK, 1, (0.65, 1.55, 0.55), jitter=0.4, seed=4),
         cyl("spike", 0.28, 1.0, 5, (0, 1.4, 0.62), (R(-90), 0, 0), ROCK2, r2=0.0)]
    p[-1].rotation_euler = (R(-90), 0, 0)
    for s in (-1, 1):
        p.append(cyl(f"flame{s}", 0.14, 0.7, 5, (0.18 * s, -1.45, 0.58), (R(90), 0, 0), EMB, r2=0.0))
    p.append(sph("eye_l", 0.1, (-0.16, 0.55, 0.9), 6, 4, (1, 0.7, 1), RED))
    p.append(sph("eye_r", 0.1, (0.16, 0.55, 0.9), 6, 4, (1, 0.7, 1), RED))
    p.append(box("brow", 0.5, 0.08, 0.07, (0, 0.58, 1.0), (R(10), 0, 0), DARK))
    return join(p, "darter")


def plater():
    p = [ico("rock", 0.92, (0, 0, 0.72), ROCKD, 1, (1.2, 1.0, 0.85), jitter=0.35, seed=5)]
    for i in range(5):
        a = R(72 * i + 20)
        p.append(box(f"plate{i}", 0.75, 0.2, 0.55, (0.8 * math.cos(a), 0.7 * math.sin(a), 0.85), (R(-12), 0, a - R(90)), PLATE))
        p.append(sph(f"rivet{i}", 0.07, (0.95 * math.cos(a), 0.85 * math.sin(a), 1.05), 6, 4, (1, 1, 1), HULL2))
    p.append(box("topplate", 0.9, 0.7, 0.14, (0, -0.1, 1.28), (0, 0, R(10)), PLATE))
    eyes(p, y=0.9, z=0.85, s=0.9)
    p[-1].location.z += 0.04
    return join(p, "plater")


def splitter():
    p = [ico("lobe1", 0.72, (-0.4, 0, 0.68), ROCK, 1, (1, 0.95, 0.85), jitter=0.4, seed=9),
         ico("lobe2", 0.7, (0.42, 0.05, 0.66), ROCK2, 1, (1, 0.95, 0.85), jitter=0.4, seed=12)]
    p.append(box("crack1", 0.1, 1.5, 0.12, (0, 0, 0.95), (0, 0, R(8)), EMB))
    p.append(box("crack2", 0.9, 0.08, 0.1, (0, 0.1, 1.0), (0, 0, R(-6)), EMB))
    p.append(box("crack3", 0.08, 0.7, 0.1, (0.1, -0.5, 0.9), (0, 0, R(30)), EMB))
    eyes(p, y=0.78, z=0.8, s=0.85)
    return join(p, "splitter")


def bit():
    p = [ico("pebble", 0.55, (0, 0, 0.45), ROCK2, 1, (1.1, 1, 0.85), jitter=0.4, seed=2),
         sph("eye", 0.15, (0, 0.5, 0.6), 8, 5, (1, 0.8, 1), RED),
         box("brow", 0.32, 0.07, 0.06, (0, 0.52, 0.72), (R(8), 0, 0), DARK)]
    return join(p, "bit")


def courier():
    p = [ico("rock", 0.85, (0, 0, 0.65), ROCK, 1, (1.1, 1.0, 0.8), jitter=0.4, seed=21)]
    p.append(torus("stripe", 0.62, 0.06, (0, 0, 0.7), (0, 0, 0), GOLD, 12, 4))
    p.append(box("crate", 0.55, 0.55, 0.45, (0, -0.1, 1.3), (0, 0, R(18)), GOLD))
    p.append(box("crate_band", 0.58, 0.12, 0.48, (0, -0.1, 1.3), (0, 0, R(18)), DARK))
    eyes(p, y=0.8, z=0.78, s=0.85)
    return join(p, "courier")


BEHEMOTH_SKINS = {
    "base": dict(body="#7A5C58", plate="#5B4B4A", glow="#FF6B5A", trim=None),
    "slag": dict(body="#4A3030", plate="#2E2424", glow="#FF8A3D", trim="#FF8A3D"),
    "gilded": dict(body="#8A6A58", plate="#B88A30", glow="#FFD36B", trim="#E8B545"),
    "crimson": dict(body="#5A2A2A", plate="#7A2020", glow="#FF3A2A", trim="#FF6B5A"),
}


def behemoth(skin="base"):
    sk = BEHEMOTH_SKINS[skin]
    body, plate = solid(sk["body"], 0.9), solid(sk["plate"], 0.55, 0.35)
    glow = gl(sk["glow"], 2.6)
    p = [ico("rock", 1.0, (0, 0, 0.9), body, 2, (1.2, 1.05, 0.95), jitter=0.4, seed=33)]
    for i in range(8):
        a = R(45 * i + 10)
        p.append(box(f"plate{i}", 0.8, 0.22, 0.7, (1.0 * math.cos(a), 0.88 * math.sin(a), 0.95), (R(-14), 0, a - R(90)), plate))
        p.append(sph(f"rivet{i}", 0.08, (1.18 * math.cos(a), 1.04 * math.sin(a), 1.22), 6, 4, (1, 1, 1), HULL2))
    p.append(box("crown", 1.2, 0.9, 0.2, (0, -0.15, 1.7), (0, 0, R(8)), plate))
    p.append(sph("furnace", 0.4, (0, 0.78, 0.95), 8, 5, (1.2, 0.5, 1), glow))
    for i, (x, y, z, r) in enumerate([(-0.9, -0.3, 1.5, 0.35), (0.9, -0.4, 1.45, 0.3), (0, -1.0, 1.1, 0.3)]):
        p.append(ico(f"boulder{i}", r, (x, y, z), ROCK2))
    p.append(sph("eye_l", 0.2, (-0.38, 0.95, 1.25), 8, 5, (1, 0.7, 1), glow))
    p.append(sph("eye_r", 0.2, (0.38, 0.95, 1.25), 8, 5, (1, 0.7, 1), glow))
    p.append(box("brow", 1.2, 0.12, 0.14, (0, 1.0, 1.48), (R(10), 0, 0), DARK))
    if sk["trim"]:
        t = gl(sk["trim"], 2.0)
        for i in range(4):
            a = R(90 * i + 45)
            p.append(box(f"trim{i}", 0.12, 0.12, 0.5, (0.85 * math.cos(a), 0.75 * math.sin(a), 1.75), (0, 0, a), t))
    return join(p, f"behemoth_{skin}")


# ------------------------------------------------------------------------------------------------ projectiles / props helpers
def crate():
    p = [box("crate", 0.6, 0.6, 0.5, (0, 0, 0.25), (0, 0, 0), GOLD), box("band", 0.64, 0.14, 0.54, (0, 0, 0.25), (0, 0, 0), DARK),
         box("band2", 0.14, 0.64, 0.54, (0, 0, 0.25), (0, 0, 0), DARK)]
    return join(p, "crate")


def bolt(kind):
    if kind == "pew":
        p = [box("b", 0.1, 0.55, 0.1, (0, 0, 0.1), (0, 0, 0), CY), box("c", 0.05, 0.3, 0.05, (0, 0.1, 0.1), (0, 0, 0), WHITE)]
    elif kind == "skiff":
        p = [box("a", 0.08, 0.3, 0.08, (-0.1, 0.15, 0.1), (0, 0, 0), MG), box("b", 0.08, 0.3, 0.08, (0.1, -0.15, 0.1), (0, 0, 0), MG)]
    else:  # hiss
        p = [box(f"s{i}", 0.03, 0.55, 0.03, (0.1 * (i - 1), 0.1 * (i % 2), 0.1), (0, 0, R(8 * (i - 1))), VI) for i in range(3)]
    return join(p, "bolt_" + kind)


def shell():
    p = [sph("shell", 0.22, (0, 0, 0.2), 8, 5, (1, 1, 1), AM), torus("band", 0.22, 0.04, (0, 0, 0.2), (0, 0, 0), BRONZE, 10, 3)]
    return join(p, "shell")

"""Bot Bastion 3D proof: five ORIGINAL low-poly models, rendered + exported to glTF.
Run: blender -b --python make_models.py -- <outdir>
"""
import bpy, bmesh, math, random, sys, os
from mathutils import Vector

OUT = sys.argv[sys.argv.index("--") + 1]
os.makedirs(OUT + "/gltf", exist_ok=True)
os.makedirs(OUT + "/raw", exist_ok=True)

bpy.ops.wm.read_factory_settings(use_empty=True)
sc = bpy.context.scene

def mat(name, rgb, rough=0.55, metal=0.0, emit=None, estr=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*rgb, 1)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    if emit:
        b.inputs["Emission Color"].default_value = (*emit, 1)
        b.inputs["Emission Strength"].default_value = estr
    return m

def hexrgb(h):
    h = h.lstrip("#")
    c = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    # to linear
    return tuple(((x + 0.055) / 1.055) ** 2.4 if x > 0.04045 else x / 12.92 for x in c)

HULL = mat("hull_navy", hexrgb("#2B3A72"), 0.5, 0.15)
HULL2 = mat("hull_steel", hexrgb("#7C8AA8"), 0.45, 0.3)
DARK = mat("dark_ink", hexrgb("#141A3A"), 0.7)
CY = mat("glow_cyan", hexrgb("#4FE3FF"), 0.3, 0, hexrgb("#4FE3FF"), 2.2)
AM = mat("glow_amber", hexrgb("#FFB547"), 0.3, 0, hexrgb("#FFB547"), 2.2)
MG = mat("glow_magenta", hexrgb("#FF4FD8"), 0.3, 0, hexrgb("#FF4FD8"), 2.2)
TL = mat("glow_teal", hexrgb("#6BFF9E"), 0.3, 0, hexrgb("#6BFF9E"), 1.6)
BRONZE = mat("hull_bronze", hexrgb("#5A4020"), 0.55, 0.2)
PLUM = mat("hull_plum", hexrgb("#6B2A68"), 0.5, 0.15)
ROCK = mat("rock", hexrgb("#7A5C58"), 0.9)
ROCK2 = mat("rock_light", hexrgb("#A8786A"), 0.9)
RED = mat("glow_red", hexrgb("#FF6B5A"), 0.3, 0, hexrgb("#FF6B5A"), 1.8)
WHITE = mat("hull_white", hexrgb("#DCE4F5"), 0.4, 0.1)

def finish(obj, m, flat=True):
    obj.data.materials.clear()
    obj.data.materials.append(m)
    if flat:
        for p in obj.data.polygons: p.use_smooth = False

def cyl(name, r, h, seg, loc=(0, 0, 0), rot=(0, 0, 0), m=HULL, r2=None):
    bpy.ops.mesh.primitive_cone_add(vertices=seg, radius1=r, radius2=(r if r2 is None else r2), depth=h, location=loc, rotation=rot)
    o = bpy.context.active_object; o.name = name; finish(o, m); return o

def box(name, sx, sy, sz, loc=(0, 0, 0), rot=(0, 0, 0), m=HULL):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc, rotation=rot)
    o = bpy.context.active_object; o.name = name; o.scale = (sx, sy, sz)
    bpy.ops.object.transform_apply(scale=True); finish(o, m); return o

def sph(name, r, loc=(0, 0, 0), seg=10, rings=6, scale=(1, 1, 1), m=HULL, smooth=False):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=seg, ring_count=rings, radius=r, location=loc)
    o = bpy.context.active_object; o.name = name; o.scale = scale
    bpy.ops.object.transform_apply(scale=True); finish(o, m, flat=not smooth); return o

def join(objs, name):
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs: o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.join()
    o = bpy.context.active_object; o.name = name
    bpy.ops.object.origin_set(type="ORIGIN_CURSOR")
    return o

# bevel for a soft toy-like edge, applied to hull parts only (keeps tri budget low: width small, 1 segment)
def bevel(o, w=0.025):
    mod = o.modifiers.new("bev", "BEVEL"); mod.width = w; mod.segments = 1; mod.limit_method = "ANGLE"
    bpy.context.view_layer.objects.active = o
    bpy.ops.object.modifier_apply(modifier=mod.name)

def picket():
    parts = []
    parts.append(cyl("base", 1.0, 0.32, 6, (0, 0, 0.16), (0, 0, math.radians(30)), HULL))
    parts.append(cyl("base_ring", 1.05, 0.08, 6, (0, 0, 0.36), (0, 0, math.radians(30)), HULL2))
    parts.append(sph("dome", 0.62, (0, 0, 0.45), 10, 5, (1, 1, 0.8), HULL))
    parts.append(box("barrel_sleeve", 0.34, 0.5, 0.3, (0, 0.55, 0.78), (0, 0, 0), HULL2))
    parts.append(box("barrel", 0.18, 0.9, 0.18, (0, 1.0, 0.78), (0, 0, 0), HULL2))
    parts.append(box("muzzle", 0.28, 0.16, 0.28, (0, 1.5, 0.78), (0, 0, 0), DARK))
    parts.append(box("muzzle_glow", 0.1, 0.05, 0.1, (0, 1.6, 0.78), (0, 0, 0), CY))
    for i in range(6):
        a = math.radians(60 * i)
        parts.append(box(f"led{i}", 0.12, 0.12, 0.07, (0.92 * math.cos(a), 0.92 * math.sin(a), 0.38), (0, 0, a), CY))
    parts.append(sph("eye", 0.16, (0, 0.36, 0.88), 8, 4, (1, 1, 1), CY))
    o = join(parts, "Picket")
    return o

def lobber():
    parts = []
    parts.append(cyl("base", 1.2, 0.34, 8, (0, 0, 0.17), (0, 0, math.radians(22.5)), BRONZE))
    parts.append(cyl("base_top", 0.95, 0.2, 8, (0, 0, 0.44), (0, 0, math.radians(22.5)), HULL2))
    for i in range(4):
        a = math.radians(45 + 90 * i)
        parts.append(box(f"foot{i}", 0.42, 0.42, 0.2, (1.12 * math.cos(a), 1.12 * math.sin(a), 0.1), (0, 0, a), HULL2))
        parts.append(box(f"foot_led{i}", 0.1, 0.1, 0.05, (1.12 * math.cos(a), 1.12 * math.sin(a), 0.22), (0, 0, a), AM))
    # trunnion block + tilted mortar tube (55 deg up, pointing +Y)
    parts.append(box("trunnion", 1.0, 0.5, 0.6, (0, 0, 0.8), (0, 0, 0), BRONZE))
    tilt = math.radians(-55)
    parts.append(cyl("tube", 0.34, 1.7, 8, (0, 0.42, 1.55), (tilt, 0, 0), HULL2, r2=0.42))
    parts.append(cyl("tube_collar", 0.5, 0.2, 8, (0, 0.95, 1.95), (tilt, 0, 0), BRONZE))
    parts.append(cyl("tube_mouth", 0.3, 0.1, 8, (0, 0.92, 1.9), (tilt, 0, 0), DARK))
    parts.append(sph("shell_glow", 0.2, (0, 0.88, 1.84), 8, 4, (1, 1, 1), AM))
    return join(parts, "Lobber")

def skiff():
    parts = []
    # arrow hull as a custom prism
    bm = bmesh.new()
    top = [(0, 1.3, 0.25), (0.8, -0.6, 0.2), (0, -0.25, 0.3), (-0.8, -0.6, 0.2)]
    bot = [(0, 1.3, -0.05), (0.8, -0.6, -0.05), (0, -0.25, -0.1), (-0.8, -0.6, -0.05)]
    tv = [bm.verts.new(v) for v in top]; bv = [bm.verts.new(v) for v in bot]
    bm.faces.new(tv)
    bm.faces.new(bv[::-1])
    for i in range(4):
        j = (i + 1) % 4
        bm.faces.new((tv[i], bv[i], bv[j], tv[j]) if False else (tv[j], bv[j], bv[i], tv[i]))
    me = bpy.data.meshes.new("skiff_hull"); bm.to_mesh(me); bm.free()
    o = bpy.data.objects.new("hull", me); bpy.context.collection.objects.link(o); finish(o, PLUM)
    bpy.context.view_layer.objects.active = o
    bpy.ops.object.mode_set(mode="EDIT"); bpy.ops.mesh.normals_make_consistent(inside=False); bpy.ops.object.mode_set(mode="OBJECT")
    parts.append(o)
    parts.append(sph("canopy", 0.3, (0, 0.3, 0.3), 8, 5, (0.8, 1.5, 0.7), CY))
    parts.append(box("wing_l", 0.5, 0.35, 0.08, (-0.78, -0.4, 0.12), (0, 0, math.radians(8)), HULL2))
    parts.append(box("wing_r", 0.5, 0.35, 0.08, (0.78, -0.4, 0.12), (0, 0, math.radians(-8)), HULL2))
    parts.append(cyl("gun_l", 0.07, 0.9, 6, (-0.55, 0.45, 0.1), (math.radians(90), 0, 0), DARK))
    parts.append(cyl("gun_r", 0.07, 0.9, 6, (0.55, 0.45, 0.1), (math.radians(90), 0, 0), DARK))
    parts.append(box("thruster_l", 0.2, 0.22, 0.2, (-0.32, -0.62, 0.12), (0, 0, 0), DARK))
    parts.append(box("thruster_r", 0.2, 0.22, 0.2, (0.32, -0.62, 0.12), (0, 0, 0), DARK))
    parts.append(box("flame_l", 0.14, 0.12, 0.14, (-0.32, -0.76, 0.12), (0, 0, 0), MG))
    parts.append(box("flame_r", 0.14, 0.12, 0.14, (0.32, -0.76, 0.12), (0, 0, 0), MG))
    o = join(parts, "Skiff")
    o.location.z = 0.5  # hovers
    bpy.ops.object.transform_apply(location=True)
    return o

def core():
    parts = []
    parts.append(cyl("plinth", 2.1, 0.4, 12, (0, 0, 0.2), (0, 0, 0), HULL))
    parts.append(cyl("plinth_ring", 2.2, 0.1, 12, (0, 0, 0.45), (0, 0, 0), HULL2))
    parts.append(cyl("drum", 1.3, 0.7, 12, (0, 0, 0.85), (0, 0, 0), WHITE, r2=1.05))
    parts.append(cyl("drum_band", 1.08, 0.12, 12, (0, 0, 1.25), (0, 0, 0), TL))
    parts.append(sph("core_orb", 0.75, (0, 0, 1.95), 12, 7, (1, 1, 1), TL, smooth=True))
    bpy.ops.mesh.primitive_torus_add(major_segments=14, minor_segments=4, major_radius=1.05, minor_radius=0.07, location=(0, 0, 1.95), rotation=(math.radians(70), 0, math.radians(20)))
    tr = bpy.context.active_object; tr.name = "cradle_ring"; finish(tr, WHITE); parts.append(tr)
    for i in range(4):
        a = math.radians(45 + 90 * i)
        x, y = 1.75 * math.cos(a), 1.75 * math.sin(a)
        parts.append(cyl(f"pylon{i}", 0.14, 1.5, 6, (x, y, 1.1), (0, 0, 0), HULL2, r2=0.09))
        parts.append(sph(f"pylon_tip{i}", 0.12, (x, y, 1.9), 6, 4, (1, 1, 1), TL))
        parts.append(box(f"strut{i}", 0.08, 0.9, 0.08, (x * 0.62, y * 0.62, 1.7), (0, math.radians(0), a + math.radians(90)), HULL2))
    parts.append(box("antenna", 0.06, 0.06, 0.9, (0, 0, 2.95), (0, 0, 0), HULL2))
    parts.append(sph("antenna_tip", 0.1, (0, 0, 3.45), 6, 4, (1, 1, 1), TL))
    return join(parts, "Core")

def drifter():
    rnd = random.Random(7)
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=1, radius=0.9, location=(0, 0, 0.7))
    o = bpy.context.active_object; o.name = "rock"
    for v in o.data.vertices:
        k = 0.78 + rnd.random() * 0.5
        v.co *= k
    o.scale = (1.15, 0.95, 0.8); bpy.ops.object.transform_apply(scale=True)
    finish(o, ROCK)
    parts = [o]
    for i, (x, y, z, r) in enumerate([(-0.5, 0.35, 1.2, 0.3), (0.35, -0.5, 1.15, 0.24), (-0.1, -0.75, 0.55, 0.2)]):
        bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=1, radius=r, location=(x, y, z))
        c = bpy.context.active_object; c.name = f"boulder{i}"; finish(c, ROCK2); parts.append(c)
    # glowing eyes face +Y (travel direction)
    parts.append(sph("eye_l", 0.16, (-0.28, 0.82, 0.82), 8, 5, (1, 0.7, 1), RED))
    parts.append(sph("eye_r", 0.12, (0.26, 0.84, 0.66), 8, 5, (1, 0.7, 1), RED))
    parts.append(box("brow", 0.7, 0.1, 0.1, (0, 0.86, 0.96), (math.radians(8), 0, 0), DARK))
    return join(parts, "Drifter")

MODELS = {}
for fn, nm in ((picket, "Picket"), (lobber, "Lobber"), (skiff, "Skiff"), (core, "Core"), (drifter, "Drifter")):
    o = fn()
    o.location = (0, 0, 0)
    if nm != "Core":
        o.rotation_euler = (0, 0, math.pi)
        bpy.context.view_layer.objects.active = o; o.select_set(True)
        bpy.ops.object.transform_apply(rotation=True)
    bpy.context.view_layer.objects.active = o
    MODELS[nm] = o

# stats
stats = {}
dg = bpy.context.evaluated_depsgraph_get()
for nm, o in MODELS.items():
    me = o.evaluated_get(dg).to_mesh()
    me.calc_loop_triangles()
    stats[nm] = {"tris": len(me.loop_triangles), "verts": len(me.vertices), "materials": len(o.data.materials),
                 "size": [round(x, 2) for x in o.dimensions]}
    o.evaluated_get(dg).to_mesh_clear()

# glTF export, one file per model
for nm, o in MODELS.items():
    bpy.ops.object.select_all(action="DESELECT")
    o.select_set(True); bpy.context.view_layer.objects.active = o
    bpy.ops.export_scene.gltf(filepath=f"{OUT}/gltf/{nm.lower()}.glb", export_format="GLB", use_selection=True,
                              export_apply=True, export_yup=True, export_cameras=False, export_lights=False)
    stats[nm]["glb_bytes"] = os.path.getsize(f"{OUT}/gltf/{nm.lower()}.glb")

# scene: ground, lights, camera
bpy.ops.mesh.primitive_cylinder_add(vertices=48, radius=3.4, depth=0.1, location=(0, 0, -0.06))
gnd = bpy.context.active_object; gnd.name = "ground"; finish(gnd, mat("ground", hexrgb("#161C3C"), 0.9))
for p in gnd.data.polygons: p.use_smooth = True

w = bpy.data.worlds.new("w"); sc.world = w; w.use_nodes = True
bg = w.node_tree.nodes["Background"]; bg.inputs[0].default_value = (*hexrgb("#0E1226"), 1); bg.inputs[1].default_value = 0.8

def light(name, kind, loc, energy, rgb=(1, 1, 1), rot=None, size=None):
    d = bpy.data.lights.new(name, kind); d.energy = energy; d.color = rgb
    if size is not None:
        if kind == "SUN": d.angle = size
        else: d.shadow_soft_size = size
    o = bpy.data.objects.new(name, d); bpy.context.collection.objects.link(o); o.location = loc
    if rot: o.rotation_euler = rot
    return o

light("key", "SUN", (0, 0, 5), 3.2, (1.0, 0.93, 0.82), (math.radians(50), math.radians(10), math.radians(-35)), 0.12)
light("rim", "SUN", (0, 0, 5), 1.6, (0.45, 0.7, 1.0), (math.radians(65), 0, math.radians(150)), 0.2)
light("fill", "SUN", (0, 0, 5), 0.5, (0.8, 0.8, 1.0), (math.radians(30), 0, math.radians(100)), 0.5)

cam_d = bpy.data.cameras.new("cam"); cam_d.type = "ORTHO"
cam = bpy.data.objects.new("cam", cam_d); bpy.context.collection.objects.link(cam); sc.camera = cam

for eng in ("BLENDER_EEVEE", "BLENDER_EEVEE_NEXT", "CYCLES"):
    try:
        sc.render.engine = eng; break
    except Exception:
        continue
if sc.render.engine == "CYCLES":
    sc.cycles.samples = 24; sc.cycles.use_denoising = False
else:
    try: sc.eevee.taa_render_samples = 24
    except Exception: pass
sc.render.film_transparent = False
sc.render.image_settings.file_format = "PNG"
sc.view_settings.view_transform = "Standard"

def place_cam(yaw_deg, dist=9.0, elev=35.0, scale=4.6):
    cam_d.ortho_scale = scale
    e = math.radians(elev); y = math.radians(yaw_deg)
    cam.location = (dist * math.cos(e) * math.sin(y), -dist * math.cos(e) * math.cos(y), dist * math.sin(e) + 0.9)
    d = Vector((0, 0, 0.9)) - cam.location
    cam.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()

SCALES = {"Picket": 3.6, "Lobber": 4.3, "Skiff": 3.3, "Core": 6.4, "Drifter": 3.4}
for nm in MODELS:
    for other, o in MODELS.items(): o.hide_render = (other != nm)
    gnd.scale = (SCALES[nm] / 4.6,) * 2 + (1,)
    sc.render.resolution_x = sc.render.resolution_y = 384
    for yaw in (45, 135, 225, 315):
        place_cam(yaw, scale=SCALES[nm])
        sc.render.filepath = f"{OUT}/raw/{nm.lower()}_{yaw}.png"
        bpy.ops.render.render(write_still=True)
    # hero at larger res
    sc.render.resolution_x = sc.render.resolution_y = 640
    place_cam(45, scale=SCALES[nm] * 0.92, elev=32)
    sc.render.filepath = f"{OUT}/raw/{nm.lower()}_hero.png"
    bpy.ops.render.render(write_still=True)
    # wireframe-ish: solidify off, use freestyle? keep: workbench wire render

INKFLAT = mat("ink_flat", hexrgb("#0E1226"), 1.0)
WIREM = mat("wire_cy", hexrgb("#4FE3FF"), 0.5, 0, hexrgb("#4FE3FF"), 3.0)
gnd.hide_render = True
for nm, o in MODELS.items():
    o.hide_render = True
for nm, o in MODELS.items():
    d1 = o.copy(); d1.data = o.data.copy(); bpy.context.collection.objects.link(d1)
    d1.data.materials.clear(); d1.data.materials.append(INKFLAT)
    d2 = o.copy(); d2.data = o.data.copy(); bpy.context.collection.objects.link(d2)
    d2.data.materials.clear(); d2.data.materials.append(WIREM)
    wm = d2.modifiers.new("wf", "WIREFRAME"); wm.thickness = 0.018; wm.use_even_offset = True
    d1.hide_render = False; d2.hide_render = False
    sc.render.resolution_x = sc.render.resolution_y = 384
    place_cam(45, scale=SCALES[nm])
    sc.render.filepath = f"{OUT}/raw/{nm.lower()}_wire.png"
    bpy.ops.render.render(write_still=True)
    d1.hide_render = True; d2.hide_render = True
import json
json.dump(stats, open(OUT + "/stats.json", "w"), indent=1)
print("STATS", json.dumps(stats))

_sp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "scene_pass.py")
if os.path.exists(_sp):
    exec(compile(open(_sp, encoding="utf-8").read(), _sp, "exec"))

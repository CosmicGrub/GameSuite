"""
Gem Cascade "3D" look: generates the baked sprite atlas procedurally and ORIGINALLY (no imported assets).

Run headless (Blender 4.x / 5.x; nothing else to install):

    blender -b --python scripts/gen_gem_cascade_atlas.py -- --out app/src/main/assets/styles/gem-cascade

Writes, for each size in --sizes (default 160 and 96):
    atlas_<size>.png      8 columns x 3 rows of square sprites, transparent background
    atlas_<size>.txt      line manifest: "<name> <x> <y> <w> <h>" (parsed by style/SpriteAtlas.kt)
Sprites are lit from the top-left, seen from a fixed 35 degree elevation with an orthographic camera, so every
sprite keeps the same scale and anchor (the sprite centre is the cell centre). Shadows are NOT baked: the app draws a
soft contact shadow so the pieces can fall and bounce over it.

Gem shapes follow the engine's GemShape order (CIRCLE, SQUARE, TRIANGLE, DIAMOND, STAR, HEXAGON, CROSS) and the
Okabe-Ito based colours of GemKinds.argb, so the 3D look stays colour-blind safe (shape AND colour).
"""
import argparse
import math
import os
import sys

import bpy
import bmesh
from mathutils import Vector, Matrix

# ---------------------------------------------------------------------------------------------------------------
# colours (kept in step with GemKinds.argb)
GEM_COLOURS = [
    (0x56, 0xB4, 0xE9), (0xE6, 0x9F, 0x00), (0x00, 0xB3, 0x86), (0xF0, 0x66, 0x3A),
    (0xD9, 0x8B, 0xB8), (0xF0, 0xE4, 0x42), (0x8B, 0x7C, 0xFF),
]

# slot order = atlas order (row-major, 8 columns)
SPRITES = [
    "gem0", "gem1", "gem2", "gem3", "gem4", "gem5", "gem6", "prism",
    "flare_row", "flare_col", "burst", "nova", "cage", "stone3", "stone2", "stone1",
    "spider", "butterfly", "treasure",
]
COLS = 8


def srgb_to_linear(v):
    v = v / 255.0
    return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4


def lin(rgb):
    return (srgb_to_linear(rgb[0]), srgb_to_linear(rgb[1]), srgb_to_linear(rgb[2]), 1.0)


# ---------------------------------------------------------------------------------------------------------------
# scene
def reset_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = 96
    scene.cycles.use_denoising = True
    scene.cycles.max_bounces = 8
    scene.cycles.transmission_bounces = 8
    scene.render.film_transparent = True
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.render.image_settings.color_depth = "8"
    scene.render.image_settings.compression = 100
    scene.view_settings.view_transform = "Standard"
    scene.view_settings.look = "None"
    scene.display_settings.display_device = "sRGB"

    # world: a soft studio gradient so glossy gems have something to reflect
    world = bpy.data.worlds.new("Studio")
    world.use_nodes = True
    nt = world.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = nt.nodes.new("ShaderNodeOutputWorld")
    bg = nt.nodes.new("ShaderNodeBackground")
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    tex = nt.nodes.new("ShaderNodeTexCoord")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(tex.outputs["Generated"], sep.inputs["Vector"])
    nt.links.new(sep.outputs["Z"], ramp.inputs["Fac"])
    ramp.color_ramp.elements[0].position = 0.3
    ramp.color_ramp.elements[0].color = (0.10, 0.11, 0.16, 1)
    ramp.color_ramp.elements[1].position = 0.75
    ramp.color_ramp.elements[1].color = (0.85, 0.88, 1.0, 1)
    nt.links.new(ramp.outputs["Color"], bg.inputs["Color"])
    bg.inputs["Strength"].default_value = 0.55
    nt.links.new(bg.outputs["Background"], out.inputs["Surface"])
    scene.world = world

    # camera: orthographic, 35 deg elevation, looking at the origin
    cam_data = bpy.data.cameras.new("Cam")
    cam_data.type = "ORTHO"
    cam_data.ortho_scale = 2.0
    cam = bpy.data.objects.new("Cam", cam_data)
    scene.collection.objects.link(cam)
    el = math.radians(35.0)
    dist = 10.0
    cam.location = (0.0, -dist * math.cos(el), dist * math.sin(el))
    cam.rotation_euler = (math.radians(90.0) - el, 0.0, 0.0)
    scene.camera = cam

    # lights: key from the top-left, cool fill from the right, warm rim from behind
    def area(name, loc, energy, colour, size):
        d = bpy.data.lights.new(name, "AREA")
        d.energy = energy
        d.color = colour
        d.size = size
        o = bpy.data.objects.new(name, d)
        o.location = loc
        direction = Vector((0, 0, 0)) - Vector(loc)
        o.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
        scene.collection.objects.link(o)

    area("Key", (-4.0, -5.0, 7.0), 330.0, (1.0, 0.97, 0.92), 4.0)
    area("Fill", (5.0, -4.0, 3.0), 110.0, (0.80, 0.88, 1.0), 5.0)
    area("Rim", (1.0, 5.0, 4.0), 200.0, (1.0, 0.9, 0.8), 3.0)
    return scene


def clear_objects():
    for o in list(bpy.data.objects):
        if o.type in {"MESH", "CURVE"}:
            bpy.data.objects.remove(o, do_unlink=True)
    for m in list(bpy.data.meshes):
        if m.users == 0:
            bpy.data.meshes.remove(m)


# ---------------------------------------------------------------------------------------------------------------
# materials
def gem_material(name, rgb, glass=0.0):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    col = lin(rgb)
    bsdf.inputs["Base Color"].default_value = col
    bsdf.inputs["Metallic"].default_value = 0.05
    bsdf.inputs["Roughness"].default_value = 0.26
    if "Transmission Weight" in bsdf.inputs:
        bsdf.inputs["Transmission Weight"].default_value = glass
    if "Specular IOR Level" in bsdf.inputs:
        bsdf.inputs["Specular IOR Level"].default_value = 0.6
    if "Coat Weight" in bsdf.inputs:
        bsdf.inputs["Coat Weight"].default_value = 0.35
        bsdf.inputs["Coat Roughness"].default_value = 0.2
    # a faint self-glow keeps the saturated colour readable in shadow
    if "Emission Color" in bsdf.inputs:
        bsdf.inputs["Emission Color"].default_value = col
        bsdf.inputs["Emission Strength"].default_value = 0.35
    return mat


def flat_material(name, rgb, rough=0.5, metal=0.0, emit=0.0):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Base Color"].default_value = lin(rgb)
    bsdf.inputs["Roughness"].default_value = rough
    bsdf.inputs["Metallic"].default_value = metal
    if emit > 0 and "Emission Strength" in bsdf.inputs:
        bsdf.inputs["Emission Color"].default_value = lin(rgb)
        bsdf.inputs["Emission Strength"].default_value = emit
    return mat


def emissive_material(name, rgb, strength=4.0):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    em = nt.nodes.new("ShaderNodeEmission")
    em.inputs["Color"].default_value = lin(rgb)
    em.inputs["Strength"].default_value = strength
    nt.links.new(em.outputs["Emission"], out.inputs["Surface"])
    return mat


def rainbow_material(name):
    """The prism: hue follows the surface normal, so every facet shows a different spectral colour."""
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    geo = nt.nodes.new("ShaderNodeNewGeometry")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    mix = nt.nodes.new("ShaderNodeMath")
    mix.operation = "ADD"
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    nt.links.new(geo.outputs["Normal"], sep.inputs["Vector"])
    nt.links.new(sep.outputs["X"], mix.inputs[0])
    mix.inputs[1].default_value = 0.0
    nt.links.new(mix.outputs["Value"], ramp.inputs["Fac"])
    cols = [(0xE5, 0x39, 0x35), (0xFF, 0xB3, 0x00), (0x43, 0xA0, 0x47), (0x1E, 0x88, 0xE5), (0x8E, 0x24, 0xAA)]
    el = ramp.color_ramp.elements
    el[0].position = 0.0
    el[0].color = lin(cols[0])
    el[1].position = 1.0
    el[1].color = lin(cols[4])
    for i, p in ((1, 0.25), (2, 0.5), (3, 0.75)):
        e = ramp.color_ramp.elements.new(p)
        e.color = lin(cols[i])
    # remap normal.x (-1..1) -> 0..1
    mapr = nt.nodes.new("ShaderNodeMapRange")
    mapr.inputs["From Min"].default_value = -1.0
    mapr.inputs["From Max"].default_value = 1.0
    nt.links.new(sep.outputs["X"], mapr.inputs["Value"])
    nt.links.new(mapr.outputs["Result"], ramp.inputs["Fac"])
    nt.links.new(ramp.outputs["Color"], bsdf.inputs["Base Color"])
    if "Emission Color" in bsdf.inputs:
        nt.links.new(ramp.outputs["Color"], bsdf.inputs["Emission Color"])
        bsdf.inputs["Emission Strength"].default_value = 0.35
    bsdf.inputs["Roughness"].default_value = 0.08
    bsdf.inputs["Metallic"].default_value = 0.2
    return mat


# ---------------------------------------------------------------------------------------------------------------
# geometry helpers
def finish(obj, mat, bevel=0.035, segs=3, smooth=False):
    obj.data.materials.clear()
    obj.data.materials.append(mat)
    bpy.context.view_layer.objects.active = obj
    obj.select_set(True)
    if bevel > 0:
        m = obj.modifiers.new("Bevel", "BEVEL")
        m.width = bevel
        m.segments = segs
        m.limit_method = "ANGLE"
        m.angle_limit = math.radians(25)
        bpy.ops.object.modifier_apply(modifier=m.name)
    if smooth:
        bpy.ops.object.shade_smooth()
    else:
        bpy.ops.object.shade_flat()
    obj.select_set(False)
    return obj


def new_obj_from_bm(name, bm):
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    obj = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(obj)
    return obj


def prism_gem(sides, radius, height, top_scale, rot=0.0, name="gem"):
    """A cut gem: faceted girdle with a flat table on top and a pavilion tapering to a point underneath."""
    bm = bmesh.new()
    girdle = [Vector((radius * math.cos(rot + 2 * math.pi * i / sides), radius * math.sin(rot + 2 * math.pi * i / sides), 0.0)) for i in range(sides)]
    top = [Vector((v.x * top_scale, v.y * top_scale, height * 0.55)) for v in girdle]
    tip = bm.verts.new((0, 0, -height * 0.45))
    g = [bm.verts.new(v) for v in girdle]
    t = [bm.verts.new(v) for v in top]
    for i in range(sides):
        j = (i + 1) % sides
        bm.faces.new((g[i], g[j], t[j], t[i]))
        bm.faces.new((g[j], g[i], tip))
    bm.faces.new(list(reversed(t)))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_obj_from_bm(name, bm)


def star_gem(points, r_out, r_in, depth, name="star"):
    bm = bmesh.new()
    ring = []
    for i in range(points * 2):
        r = r_out if i % 2 == 0 else r_in
        a = math.pi / 2 + i * math.pi / points
        ring.append((r * math.cos(a), r * math.sin(a)))
    top_c = bm.verts.new((0, 0, depth))
    bot_c = bm.verts.new((0, 0, -depth * 0.55))
    zt = [bm.verts.new((x, y, depth * 0.2)) for x, y in ring]
    zb = [bm.verts.new((x, y, -depth * 0.1)) for x, y in ring]
    n = len(ring)
    for i in range(n):
        j = (i + 1) % n
        bm.faces.new((zt[i], zt[j], top_c))
        bm.faces.new((zb[j], zb[i], bot_c))
        bm.faces.new((zb[i], zb[j], zt[j], zt[i]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return new_obj_from_bm(name, bm)


def round_box(sx, sy, sz, bevel=0.1, name="box"):
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=1.0)
    bmesh.ops.scale(bm, vec=(sx, sy, sz), verts=bm.verts)
    return new_obj_from_bm(name, bm)


def place(obj, z=0.0, rot=0.0):
    obj.location = (0, 0, z)
    obj.rotation_euler = (0, 0, rot)
    return obj


# ---------------------------------------------------------------------------------------------------------------
# the sprites. Each returns the list of objects it created. All are about 1.4 units wide in a 2.0 unit frame.
def build(name):
    if name.startswith("gem") and name != "gem":
        k = int(name[3:])
        mat = gem_material("m_" + name, GEM_COLOURS[k], glass=0.0)
        if k == 0:    # CIRCLE: a rounded cabochon
            bpy.ops.mesh.primitive_uv_sphere_add(segments=20, ring_count=10, radius=0.66)
            o = bpy.context.active_object
            o.scale = (1.0, 1.0, 0.78)
            return [finish(o, mat, bevel=0, smooth=True)]
        if k == 1:    # SQUARE: princess cut
            o = prism_gem(4, 0.72, 0.62, 0.74, rot=math.pi / 4, name=name)
            return [finish(o, mat, bevel=0.03)]
        if k == 2:    # TRIANGLE
            o = prism_gem(3, 0.82, 0.62, 0.62, rot=math.pi / 2, name=name)
            return [finish(o, mat, bevel=0.03)]
        if k == 3:    # DIAMOND: tall rhombus
            o = prism_gem(4, 0.74, 0.95, 0.5, rot=0.0, name=name)
            return [finish(o, mat, bevel=0.025)]
        if k == 4:    # STAR
            o = star_gem(5, 0.86, 0.40, 0.42, name=name)
            return [finish(o, mat, bevel=0.03)]
        if k == 5:    # HEXAGON
            o = prism_gem(6, 0.76, 0.58, 0.68, rot=0.0, name=name)
            return [finish(o, mat, bevel=0.03)]
        if k == 6:    # CROSS: two crossing bars
            a = round_box(1.5, 0.52, 0.5, name=name + "a")
            b = round_box(0.52, 1.5, 0.5, name=name + "b")
            return [finish(a, mat, bevel=0.1), finish(b, mat, bevel=0.1)]
    if name == "prism":
        o = prism_gem(8, 0.78, 0.8, 0.55, rot=math.pi / 8, name=name)
        return [finish(o, rainbow_material("m_prism"), bevel=0.02)]
    if name == "flare_row":
        o = round_box(1.65, 0.16, 0.16, name=name)
        o.location = (0, 0, 0.55)
        return [finish(o, emissive_material("m_flare", (255, 255, 255), 6.0), bevel=0.06)]
    if name == "flare_col":
        o = round_box(0.16, 1.65, 0.16, name=name)
        o.location = (0, 0, 0.55)
        return [finish(o, emissive_material("m_flare2", (255, 255, 255), 6.0), bevel=0.06)]
    if name == "burst":
        bpy.ops.mesh.primitive_torus_add(major_radius=0.78, minor_radius=0.075, major_segments=36, minor_segments=10)
        o = bpy.context.active_object
        o.location = (0, 0, 0.5)
        bpy.ops.mesh.primitive_uv_sphere_add(segments=12, ring_count=8, radius=0.18, location=(0, 0, 0.5))
        c = bpy.context.active_object
        m = emissive_material("m_burst", (255, 255, 255), 5.0)
        return [finish(o, m, bevel=0, smooth=True), finish(c, m, bevel=0, smooth=True)]
    if name == "nova":
        o = star_gem(8, 0.92, 0.36, 0.14, name=name)
        o.location = (0, 0, 0.55)
        return [finish(o, emissive_material("m_nova", (255, 255, 255), 5.0), bevel=0.01)]
    if name == "cage":
        objs = []
        m = flat_material("m_cage", (74, 78, 87), rough=0.35, metal=0.9)
        for i in (-1, 0, 1):
            x = round_box(0.07, 1.55, 0.07, name="cv%d" % i)
            x.location = (i * 0.42, 0, 0.45)
            objs.append(finish(x, m, bevel=0.02, segs=2))
            y = round_box(1.55, 0.07, 0.07, name="ch%d" % i)
            y.location = (0, i * 0.42, 0.45)
            objs.append(finish(y, m, bevel=0.02, segs=2))
        for sx in (-0.78, 0.78):
            for sy in (-0.78, 0.78):
                p = round_box(0.1, 0.1, 0.9, name="post")
                p.location = (sx, sy, 0.45)
                objs.append(finish(p, m, bevel=0.02, segs=2))
        return objs
    if name.startswith("stone"):
        hp = int(name[5])
        bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=2, radius=0.7)
        o = bpy.context.active_object
        # deterministic lumpiness
        me = o.data
        for i, v in enumerate(me.vertices):
            s = 1.0 + 0.16 * math.sin(i * 12.9898) + 0.1 * math.cos(i * 78.233)
            v.co *= s
        o.scale = (1.15, 1.0, 0.72 if hp > 1 else 0.55)
        shade = {3: (72, 76, 86), 2: (110, 115, 126), 1: (150, 154, 164)}[hp]
        return [finish(o, flat_material("m_" + name, shade, rough=0.85), bevel=0)]
    if name == "spider":
        objs = []
        body = flat_material("m_spider", (43, 43, 51), rough=0.4, metal=0.2)
        bpy.ops.mesh.primitive_uv_sphere_add(segments=16, ring_count=10, radius=0.42, location=(0, 0, 0.3))
        b = bpy.context.active_object
        b.scale = (1.0, 1.15, 0.8)
        objs.append(finish(b, body, bevel=0, smooth=True))
        for side in (-1, 1):
            for k in range(4):
                a = math.radians(-50 + k * 33)
                leg = round_box(0.7, 0.07, 0.07, name="leg")
                leg.location = (side * 0.55, math.sin(a) * 0.5, 0.18)
                leg.rotation_euler = (0, math.radians(side * 25), side * a * 0.6)
                objs.append(finish(leg, body, bevel=0.01, segs=1))
        eye = emissive_material("m_eye", (255, 77, 77), 7.0)
        for ex in (-0.15, 0.15):
            bpy.ops.mesh.primitive_uv_sphere_add(segments=8, ring_count=6, radius=0.07, location=(ex, -0.4, 0.45))
            objs.append(finish(bpy.context.active_object, eye, bevel=0, smooth=True))
        return objs
    if name == "butterfly":
        objs = []
        wing = gem_material("m_wing", (255, 95, 162), glass=0.0)
        for side in (-1, 1):
            up = round_box(0.8, 0.07, 0.62, name="wu")
            up.location = (side * 0.45, 0.0, 0.7)
            up.rotation_euler = (0, side * math.radians(-18), side * math.radians(-22))
            objs.append(finish(up, wing, bevel=0.025))
            lo = round_box(0.6, 0.07, 0.46, name="wl")
            lo.location = (side * 0.34, 0.0, 0.25)
            lo.rotation_euler = (0, side * math.radians(14), side * math.radians(24))
            objs.append(finish(lo, wing, bevel=0.025))
        bpy.ops.mesh.primitive_uv_sphere_add(segments=10, ring_count=6, radius=0.1, location=(0, 0, 0.4))
        bd = bpy.context.active_object
        bd.scale = (1, 1, 3.4)
        objs.append(finish(bd, flat_material("m_body", (30, 30, 36), rough=0.5), bevel=0, smooth=True))
        return objs
    if name == "treasure":
        o = prism_gem(4, 0.7, 1.0, 0.0001, rot=0.0, name=name)
        o.scale = (1, 1, 1.15)
        return [finish(o, flat_material("m_gold", (255, 193, 7), rough=0.22, metal=1.0), bevel=0.02)]
    raise KeyError(name)


# ---------------------------------------------------------------------------------------------------------------
def render_all(size, tmp_dir):
    scene = bpy.context.scene
    scene.render.resolution_x = size
    scene.render.resolution_y = size
    scene.render.resolution_percentage = 100
    paths = {}
    for name in SPRITES:
        clear_objects()
        for o in build(name):
            o.select_set(False)
        path = os.path.join(tmp_dir, "%s_%d.png" % (name, size))
        scene.render.filepath = path
        bpy.ops.render.render(write_still=True)
        paths[name] = path
        print("rendered", name, size)
    return paths


def pack(paths, size, out_dir):
    import numpy as np
    rows = (len(SPRITES) + COLS - 1) // COLS
    w, h = COLS * size, rows * size
    atlas = np.zeros((h, w, 4), dtype=np.float32)
    lines = []
    for idx, name in enumerate(SPRITES):
        img = bpy.data.images.load(paths[name])
        arr = np.array(img.pixels[:], dtype=np.float32).reshape(img.size[1], img.size[0], 4)
        bpy.data.images.remove(img)
        col, row = idx % COLS, idx // COLS
        # Blender images are bottom-up; row 0 of the atlas is the TOP row
        y0 = (rows - 1 - row) * size
        atlas[y0:y0 + size, col * size:(col + 1) * size, :] = arr
        lines.append("%s %d %d %d %d" % (name, col * size, row * size, size, size))
    out = bpy.data.images.new("atlas_%d" % size, width=w, height=h, alpha=True)
    out.pixels.foreach_set(atlas.ravel())
    out.filepath_raw = os.path.join(out_dir, "atlas_%d.png" % size)
    out.file_format = "PNG"
    out.save()
    with open(os.path.join(out_dir, "atlas_%d.txt" % size), "w", newline="\n") as f:
        f.write("# gem-cascade baked sprites, generated by scripts/gen_gem_cascade_atlas.py\n")
        f.write("size %d\n" % size)
        f.write("\n".join(lines) + "\n")
    print("wrote atlas", size, w, h)


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    p = argparse.ArgumentParser()
    p.add_argument("--out", required=True)
    p.add_argument("--sizes", default="160,96")
    p.add_argument("--samples", type=int, default=96)
    a = p.parse_args(argv)
    os.makedirs(a.out, exist_ok=True)
    tmp = os.path.join(a.out, "_tmp")
    os.makedirs(tmp, exist_ok=True)
    reset_scene()
    bpy.context.scene.cycles.samples = a.samples
    for s in [int(x) for x in a.sizes.split(",")]:
        paths = render_all(s, tmp)
        pack(paths, s, a.out)
    for f in os.listdir(tmp):
        os.remove(os.path.join(tmp, f))
    os.rmdir(tmp)


main()

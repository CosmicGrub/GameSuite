"""
DRAFT - NEVER EXECUTED. Part of docs/design/style-packs (SCRIPT.md, Part C3).

Render a model to a transparent PNG frame sequence (turntable or "tumble") for the Full 3D sprite-strip path.
Run headless (example, do not run from the planning session):

    blender -b path/to/model.blend --python blender_render_turntable.py -- \
        --object Die --frames 32 --size 256 --out out/die_red --mode tumble --variant red

Assumptions to verify when this is first used (UNVERIFIED against the Blender version in use):
  * bpy API names below are from Blender 3.x/4.x; engine enum names changed between versions
    (BLENDER_EEVEE vs BLENDER_EEVEE_NEXT in 4.2+). Check `bpy.types.RenderSettings.engine` items.
  * Orthographic camera so every frame keeps identical scale (sprite anchors stay fixed).
Output frames: out/<name>/frame_0000.png ... ; a separate packer script builds the atlas + manifest.
"""
import argparse
import math
import sys

import bpy  # only available inside Blender


def parse_args():
    # Blender passes its own args before "--"; ours come after.
    argv = sys.argv
    argv = argv[argv.index("--") + 1:] if "--" in argv else []
    p = argparse.ArgumentParser()
    p.add_argument("--object", required=True, help="name of the object to render")
    p.add_argument("--frames", type=int, default=32)
    p.add_argument("--size", type=int, default=256, help="square output size in px (render at the largest density bucket)")
    p.add_argument("--out", required=True, help="output directory prefix")
    p.add_argument("--mode", choices=["turntable", "tumble", "settle"], default="turntable")
    p.add_argument("--variant", default="default", help="material variant name recorded in the manifest")
    p.add_argument("--samples", type=int, default=64)
    return p.parse_args(argv)


def setup_render(size, samples):
    scene = bpy.context.scene
    scene.render.resolution_x = size
    scene.render.resolution_y = size
    scene.render.resolution_percentage = 100
    scene.render.film_transparent = True            # alpha background, required for sprites
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.render.image_settings.color_depth = "8"
    # Engine choice: EEVEE is fast, Cycles is more faithful. Pick one deterministic setting.
    try:
        scene.render.engine = "BLENDER_EEVEE_NEXT"  # Blender 4.2+
    except TypeError:
        scene.render.engine = "BLENDER_EEVEE"       # older versions
    if hasattr(scene, "eevee"):
        scene.eevee.taa_render_samples = samples
    scene.view_settings.view_transform = "Standard"  # avoid Filmic/AgX shifting palette colours between pieces
    return scene


def setup_camera(scene, target):
    cam_data = bpy.data.cameras.new("SpriteCam")
    cam_data.type = "ORTHO"
    cam_data.ortho_scale = max(target.dimensions) * 1.6   # margin so the tumble never clips
    cam = bpy.data.objects.new("SpriteCam", cam_data)
    scene.collection.objects.link(cam)
    cam.location = (0.0, -6.0, 3.5)
    direction = target.location - cam.location
    cam.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
    scene.camera = cam
    return cam


def setup_lights(scene):
    """Fixed 3-point rig, identical for every asset so a whole pack looks like one set."""
    rig = [
        ("Key", "AREA", (3.0, -4.0, 5.0), 800.0),
        ("Fill", "AREA", (-4.0, -3.0, 2.0), 250.0),
        ("Rim", "AREA", (0.0, 5.0, 4.0), 400.0),
    ]
    for name, kind, loc, energy in rig:
        data = bpy.data.lights.new(name, kind)
        data.energy = energy
        obj = bpy.data.objects.new(name, data)
        obj.location = loc
        scene.collection.objects.link(obj)


def pose(obj, mode, t):
    """t in [0,1). Rotation is set absolutely each frame so frames are order independent."""
    if mode == "turntable":
        obj.rotation_euler = (0.0, 0.0, 2.0 * math.pi * t)
    elif mode == "tumble":
        # Two-axis spin that decelerates: ease-out so the last frame is the settled pose.
        e = 1.0 - (1.0 - t) ** 3
        obj.rotation_euler = (4.0 * math.pi * e, 2.0 * math.pi * e, 0.0)
        obj.location.z = abs(math.sin(math.pi * 2.0 * t)) * (1.0 - t) * 0.8  # hop that dies out
    elif mode == "settle":
        e = 1.0 - (1.0 - t) ** 2
        obj.location.z = (1.0 - e) * 0.5


def main():
    args = parse_args()
    scene = setup_render(args.size, args.samples)
    obj = bpy.data.objects[args.object]
    setup_camera(scene, obj)
    setup_lights(scene)
    for i in range(args.frames):
        pose(obj, args.mode, i / float(args.frames))
        scene.render.filepath = f"{args.out}/frame_{i:04d}.png"
        bpy.ops.render.render(write_still=True)
    # The manifest (frame count, fps, anchor, shadow offset, variant) is written by the packer step, not here,
    # so rendering stays side-effect free. Print one JSON line for the packer to pick up.
    print({"object": args.object, "mode": args.mode, "frames": args.frames, "size": args.size, "variant": args.variant})


if __name__ == "__main__":
    main()

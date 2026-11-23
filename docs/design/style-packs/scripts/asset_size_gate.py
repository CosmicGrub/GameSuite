#!/usr/bin/env python3
"""
DRAFT - NEVER EXECUTED. Part of docs/design/style-packs (SCRIPT.md, Part C5).

CI size/format gate for style packs. Python standard library only, read-only: it never modifies files.

Usage (do not run from the planning session):
    python -I asset_size_gate.py --budgets style-budgets.json --packs-root app/src/stylepacks --licenses docs/design/style-packs/LICENSES.md

Expected inputs
  style-budgets.json (checked in), example:
    {
      "base_growth_mb": 3.0,
      "packs": {
        "style_full3d":   {"max_mb": 80, "max_texture_px": 1024, "formats": ["webp", "png", "json", "glb"]},
        "style_storybook": {"max_mb": 30, "max_texture_px": 1024, "formats": ["webp", "png", "json", "ogg"]}
      }
    }
  Each pack directory contains manifest.json, example:
    {
      "schema": 1, "id": "style_full3d", "minAppVersion": 1,
      "files": {"dice/die_red.webp": {"sha256": "...", "licenseId": "own-001"}}
    }
  LICENSES.md contains rows whose first cell is the licenseId used in manifests.

Exit code 0 = pass, 1 = failures. Prints a markdown table for the CI step summary.
"""
import argparse
import hashlib
import json
import os
import re
import struct
import sys

MB = 1024.0 * 1024.0


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", head[16:24])


def webp_size(path):
    """Minimal WebP header reader (VP8, VP8L, VP8X). Returns (w, h) or None. Draft: verify against real files."""
    with open(path, "rb") as f:
        d = f.read(40)
    if d[:4] != b"RIFF" or d[8:12] != b"WEBP":
        return None
    kind = d[12:16]
    if kind == b"VP8X":
        w = 1 + int.from_bytes(d[24:27], "little")
        h = 1 + int.from_bytes(d[27:30], "little")
        return (w, h)
    if kind == b"VP8L":
        bits = int.from_bytes(d[21:25], "little")
        return (1 + (bits & 0x3FFF), 1 + ((bits >> 14) & 0x3FFF))
    if kind == b"VP8 ":
        return (int.from_bytes(d[26:28], "little") & 0x3FFF, int.from_bytes(d[28:30], "little") & 0x3FFF)
    return None


def load_license_ids(path):
    ids = set()
    if not os.path.exists(path):
        return ids
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = re.match(r"\|\s*([A-Za-z0-9_.-]+)\s*\|", line)
            if m:
                ids.add(m.group(1))
    return ids


def check_pack(name, pack_dir, rule, license_ids, failures):
    manifest_path = os.path.join(pack_dir, "manifest.json")
    if not os.path.exists(manifest_path):
        failures.append(f"{name}: manifest.json missing")
        return 0.0
    with open(manifest_path, encoding="utf-8") as f:
        manifest = json.load(f)
    listed = manifest.get("files", {})
    total = 0
    on_disk = set()
    for root, _dirs, files in os.walk(pack_dir):
        for fn in files:
            full = os.path.join(root, fn)
            rel = os.path.relpath(full, pack_dir).replace(os.sep, "/")
            if rel == "manifest.json":
                total += os.path.getsize(full)
                continue
            on_disk.add(rel)
            total += os.path.getsize(full)
            ext = rel.rsplit(".", 1)[-1].lower() if "." in rel else ""
            if ext not in rule.get("formats", []):
                failures.append(f"{name}: format not allowed: {rel}")
            if rel not in listed:
                failures.append(f"{name}: file not in manifest: {rel}")
                continue
            entry = listed[rel]
            if entry.get("sha256") != sha256_of(full):
                failures.append(f"{name}: hash mismatch: {rel}")
            if entry.get("licenseId") not in license_ids:
                failures.append(f"{name}: licence entry missing for {rel} (licenseId={entry.get('licenseId')})")
            dims = png_size(full) if ext == "png" else webp_size(full) if ext == "webp" else None
            if dims and max(dims) > rule.get("max_texture_px", 1024):
                failures.append(f"{name}: texture too large {dims}: {rel}")
    for rel in listed:
        if rel not in on_disk:
            failures.append(f"{name}: manifest lists missing file: {rel}")
    size_mb = total / MB
    if size_mb > rule["max_mb"]:
        failures.append(f"{name}: {size_mb:.1f} MB exceeds budget {rule['max_mb']} MB")
    return size_mb


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--budgets", required=True)
    ap.add_argument("--packs-root", required=True)
    ap.add_argument("--licenses", required=True)
    ap.add_argument("--base-growth-mb", type=float, default=None, help="measured base growth supplied by the build step")
    args = ap.parse_args()

    with open(args.budgets, encoding="utf-8") as f:
        budgets = json.load(f)
    license_ids = load_license_ids(args.licenses)
    failures = []
    rows = []
    for name, rule in budgets["packs"].items():
        pack_dir = os.path.join(args.packs_root, name)
        if not os.path.isdir(pack_dir):
            failures.append(f"{name}: pack directory missing")
            continue
        size_mb = check_pack(name, pack_dir, rule, license_ids, failures)
        rows.append((name, size_mb, rule["max_mb"], "ok" if size_mb <= rule["max_mb"] else "OVER"))
    if args.base_growth_mb is not None and args.base_growth_mb > budgets["base_growth_mb"]:
        failures.append(f"base growth {args.base_growth_mb:.1f} MB exceeds {budgets['base_growth_mb']} MB")

    print("| pack | MB | budget MB | status |\n|---|---|---|---|")
    for name, size_mb, budget, status in rows:
        print(f"| {name} | {size_mb:.1f} | {budget} | {status} |")
    if failures:
        print("\nFAILURES:")
        for msg in failures:
            print(f"- {msg}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

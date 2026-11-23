#!/usr/bin/env bash
# DRAFT - NEVER EXECUTED. Part of docs/design/style-packs (SCRIPT.md, Part C2).
#
# Optimise a GLB exported from Blender for the optional GLB/engine path (stage 11, owner decision D3).
# The default Full 3D path uses sprite strips and does NOT need this script.
#
# Usage (do not run from the planning session):
#   ./optimize_glb.sh input.glb output.glb [max_texture_px]
#
# Tools assumed (all UNVERIFIED versions; record the exact versions into the pack manifest when first run):
#   - gltf-transform CLI  (npm i -g @gltf-transform/cli)   : prune, dedup, resize, meshopt/draco, texture compression
#   - gltfpack            (meshoptimizer)                  : alternative single-binary optimiser
#   - toktx / KTX-Software                                 : only needed for KTX2/Basis textures
#   - gltf_validator      (Khronos)                        : validation before and after
#
# Safety: reads input, writes only to the output path, never edits the source GLB, no network access.

set -euo pipefail

IN="${1:?input glb required}"
OUT="${2:?output glb required}"
MAXTEX="${3:-512}"

command -v gltf-transform >/dev/null || { echo "gltf-transform not found (see header)"; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "[1/6] validate input"
# gltf_validator exits non-zero on errors; warnings are tolerated but printed.
command -v gltf_validator >/dev/null && gltf_validator "$IN" || echo "validator missing: skipped"

echo "[2/6] prune unused data, dedupe accessors/materials"
gltf-transform prune "$IN" "$TMP/a.glb"
gltf-transform dedup "$TMP/a.glb" "$TMP/b.glb"

echo "[3/6] resize textures to <= ${MAXTEX}px"
gltf-transform resize "$TMP/b.glb" "$TMP/c.glb" --width "$MAXTEX" --height "$MAXTEX"

echo "[4/6] compress textures (WebP keeps decode simple; KTX2 only if the engine supports it)"
gltf-transform webp "$TMP/c.glb" "$TMP/d.glb" --quality 85
# KTX2 alternative (needs toktx):  gltf-transform etc1s "$TMP/c.glb" "$TMP/d.glb"   # or: uastc

echo "[5/6] mesh compression (meshopt is cheaper to decode than draco on phones; verify with the chosen engine)"
gltf-transform meshopt "$TMP/d.glb" "$TMP/e.glb" --level medium

cp "$TMP/e.glb" "$OUT"

echo "[6/6] validate output and print sizes"
command -v gltf_validator >/dev/null && gltf_validator "$OUT" || true
ls -l "$IN" "$OUT" | awk '{print $5, $9}'

# A size budget check is done separately by asset_size_gate.py (reads style-budgets.json).

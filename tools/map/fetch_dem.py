#!/usr/bin/env python3
"""Fetches a real elevation model for the playable area.

Source: AWS Open Data "Terrain Tiles" (Mapzen terrarium PNG tiles, public domain /
CC0). Height encoding: height = (R * 256 + G + B / 256) - 32768 metres.

Output: tools/map/dem.bin (int16 little-endian grid) + tools/map/dem.json metadata.
"""
import json
import math
import os
import struct
import sys
import urllib.request
from io import BytesIO

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_BIN = os.path.join(HERE, "dem.bin")
OUT_JSON = os.path.join(HERE, "dem.json")

BBOX = dict(south=43.8400, west=10.6550, north=43.9900, east=11.0450)
ZOOM = 13
STEP = 64.0  # metres between heightmap samples
LAT0, LON0 = 43.93330, 10.91670
M_LAT = 110574.0
M_LON = 111320.0 * math.cos(math.radians(LAT0))


def lonlat_to_px(lon, lat, z):
    n = 2 ** z
    x = (lon + 180.0) / 360.0 * n
    lat_rad = math.radians(lat)
    y = (1.0 - math.log(math.tan(lat_rad) + 1.0 / math.cos(lat_rad)) / math.pi) / 2.0 * n
    return x, y


def main():
    world_w = (BBOX["east"] - BBOX["west"]) * M_LON
    world_h = (BBOX["north"] - BBOX["south"]) * M_LAT
    cols = int(math.ceil(world_w / STEP)) + 1
    rows = int(math.ceil(world_h / STEP)) + 1
    print("grid %dx%d (%.1f x %.1f m)" % (cols, rows, world_w, world_h))

    x0px, y0px = lonlat_to_px(BBOX["west"], BBOX["north"], ZOOM)
    x1px, y1px = lonlat_to_px(BBOX["east"], BBOX["south"], ZOOM)
    tx0, ty0 = int(math.floor(x0px)), int(math.floor(y0px))
    tx1, ty1 = int(math.floor(x1px)), int(math.floor(y1px))
    n = 2 ** ZOOM
    tiles = {}
    for ty in range(ty0, ty1 + 1):
        for tx in range(tx0, tx1 + 1):
            url = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png" % (ZOOM, tx, ty)
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "PTA-map-builder/1.0"})
                data = urllib.request.urlopen(req, timeout=60).read()
                tiles[(tx, ty)] = Image.open(BytesIO(data)).convert("RGB")
                print("tile %d/%d ok" % (tx, ty), flush=True)
            except Exception as e:  # noqa: BLE001
                print("tile %d/%d failed: %s" % (tx, ty, e), flush=True)
    if not tiles:
        print("no tiles", file=sys.stderr)
        return 1

    heights = [[0.0] * cols for _ in range(rows)]
    for r in range(rows):
        z = -world_h / 2.0 + r * STEP  # south positive, matches game +Z
        lat = LAT0 - z / M_LAT
        for c in range(cols):
            x = -world_w / 2.0 + c * STEP
            lon = LON0 + x / M_LON
            px, py = lonlat_to_px(lon, lat, ZOOM)
            tx, ty = int(math.floor(px)), int(math.floor(py))
            img = tiles.get((tx, ty))
            if img is None:
                heights[r][c] = 60.0
                continue
            ix = min(255, max(0, int((px - tx) * 256)))
            iy = min(255, max(0, int((py - ty) * 256)))
            rr, gg, bb = img.getpixel((ix, iy))
            heights[r][c] = (rr * 256.0 + gg + bb / 256.0) - 32768.0

    minx = -world_w / 2.0
    minz = -world_h / 2.0
    flat = [0] * (cols * rows)
    for r in range(rows):
        for c in range(cols):
            flat[r * cols + c] = int(round(heights[r][c]))
    with open(OUT_BIN, "wb") as f:
        f.write(struct.pack("<iifff", cols, rows, minx, minz, STEP))
        f.write(struct.pack("<%dh" % len(flat), *flat))
    meta = dict(cols=cols, rows=rows, minX=minx, minZ=minz, step=STEP,
                minHeight=min(flat), maxHeight=max(flat),
                attribution="Elevation: Mapzen/AWS Terrain Tiles (public domain)",
                zoom=ZOOM)
    with open(OUT_JSON, "w") as f:
        json.dump(meta, f, indent=1)
    print("wrote", OUT_BIN, os.path.getsize(OUT_BIN), "bytes; heights",
          min(flat), "..", max(flat))
    try:
        from PIL import ImageDraw
        img = Image.new("L", (cols, rows))
        img.putdata([int((h - min(flat)) / max(1, max(flat) - min(flat)) * 255) for h in flat])
        img.resize((cols * 2, rows * 2)).save(os.path.join(HERE, "dem_preview.png"))
    except Exception:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())

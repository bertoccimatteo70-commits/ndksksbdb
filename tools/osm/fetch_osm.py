#!/usr/bin/env python3
"""PTA map data fetcher.

Downloads OpenStreetMap data (ODbL, (c) OpenStreetMap contributors) for the
Pistoia province playable area, in tiles, and stores raw JSON under
tools/osm/raw/.  The raw data is converted to the compact runtime format by
tools/map/build_map.py.

Usage:
    python3 fetch_osm.py --layers roads,landuse,pois,buildings
    python3 fetch_osm.py --layers roads --tiles 0,0 1,0
"""
import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
os.makedirs(RAW, exist_ok=True)

# Playable area: Pescia (west) .. Montale/Quarrata (east),
# Montecatini Terme/Casalguidi (south) .. Pistoia hills (north).
BBOX = dict(south=43.8400, west=10.6550, north=43.9900, east=11.0450)
ROWS, COLS = 3, 4

UA = "PTA-PistoiaTownAdventures-MapBuilder/1.0 (OpenStreetMap ODbL; local build)"
ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.osm.jp/api/interpreter",
]

LAYER_QUERIES = {
    "roads": 'way["highway"]({bbox});way["railway"]({bbox});way["waterway"]({bbox});',
    "landuse": ('way["landuse"]({bbox});way["natural"]({bbox});way["leisure"]({bbox});'
                'way["water"]({bbox});relation["landuse"]({bbox});relation["natural"]({bbox});'
                'relation["water"]({bbox});relation["leisure"]({bbox});'),
    "pois": ('node["amenity"]({bbox});node["shop"]({bbox});node["place"]({bbox});'
             'node["tourism"]({bbox});node["leisure"]({bbox});node["railway"]({bbox});'
             'node["historic"]({bbox});'),
    "buildings": 'way["building"]({bbox});',
}


def tile_bbox(r, c):
    dlat = (BBOX["north"] - BBOX["south"]) / ROWS
    dlon = (BBOX["east"] - BBOX["west"]) / COLS
    s = BBOX["south"] + r * dlat
    w = BBOX["west"] + c * dlon
    return s, w, s + dlat, w + dlon


def fmt_bbox(b):
    return "%.5f,%.5f,%.5f,%.5f" % b


def post(endpoint, query, timeout=400):
    data = urllib.parse.urlencode({"data": query}).encode()
    req = urllib.request.Request(endpoint, data=data, headers={
        "User-Agent": UA,
        "Accept": "application/json",
    })
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read()
    return body


def fetch_one(layer, r, c, force=False):
    out = os.path.join(RAW, "%s_r%d_c%d.json" % (layer, r, c))
    if os.path.exists(out) and not force:
        try:
            with open(out, "rb") as f:
                head = f.read(64)
            if b'"elements"' in head or os.path.getsize(out) > 1024 * 1024:
                print("[skip] %s exists (%d bytes)" % (os.path.basename(out), os.path.getsize(out)), flush=True)
                return True
        except OSError:
            pass
    bbox = fmt_bbox(tile_bbox(r, c))
    q = ("[out:json][timeout:300][maxsize:1073741824];(" + LAYER_QUERIES[layer].format(bbox=bbox) + ");out geom;")
    last = None
    for attempt in range(len(ENDPOINTS) * 4):
        ep = ENDPOINTS[attempt % len(ENDPOINTS)]
        try:
            t0 = time.time()
            body = post(ep, q)
            if not body.lstrip().startswith(b"{") and not body.lstrip().startswith(b"["):
                raise RuntimeError("non-json response: %r" % body[:200])
            doc = json.loads(body)
            els = doc.get("elements", [])
            with open(out, "wb") as f:
                f.write(body)
            print("[ok] %s %s -> %d elements, %.1f MB in %.0fs" % (
                layer, bbox, len(els), len(body) / 1048576.0, time.time() - t0), flush=True)
            return True
        except Exception as e:  # noqa: BLE001
            last = e
            wait = min(60, 5 * (attempt + 1))
            print("[retry %d] %s %s: %s (sleep %ds)" % (attempt + 1, layer, bbox, e, wait), flush=True)
            time.sleep(wait)
    print("[FAIL] %s %s: %s" % (layer, bbox, last), flush=True)
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--layers", default="roads,landuse,pois,buildings")
    ap.add_argument("--tiles", nargs="*", default=None, help="r,c pairs")
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()
    layers = [x.strip() for x in args.layers.split(",") if x.strip()]
    for l in layers:
        if l not in LAYER_QUERIES:
            print("unknown layer", l)
            return 2
    tiles = []
    if args.tiles:
        for t in args.tiles:
            r, c = [int(v) for v in t.split(",")]
            tiles.append((r, c))
    else:
        tiles = [(r, c) for r in range(ROWS) for c in range(COLS)]
    ok = True
    for layer in layers:
        for (r, c) in tiles:
            ok = fetch_one(layer, r, c, args.force) and ok
    print("DONE ok=%s" % ok, flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

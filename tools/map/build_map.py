#!/usr/bin/env python3
"""PTA map baker.

Converts the real OpenStreetMap extract (ODbL, (c) OpenStreetMap contributors)
for the Pistoia province playable area into the compact binary world file used
by the Android game, and renders a top-down preview used to verify geographic
fidelity.

    PYTHONPATH=tools/pylibs python3 tools/map/build_map.py \
        --pbf tools/osm/pbf/centro.osm.pbf

Outputs:
    app/src/main/assets/map/pta_map.bin
    app/src/main/assets/map/pta_map.json
    docs/map_preview.png
"""
import argparse
import json
import math
import os
import struct
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT_BIN = os.path.join(ROOT, "app", "src", "main", "assets", "map", "pta_map.bin")
OUT_JSON = os.path.join(ROOT, "app", "src", "main", "assets", "map", "pta_map.json")
PREVIEW = os.path.join(ROOT, "docs", "map_preview.png")
OUT_LANDMARKS = os.path.join(ROOT, "app", "src", "main", "assets", "map", "landmarks.json")
DEM_BIN = os.path.join(HERE, "dem.bin")
DEM_JSON = os.path.join(HERE, "dem.json")

# Projection origin: Pistoia city centre (Piazza del Duomo).
LAT0, LON0 = 43.93330, 10.91670
M_PER_DEG_LAT = 110574.0
M_PER_DEG_LON = 111320.0 * math.cos(math.radians(LAT0))

# Playable area: Pescia/Montecatini (south-west) .. Montale/Quarrata (east).
BBOX = dict(south=43.8400, west=10.6550, north=43.9900, east=11.0450)
CHUNK = 512.0

ROAD_CLASS = {
    "motorway": 0, "motorway_link": 0, "trunk": 1, "trunk_link": 1,
    "primary": 2, "primary_link": 2, "secondary": 3, "secondary_link": 3,
    "tertiary": 4, "tertiary_link": 4, "residential": 5, "unclassified": 6,
    "service": 7, "living_street": 8, "pedestrian": 9, "footway": 10,
    "cycleway": 11, "path": 12, "steps": 13, "track": 14, "busway": 5,
}
ROAD_WIDTH = [14.0, 12.0, 10.5, 9.0, 8.0, 6.5, 6.0, 4.5, 5.5, 6.0, 2.2, 2.5, 1.6, 1.8, 3.0, 4.0]
DRIVABLE = set(range(0, 9))

AREA_KIND = {
    "residential": 1, "commercial": 2, "retail": 2, "industrial": 3,
    "farmland": 4, "farmyard": 4, "orchard": 4, "vineyard": 4, "meadow": 5,
    "forest": 6, "wood": 6, "grass": 5, "grassland": 5, "park": 7,
    "garden": 7, "recreation_ground": 7, "pitch": 8, "cemetery": 9,
    "quarry": 10, "railway": 11, "parking": 12, "water": 13, "wetland": 13,
    "school": 14, "university": 14, "hospital": 14, "allotments": 4,
    "greenfield": 5, "scrub": 6,
}
NATURAL_KIND = {
    "wood": 6, "scrub": 6, "grassland": 5, "heath": 5, "water": 13,
    "wetland": 13, "bare_rock": 10, "sand": 10, "tree_row": 6,
}
LEISURE_KIND = {
    "park": 7, "garden": 7, "pitch": 8, "sports_centre": 8,
    "playground": 7, "nature_reserve": 6, "golf_course": 7,
}

POI_CATEGORY = {
    "fuel": 0, "charging_station": 0, "car_repair": 12, "car": 12,
    "restaurant": 2, "bar": 2, "cafe": 2, "fast_food": 2, "pub": 2,
    "pharmacy": 3, "hospital": 3, "clinic": 3, "doctors": 3, "dentist": 3,
    "bank": 4, "atm": 4, "school": 5, "college": 5, "kindergarten": 5,
    "place_of_worship": 6, "hotel": 7, "guest_house": 7, "hostel": 7,
    "parking": 8, "parking_space": 8, "bus_station": 9,
    "police": 15, "fire_station": 15, "townhall": 15, "courthouse": 15,
    "post_office": 12, "marketplace": 12, "cinema": 12, "theatre": 12,
    "library": 12, "museum": 12, "supermarket": 1, "convenience": 1,
}
PLACE_CATEGORY = {"city": 11, "town": 11, "village": 11, "suburb": 11, "hamlet": 11, "quarter": 11}

FACADE_COLORS = [
    (216, 187, 138), (201, 164, 112), (228, 209, 173), (197, 141, 104),
    (235, 227, 208), (203, 197, 181), (174, 118, 91), (222, 197, 152),
    (189, 170, 136), (231, 219, 196), (160, 143, 121), (212, 178, 131),
    (240, 236, 225), (186, 156, 118),
]
ROOF_COLORS = [
    (168, 84, 58), (150, 72, 50), (183, 98, 66), (128, 66, 51),
    (140, 134, 126), (108, 104, 98), (156, 92, 66), (120, 118, 112),
]
AREA_KIND_NAMES = {
    0: "other", 1: "residential", 2: "commercial", 3: "industrial", 4: "farmland",
    5: "grass", 6: "forest", 7: "park", 8: "pitch", 9: "cemetery", 10: "quarry",
    11: "railway", 12: "parking", 13: "water", 14: "civic",
}


def fnum(tags, key):
    v = tags.get(key)
    if not v:
        return None
    try:
        return float(str(v).replace("m", "").replace(",", ".").strip())
    except ValueError:
        return None


def project(lat, lon):
    return ((lon - LON0) * M_PER_DEG_LON, -(lat - LAT0) * M_PER_DEG_LAT)


def in_bbox(lat, lon):
    return BBOX["south"] <= lat <= BBOX["north"] and BBOX["west"] <= lon <= BBOX["east"]


def polygon_area(pts):
    a = 0.0
    n = len(pts)
    for i in range(n):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % n]
        a += x1 * y2 - x2 * y1
    return abs(a) * 0.5


def simplify(pts, tol):
    """Douglas-Peucker on a closed ring (first == last)."""
    if len(pts) < 6:
        return pts
    closed = pts[0] == pts[-1]
    if not closed:
        pts = pts + [pts[0]]
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        if b <= a + 1:
            continue
        ax, ay = pts[a]
        bx, by = pts[b]
        dx, dy = bx - ax, by - ay
        norm = math.hypot(dx, dy)
        best, bestd = -1, -1.0
        for i in range(a + 1, b):
            px, py = pts[i]
            if norm < 1e-9:
                d = math.hypot(px - ax, py - ay)
            else:
                d = abs(dy * px - dx * py + bx * ay - by * ax) / norm
            if d > bestd:
                best, bestd = i, d
        if bestd > tol:
            keep[best] = True
            stack.append((a, best))
            stack.append((best, b))
    out = [p for i, p in enumerate(pts) if keep[i]]
    if len(out) < 4:
        out = pts[:4] if len(pts) >= 4 else pts
    # Drop duplicated closing vertex; the game closes rings itself.
    if out[0] == out[-1]:
        out = out[:-1]
    return out


def decimate(pts, maxpts=14):
    if len(pts) <= maxpts:
        return pts
    step = len(pts) / float(maxpts)
    return [pts[int(i * step)] for i in range(maxpts)]


class Bin:
    def __init__(self, path):
        self.f = open(path, "wb")

    def u8(self, v):
        self.f.write(struct.pack("<B", v & 0xFF))

    def u16(self, v):
        self.f.write(struct.pack("<H", v & 0xFFFF))

    def i16(self, v):
        self.f.write(struct.pack("<h", max(-32768, min(32767, int(v)))))

    def u32(self, v):
        self.f.write(struct.pack("<I", v & 0xFFFFFFFF))

    def i32(self, v):
        self.f.write(struct.pack("<i", int(v)))

    def f32(self, v):
        self.f.write(struct.pack("<f", float(v)))

    def raw(self, b):
        self.f.write(b)

    def close(self):
        self.f.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pbf", required=True)
    ap.add_argument("--limit-buildings", type=int, default=0,
                    help="debug: stop after N buildings")
    args = ap.parse_args()

    import osmium

    print("[1/4] indexing nodes in bbox ...", flush=True)
    node_xy = {}

    class NodePass(osmium.SimpleHandler):
        def node(self, n):
            lat, lon = n.location.lat, n.location.lon
            if in_bbox(lat, lon):
                x, z = project(lat, lon)
                # Quantise to centimetres and pack into one int (fits int64).
                xi = int(round(x * 100.0))
                zi = int(round(z * 100.0))
                node_xy[n.id] = (xi << 32) | (zi & 0xFFFFFFFF)

    NodePass().apply_file(args.pbf)
    print("      nodes: %d" % len(node_xy), flush=True)

    names = {}
    name_list = []

    def name_id(name):
        if not name:
            return -1
        if name in names:
            return names[name]
        names[name] = len(name_list)
        name_list.append(name)
        return names[name]

    buildings, roads, areas, pois = [], [], [], []

    class WayPass(osmium.SimpleHandler):
        def way(self, w):
            tags = w.tags
            if not tags:
                return
            coords = []
            for nd in w.nodes:
                packed = node_xy.get(nd.ref)
                if packed is None:
                    continue
                xi = packed >> 32
                zi = packed & 0xFFFFFFFF
                if zi >= 0x80000000:
                    zi -= 0x100000000
                coords.append((xi / 100.0, zi / 100.0))
            if len(coords) < 2:
                return
            nid = w.id

            hw = tags.get("highway")
            if hw in ROAD_CLASS:
                cls = ROAD_CLASS[hw]
                if tags.get("area") == "yes" and hw in ("pedestrian", "residential"):
                    cls = 9
                width = fnum(tags, "width") or ROAD_WIDTH[cls]
                width = max(1.4, min(24.0, width))
                if len(coords) > 160:
                    coords = decimate(coords, 160)
                flags = 0
                if tags.get("oneway") in ("yes", "true", "1", "-1"):
                    flags |= 1
                if tags.get("bridge") not in (None, "no"):
                    flags |= 2
                if tags.get("tunnel") not in (None, "no"):
                    flags |= 4
                lanes = 0
                try:
                    lanes = int(tags.get("lanes", "0"))
                except ValueError:
                    lanes = 0
                if lanes <= 0:
                    lanes = 2 if cls <= 6 else 1
                roads.append(dict(
                    id=nid, cls=cls, flags=flags, lanes=min(8, lanes),
                    width=width, name=name_id(tags.get("name")),
                    pts=coords, drivable=(cls in DRIVABLE)))
                return

            b = tags.get("building")
            if b and b not in ("no", "roof") and len(coords) >= 4:
                if coords[0] != coords[-1]:
                    coords = coords + [coords[0]]
                area = polygon_area(coords[:-1])
                if area < 14.0:
                    return
                pts = decimate(simplify(coords, 0.55), 16)
                if len(pts) < 3:
                    return
                lv = fnum(tags, "building:levels")
                hgt = fnum(tags, "height")
                if hgt is None:
                    if lv:
                        hgt = lv * 3.2 + 1.0
                    else:
                        hgt = 0.0
                if hgt <= 0:
                    hgt = 7.0
                if area > 3500:
                    hgt = min(hgt, 10.0)
                hgt = max(3.0, min(48.0, hgt))
                seed = (nid * 2654435761) & 0xFFFFFFFF
                fac = FACADE_COLORS[seed % len(FACADE_COLORS)]
                roof = ROOF_COLORS[(seed >> 7) % len(ROOF_COLORS)]
                buildings.append(dict(
                    pts=pts, h=hgt, fac=fac, roof=roof,
                    name=name_id(tags.get("name")) if (tags.get("name") and
                                                      (tags.get("tourism") or tags.get("historic") or
                                                       tags.get("amenity") in ("townhall", "place_of_worship", "museum", "library")))
                    else -1,
                    area=area))
                return

            # Areas / land cover / water.
            kind = 0
            if "landuse" in tags:
                kind = AREA_KIND.get(tags["landuse"], 0)
            elif "natural" in tags:
                kind = NATURAL_KIND.get(tags["natural"], 0)
            elif "leisure" in tags:
                kind = LEISURE_KIND.get(tags["leisure"], 0)
            elif "waterway" in tags and tags["waterway"] in ("riverbank", "dock"):
                kind = 13
            elif "amenity" in tags:
                kind = AREA_KIND.get(tags["amenity"], 0)
            if kind and len(coords) >= 4:
                if coords[0] != coords[-1]:
                    coords = coords + [coords[0]]
                area = polygon_area(coords[:-1])
                if area < 60.0:
                    return
                pts = decimate(simplify(coords, 1.2), 40)
                if len(pts) < 3:
                    return
                areas.append(dict(kind=kind, pts=pts, name=name_id(tags.get("name")), area=area))

    WayPass().apply_file(args.pbf)
    print("      buildings=%d roads=%d areas=%d" % (len(buildings), len(roads), len(areas)), flush=True)

    print("[2/4] scanning POIs ...", flush=True)

    class PoiPass(osmium.SimpleHandler):
        def node(self, n):
            tags = n.tags
            if not tags:
                return
            lat, lon = n.location.lat, n.location.lon
            if not in_bbox(lat, lon):
                return
            x, z = project(lat, lon)
            cat = None
            if tags.get("highway") == "traffic_signals":
                cat = 10
            elif tags.get("amenity") in POI_CATEGORY:
                cat = POI_CATEGORY[tags["amenity"]]
            elif tags.get("shop"):
                cat = 1
            elif tags.get("tourism") == "hotel":
                cat = 7
            elif tags.get("railway") == "station":
                cat = 9
            elif tags.get("place") in PLACE_CATEGORY:
                cat = PLACE_CATEGORY[tags["place"]]
            if cat is None:
                return
            nm = tags.get("name") if cat in (9, 11) else None
            pois.append(dict(x=x, z=z, cat=cat, name=name_id(nm)))

    PoiPass().apply_file(args.pbf)
    print("      pois=%d names=%d" % (len(pois), len(name_list)), flush=True)

    print("[3/4] writing binary ...", flush=True)
    os.makedirs(os.path.dirname(OUT_BIN), exist_ok=True)
    xs = [p[0] for b in buildings for p in b["pts"]] + [p[0] for r in roads for p in r["pts"]]
    zs = [p[1] for b in buildings for p in b["pts"]] + [p[1] for r in roads for p in r["pts"]]
    minx, maxx = min(xs), max(xs)
    minz, maxz = min(zs), max(zs)

    out = Bin(OUT_BIN)
    out.raw(b"PTAMAP01")
    out.u32(2)
    out.f32(LAT0)
    out.f32(LON0)
    out.f32(minx)
    out.f32(minz)
    out.f32(maxx)
    out.f32(maxz)
    out.f32(CHUNK)
    out.u32(len(buildings))
    out.u32(len(roads))
    out.u32(len(areas))
    out.u32(len(pois))
    out.u32(len(name_list))
    out.f32(M_PER_DEG_LAT)
    out.f32(M_PER_DEG_LON)

    # Real elevation grid (Mapzen/AWS terrain tiles, public domain).
    dem_meta = None
    if os.path.exists(DEM_BIN):
        with open(DEM_BIN, "rb") as f:
            cols, rows, dminx, dminz, step = struct.unpack("<iifff", f.read(20))
            dem = f.read(cols * rows * 2)
        with open(DEM_JSON) as f:
            dem_meta = json.load(f)
        out.u32(cols)
        out.u32(rows)
        out.f32(dminx)
        out.f32(dminz)
        out.f32(step)
        out.raw(dem)
        print("elevation grid %dx%d, %d..%d m" % (cols, rows, dem_meta["minHeight"], dem_meta["maxHeight"]), flush=True)
    else:
        print("WARNING: no dem.bin, writing flat elevation", flush=True)
        out.u32(2)
        out.u32(2)
        out.f32(minx)
        out.f32(minz)
        out.f32(max(1.0, maxx - minx))
        out.raw(struct.pack("<4h", 60, 60, 60, 60))

    # name table
    for nm in name_list:
        b = nm.encode("utf-8")
        out.u16(len(b))
        out.raw(b)

    for b in buildings:
        out.u8(1 if b["name"] >= 0 else 0)
        out.u8(len(b["pts"]))
        out.f32(b["h"])
        out.u8(FACADE_COLORS.index(b["fac"]))
        out.u8(ROOF_COLORS.index(b["roof"]))
        out.u16(0)
        out.i32(b["name"])
        for (x, z) in b["pts"]:
            out.f32(x)
            out.f32(z)

    for r in roads:
        out.i32(r["id"])
        out.u8(r["cls"])
        out.u8(r["flags"])
        out.u8(r["lanes"])
        out.u8(1 if r["drivable"] else 0)
        out.f32(r["width"])
        out.i32(r["name"])
        out.u16(len(r["pts"]))
        for (x, z) in r["pts"]:
            out.f32(x)
            out.f32(z)

    for a in areas:
        out.u8(a["kind"])
        out.i32(a["name"])
        out.u16(len(a["pts"]))
        for (x, z) in a["pts"]:
            out.f32(x)
            out.f32(z)

    for p in pois:
        out.f32(p["x"])
        out.f32(p["z"])
        out.u8(p["cat"])
        out.i32(p["name"])
    out.close()
    size = os.path.getsize(OUT_BIN)

    towns = [dict(name=name_list[p["name"]], x=p["x"], z=p["z"], cat=p["cat"])
             for p in pois if p["cat"] == 11 and p["name"] >= 0]
    towns = sorted(towns, key=lambda t: (t["x"] ** 2 + t["z"] ** 2))

    # Named landmarks / monuments: real coordinates used by game content and the
    # map screen. Only public places and monuments are kept. Deduplicated by name.
    lm = {}
    for b in buildings:
        if b["name"] < 0:
            continue
        nm = name_list[b["name"]]
        pts = b["pts"]
        bx = sum(p[0] for p in pts) / len(pts)
        bz = sum(p[1] for p in pts) / len(pts)
        cur = lm.get(nm)
        if cur is None or b["area"] > cur["area"]:
            lm[nm] = dict(name=nm, x=bx, z=bz, area=b["area"], h=b["h"])
    landmarks = sorted(lm.values(), key=lambda v: -v["area"])[:500]
    with open(OUT_LANDMARKS, "w") as f:
        json.dump(dict(
            attribution="Map data (c) OpenStreetMap contributors, ODbL 1.0",
            towns=towns, landmarks=landmarks), f, indent=1)
    print("landmarks: %d -> %s" % (len(landmarks), OUT_LANDMARKS), flush=True)
    meta = dict(
        version=1, origin=dict(lat=LAT0, lon=LON0),
        bounds=dict(minX=minx, minZ=minz, maxX=maxx, maxZ=maxz),
        chunkSize=CHUNK,
        counts=dict(buildings=len(buildings), roads=len(roads), areas=len(areas),
                    pois=len(pois), names=len(name_list)),
        elevation=dem_meta,
        attribution="Map data (c) OpenStreetMap contributors, ODbL 1.0 - https://www.openstreetmap.org/copyright",
        projection="Local tangent plane, metres, +X east, +Z south, origin Piazza del Duomo",
        towns=towns,
        generated_from="Geofabrik italy/centro extract",
    )
    with open(OUT_JSON, "w") as f:
        json.dump(meta, f, indent=2)

    print("[4/4] rendering preview ...", flush=True)
    os.makedirs(os.path.dirname(PREVIEW), exist_ok=True)
    W = 1800
    scale = W / (maxx - minx)
    H = int((maxz - minz) * scale) + 1
    img = Image.new("RGB", (W, H), (46, 58, 48))
    d = ImageDraw.Draw(img, "RGBA")

    def tp(x, z):
        return ((x - minx) * scale, (z - minz) * scale)

    area_col = {
        1: (96, 94, 88), 2: (104, 92, 82), 3: (88, 86, 84), 4: (120, 124, 78),
        5: (92, 110, 74), 6: (62, 88, 60), 7: (78, 116, 70), 8: (110, 116, 74),
        9: (100, 104, 96), 10: (120, 116, 108), 11: (70, 70, 72), 12: (94, 94, 96),
        13: (52, 82, 112), 14: (110, 100, 96),
    }
    for a in sorted(areas, key=lambda a: -a["area"]):
        d.polygon([tp(*p) for p in a["pts"]], fill=area_col.get(a["kind"], (90, 96, 84)))
    for b in buildings:
        d.polygon([tp(*p) for p in b["pts"]], fill=b["fac"])
    road_col = {0: (240, 196, 90), 1: (232, 186, 90), 2: (226, 176, 84), 3: (214, 166, 84),
                4: (206, 158, 82), 5: (190, 188, 180), 6: (176, 174, 166),
                7: (160, 158, 150), 8: (176, 174, 166), 9: (200, 196, 186),
                10: (150, 146, 136), 11: (140, 160, 140), 12: (120, 130, 110), 13: (140, 136, 128),
                14: (150, 130, 100)}
    for r in roads:
        w = max(1, int(r["width"] * scale * 0.55))
        d.line([tp(*p) for p in r["pts"]], fill=road_col.get(r["cls"], (180, 178, 170)), width=w)
    try:
        font = ImageFont.truetype(os.path.join(ROOT, "app/src/main/assets/fonts/DejaVuSans-Bold.ttf"), 18)
        small = ImageFont.truetype(os.path.join(ROOT, "app/src/main/assets/fonts/DejaVuSans.ttf"), 13)
    except OSError:
        font = small = ImageFont.load_default()
    for t in towns:
        px, pz = tp(t["x"], t["z"])
        if 0 <= px < W and 0 <= pz < H:
            d.ellipse([px - 3, pz - 3, px + 3, pz + 3], fill=(255, 240, 160))
            d.text((px + 5, pz - 6), t["name"], fill=(255, 246, 210), font=font,
                   stroke_width=2, stroke_fill=(20, 22, 20))
    d.rectangle([8, 8, 8 + 560, 8 + 62], fill=(0, 0, 0, 150))
    d.text((16, 14), "PTA - Pistoia Town Adventures / mappa reale OSM", fill=(255, 255, 255), font=font)
    d.text((16, 40), "Map data (c) OpenStreetMap contributors - ODbL 1.0", fill=(210, 210, 210), font=small)
    img.save(PREVIEW)
    print("preview: %s (%dx%d)" % (PREVIEW, W, H), flush=True)

    print("wrote %s (%.2f MB)" % (OUT_BIN, size / 1048576.0), flush=True)
    print("counts:", meta["counts"], flush=True)
    print("town labels:", len(towns), [t["name"] for t in towns][:24], flush=True)
    print("roads drivable: %d" % sum(1 for r in roads if r["drivable"]), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())

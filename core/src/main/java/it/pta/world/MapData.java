package it.pta.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable, spatial-indexed world geometry baked from OpenStreetMap data.
 *
 * File format is produced by tools/map/build_map.py and documented in
 * docs/MAP_PIPELINE.md. Everything is stored in flat primitive arrays and bucketed
 * by 512 m chunks so the Android renderer can stream the world without holding a
 * mesh for the whole province.
 */
public final class MapData {
    public static final int MAGIC = 0x5041544D; // "PTAM"

    public final float minX, minZ, maxX, maxZ;
    public final float chunkSize;
    public final Building[] buildings;
    public final RoadWay[] roads;
    public final Area[] areas;
    public final Poi[] pois;
    public final String[] names;

    /** Real elevation grid (metres) baked from Mapzen/AWS terrain tiles. */
    public final int demCols, demRows;
    public final float demMinX, demMinZ, demStep;
    private final short[] dem;

    public final int minCX, minCZ, nCX, nCZ;
    private final int[] buildingStart;
    private final int[] buildingItems;
    private final int[] roadStart;
    private final int[] roadItems;
    private final int[] areaStart;
    private final int[] areaItems;
    private final int[] poiStart;
    private final int[] poiItems;

    private MapData(Builder b) {
        this.minX = b.minX;
        this.minZ = b.minZ;
        this.maxX = b.maxX;
        this.maxZ = b.maxZ;
        this.chunkSize = b.chunkSize;
        this.buildings = b.buildings.toArray(new Building[0]);
        this.roads = b.roads.toArray(new RoadWay[0]);
        this.areas = b.areas.toArray(new Area[0]);
        this.pois = b.pois.toArray(new Poi[0]);
        this.names = b.names.toArray(new String[0]);
        this.demCols = b.demCols;
        this.demRows = b.demRows;
        this.demMinX = b.demMinX;
        this.demMinZ = b.demMinZ;
        this.demStep = b.demStep;
        this.dem = b.dem;

        this.minCX = (int) Math.floor(minX / chunkSize);
        this.minCZ = (int) Math.floor(minZ / chunkSize);
        this.nCX = (int) Math.floor(maxX / chunkSize) - minCX + 1;
        this.nCZ = (int) Math.floor(maxZ / chunkSize) - minCZ + 1;

        Buckets bb = new Buckets(nCX * nCZ, minCX, minCZ, nCX);
        for (int i = 0; i < buildings.length; i++) {
            Building bd = buildings[i];
            bb.add(cx(bd.cx), cz(bd.cz), i);
        }
        bb.build();
        buildingStart = bb.start;
        buildingItems = bb.items;

        Buckets rb = new Buckets(nCX * nCZ, minCX, minCZ, nCX);
        for (int i = 0; i < roads.length; i++) {
            RoadWay r = roads[i];
            markBox(rb, i, r.minX, r.minZ, r.maxX, r.maxZ);
        }
        rb.build();
        roadStart = rb.start;
        roadItems = rb.items;

        Buckets ab = new Buckets(nCX * nCZ, minCX, minCZ, nCX);
        for (int i = 0; i < areas.length; i++) {
            Area a = areas[i];
            markBox(ab, i, a.minX, a.minZ, a.maxX, a.maxZ);
        }
        ab.build();
        areaStart = ab.start;
        areaItems = ab.items;

        Buckets pb = new Buckets(nCX * nCZ, minCX, minCZ, nCX);
        for (int i = 0; i < pois.length; i++) {
            Poi p = pois[i];
            pb.add(cx(p.x), cz(p.z), i);
        }
        pb.build();
        poiStart = pb.start;
        poiItems = pb.items;
    }

    private void markBox(Buckets b, int idx, float x0, float z0, float x1, float z1) {
        int c0 = cx(x0), c1 = cx(x1), r0 = cz(z0), r1 = cz(z1);
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                b.add(c, r, idx);
            }
        }
    }

    public int cx(float x) {
        return (int) Math.floor(x / chunkSize);
    }

    public int cz(float z) {
        return (int) Math.floor(z / chunkSize);
    }

    public float chunkOriginX(int cx) {
        return cx * chunkSize;
    }

    public float chunkOriginZ(int cz) {
        return cz * chunkSize;
    }

    private int key(int cx, int cz) {
        int col = cx - minCX;
        int row = cz - minCZ;
        if (col < 0 || row < 0 || col >= nCX || row >= nCZ) {
            return -1;
        }
        return row * nCX + col;
    }

    public int[] buildingsIn(int cx, int cz) {
        return slice(buildingStart, buildingItems, key(cx, cz));
    }

    public int[] roadsIn(int cx, int cz) {
        return slice(roadStart, roadItems, key(cx, cz));
    }

    public int[] areasIn(int cx, int cz) {
        return slice(areaStart, areaItems, key(cx, cz));
    }

    public int[] poisIn(int cx, int cz) {
        return slice(poiStart, poiItems, key(cx, cz));
    }

    private static int[] slice(int[] start, int[] items, int k) {
        if (k < 0 || k >= start.length) {
            return EMPTY;
        }
        int s = start[k];
        int e = start[k + 1];
        int[] out = new int[e - s];
        System.arraycopy(items, s, out, 0, e - s);
        return out;
    }

    private static final int[] EMPTY = new int[0];

    public String name(int idx) {
        if (idx < 0 || idx >= names.length) {
            return null;
        }
        return names[idx];
    }

    /** Closest point on road network to (x,z), searching a local 3x3 chunk window. */
    public RoadHit nearestRoad(float x, float z, boolean drivableOnly, float maxDist) {
        int c = cx(x), r = cz(z);
        RoadHit best = new RoadHit();
        best.dist = maxDist;
        float bestScore = maxDist;
        for (int rr = r - 1; rr <= r + 1; rr++) {
            for (int cc = c - 1; cc <= c + 1; cc++) {
                for (int idx : roadsIn(cc, rr)) {
                    RoadWay w = roads[idx];
                    if (drivableOnly && !w.drivable) {
                        continue;
                    }
                    float px = 0, pz = 0;
                    int seg = -1;
                    float t = 0;
                    float d = closestOnPolyline(w.pts, x, z, bestScore, tmp);
                    if (d < bestScore) {
                        bestScore = d;
                        best.way = w;
                        best.index = idx;
                        best.segment = (int) tmp[0];
                        best.t = tmp[1];
                        best.x = tmp[2];
                        best.z = tmp[3];
                        best.dist = d;
                    }
                }
            }
        }
        return bestScore < maxDist ? best : null;
    }

    private final float[] tmp = new float[4];

    /** Fills out={segment,t,px,pz}; returns distance or Float.MAX_VALUE. */
    public static float closestOnPolyline(float[] pts, float x, float z, float limit, float[] out) {
        float best = limit * limit;
        int bestSeg = -1;
        float bestT = 0, bx = 0, bz = 0;
        for (int i = 0; i + 3 < pts.length; i += 2) {
            float ax = pts[i], az = pts[i + 1];
            float bx2 = pts[i + 2], bz2 = pts[i + 3];
            float dx = bx2 - ax, dz = bz2 - az;
            float len2 = dx * dx + dz * dz;
            float t = len2 > 1e-6f ? ((x - ax) * dx + (z - az) * dz) / len2 : 0;
            t = Math.max(0, Math.min(1, t));
            float px = ax + dx * t, pz = az + dz * t;
            float d2 = (px - x) * (px - x) + (pz - z) * (pz - z);
            if (d2 < best) {
                best = d2;
                bestSeg = i / 2;
                bestT = t;
                bx = px;
                bz = pz;
            }
        }
        if (bestSeg < 0) {
            return Float.MAX_VALUE;
        }
        out[0] = bestSeg;
        out[1] = bestT;
        out[2] = bx;
        out[3] = bz;
        return (float) Math.sqrt(best);
    }

    /** True when a circle intersects a solid building footprint. */
    public boolean blocked(float x, float z, float radius) {
        int c = cx(x), r = cz(z);
        for (int rr = r - 1; rr <= r + 1; rr++) {
            for (int cc = c - 1; cc <= c + 1; cc++) {
                for (int idx : buildingsIn(cc, rr)) {
                    Building b = buildings[idx];
                    if (!b.collides) {
                        continue;
                    }
                    if (x < b.minX - radius || x > b.maxX + radius
                            || z < b.minZ - radius || z > b.maxZ + radius) {
                        continue;
                    }
                    if (b.distanceTo(x, z) < radius) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Pushes a circle out of the nearest overlapping building; returns true if moved. */
    public boolean resolveCollision(float[] pos, float radius) {
        boolean hit = false;
        int c = cx(pos[0]), r = cz(pos[2]);
        for (int rr = r - 1; rr <= r + 1; rr++) {
            for (int cc = c - 1; cc <= c + 1; cc++) {
                for (int idx : buildingsIn(cc, rr)) {
                    Building b = buildings[idx];
                    if (!b.collides) {
                        continue;
                    }
                    if (pos[0] < b.minX - radius || pos[0] > b.maxX + radius
                            || pos[2] < b.minZ - radius || pos[2] > b.maxZ + radius) {
                        continue;
                    }
                    int[] nearest = b.nearestPoint(pos[0], pos[2]);
                    float dx = pos[0] - b.polyX(nearest[0]);
                    float dz = pos[2] - b.polyZ(nearest[0]);
                    float d = (float) Math.sqrt(dx * dx + dz * dz);
                    if (d < radius) {
                        if (d < 1e-4f) {
                            dx = 1;
                            dz = 0;
                            d = 1;
                        }
                        float push = (radius - d) + 0.01f;
                        pos[0] += dx / d * push;
                        pos[2] += dz / d * push;
                        hit = true;
                    }
                }
            }
        }
        return hit;
    }

    public Area areaAt(float x, float z) {
        int c = cx(x), r = cz(z);
        Area best = null;
        int bestRank = -1;
        for (int idx : areasIn(c, r)) {
            Area a = areas[idx];
            if (x < a.minX || x > a.maxX || z < a.minZ || z > a.maxZ) {
                continue;
            }
            if (!a.contains(x, z)) {
                continue;
            }
            int rank = a.kind == 13 ? 40 : (a.kind == 7 || a.kind == 6 ? 30 : (a.kind == 1 ? 20 : 10));
            if (rank > bestRank) {
                bestRank = rank;
                best = a;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- data model

    public static final class Building {
        public final float[] pts;
        public final float height;
        public final int facade, roof;
        public final int nameIdx;
        public final float cx, cz;
        public final float minX, minZ, maxX, maxZ;
        public final float radius;
        public final float area;
        public final boolean collides;

        Building(float[] pts, float height, int facade, int roof, int nameIdx) {
            this.pts = pts;
            this.height = height;
            this.facade = facade;
            this.roof = roof;
            this.nameIdx = nameIdx;
            float sx = 0, sz = 0, mnx = 1e9f, mnz = 1e9f, mxx = -1e9f, mxz = -1e9f;
            int n = pts.length / 2;
            for (int i = 0; i < pts.length; i += 2) {
                sx += pts[i];
                sz += pts[i + 1];
                mnx = Math.min(mnx, pts[i]);
                mxx = Math.max(mxx, pts[i]);
                mnz = Math.min(mnz, pts[i + 1]);
                mxz = Math.max(mxz, pts[i + 1]);
            }
            cx = sx / n;
            cz = sz / n;
            minX = mnx;
            minZ = mnz;
            maxX = mxx;
            maxZ = mxz;
            radius = 0.5f * (float) Math.hypot(mxx - mnx, mxz - mnz);
            collides = radius > 0.6f;
            area = Math.abs(ringArea(pts));
        }

        public int vertexCount() {
            return pts.length / 2;
        }

        public float polyX(int i) {
            return pts[i * 2];
        }

        public float polyZ(int i) {
            return pts[i * 2 + 1];
        }

        public float distanceTo(float x, float z) {
            if (x > minX && x < maxX && z > minZ && z < maxZ && contains(x, z)) {
                return 0;
            }
            int[] np = nearestPoint(x, z);
            return (float) Math.hypot(x - polyX(np[0]), z - polyZ(np[0]));
        }

        public boolean contains(float x, float z) {
            return pointInRing(pts, x, z);
        }

        /** Returns index of the closest polygon vertex (good enough for push-out). */
        public int[] nearestPoint(float x, float z) {
            int best = 0;
            float bd = Float.MAX_VALUE;
            int n = vertexCount();
            for (int i = 0; i < n; i++) {
                float dx = x - polyX(i), dz = z - polyZ(i);
                float d = dx * dx + dz * dz;
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
            ret[0] = best;
            return ret;
        }

        private final int[] ret = new int[1];
    }

    public static final class RoadWay {
        public final int id;
        public final int cls;
        public final int flags;
        public final int lanes;
        public final boolean drivable;
        public final float width;
        public final int nameIdx;
        public final float[] pts;
        public final float length;
        public final float minX, minZ, maxX, maxZ;

        RoadWay(int id, int cls, int flags, int lanes, boolean drivable, float width,
                int nameIdx, float[] pts) {
            this.id = id;
            this.cls = cls;
            this.flags = flags;
            this.lanes = lanes;
            this.drivable = drivable;
            this.width = width;
            this.nameIdx = nameIdx;
            this.pts = pts;
            float len = 0;
            float mnx = 1e9f, mnz = 1e9f, mxx = -1e9f, mxz = -1e9f;
            for (int i = 0; i < pts.length; i += 2) {
                mnx = Math.min(mnx, pts[i]);
                mxx = Math.max(mxx, pts[i]);
                mnz = Math.min(mnz, pts[i + 1]);
                mxz = Math.max(mxz, pts[i + 1]);
                if (i + 3 < pts.length) {
                    len += (float) Math.hypot(pts[i + 2] - pts[i], pts[i + 3] - pts[i + 1]);
                }
            }
            length = len;
            minX = mnx;
            minZ = mnz;
            maxX = mxx;
            maxZ = mxz;
        }

        public boolean oneWay() {
            return (flags & 1) != 0;
        }

        public boolean bridge() {
            return (flags & 2) != 0;
        }

        public boolean tunnel() {
            return (flags & 4) != 0;
        }

        public boolean pedestrianOnly() {
            return cls >= 9;
        }

        public int segments() {
            return pts.length / 2 - 1;
        }
    }

    public static final class Area {
        public final int kind;
        public final int nameIdx;
        public final float[] pts;
        public final float minX, minZ, maxX, maxZ;
        public final float radius;

        Area(int kind, int nameIdx, float[] pts) {
            this.kind = kind;
            this.nameIdx = nameIdx;
            this.pts = pts;
            float mnx = 1e9f, mnz = 1e9f, mxx = -1e9f, mxz = -1e9f;
            for (int i = 0; i < pts.length; i += 2) {
                mnx = Math.min(mnx, pts[i]);
                mxx = Math.max(mxx, pts[i]);
                mnz = Math.min(mnz, pts[i + 1]);
                mxz = Math.max(mxz, pts[i + 1]);
            }
            minX = mnx;
            minZ = mnz;
            maxX = mxx;
            maxZ = mxz;
            radius = 0.5f * (float) Math.hypot(mxx - mnx, mxz - mnz);
        }

        public boolean contains(float x, float z) {
            return pointInRing(pts, x, z);
        }
    }

    public static final class Poi {
        public final float x, z;
        public final int cat;
        public final int nameIdx;

        Poi(float x, float z, int cat, int nameIdx) {
            this.x = x;
            this.z = z;
            this.cat = cat;
            this.nameIdx = nameIdx;
        }
    }

    public static final class RoadHit {
        public RoadWay way;
        public int index;
        public int segment;
        public float t;
        public float x, z;
        public float dist;
    }

    // ------------------------------------------------------------------ helpers

    public static boolean pointInRing(float[] pts, float x, float z) {
        boolean inside = false;
        int n = pts.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = pts[i * 2], zi = pts[i * 2 + 1];
            float xj = pts[j * 2], zj = pts[j * 2 + 1];
            if (((zi > z) != (zj > z)) && (x < (xj - xi) * (z - zi) / (zj - zi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }

    public static float ringArea(float[] pts) {
        float a = 0;
        int n = pts.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            a += pts[j * 2] * pts[i * 2 + 1] - pts[i * 2] * pts[j * 2 + 1];
        }
        return a * 0.5f;
    }

    /** Counting-sort buckets mapping chunk cells to item indices. */
    private static final class Buckets {
        final int nCells, minCX, minCZ, nCX;
        int[] keys = new int[2048];
        int[] vals = new int[2048];
        int n;
        int[] start;
        int[] items;

        Buckets(int size, int minCX, int minCZ, int nCX) {
            this.nCells = size;
            this.minCX = minCX;
            this.minCZ = minCZ;
            this.nCX = nCX;
        }

        int keyOf(int cx, int cz) {
            int col = cx - minCX;
            int row = cz - minCZ;
            if (col < 0 || row < 0 || col >= nCX) {
                return -1;
            }
            int k = row * nCX + col;
            return k < nCells ? k : -1;
        }

        void add(int cx, int cz, int idx) {
            int k = keyOf(cx, cz);
            if (k < 0) {
                return;
            }
            if (n == keys.length) {
                int cap = keys.length * 2;
                keys = java.util.Arrays.copyOf(keys, cap);
                vals = java.util.Arrays.copyOf(vals, cap);
            }
            keys[n] = k;
            vals[n] = idx;
            n++;
        }

        void build() {
            int[] count = new int[nCells];
            for (int i = 0; i < n; i++) {
                count[keys[i]]++;
            }
            start = new int[nCells + 1];
            int acc = 0;
            for (int i = 0; i < nCells; i++) {
                start[i] = acc;
                acc += count[i];
            }
            start[nCells] = acc;
            items = new int[acc];
            int[] cursor = new int[nCells];
            System.arraycopy(start, 0, cursor, 0, nCells);
            for (int i = 0; i < n; i++) {
                int k = keys[i];
                items[cursor[k]++] = vals[i];
            }
        }
    }

    // ------------------------------------------------------------------- loading

    public static MapData load(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[8];
        bb.get(magic);
        if (magic[0] != 'P' || magic[1] != 'T' || magic[2] != 'A' || magic[3] != 'M') {
            throw new IllegalArgumentException("not a PTA map file");
        }
        int version = bb.getInt();
        if (version != 2) {
            throw new IllegalArgumentException("unsupported PTA map version " + version);
        }
        Builder b = new Builder();
        bb.getFloat(); // lat0
        bb.getFloat(); // lon0
        b.minX = bb.getFloat();
        b.minZ = bb.getFloat();
        b.maxX = bb.getFloat();
        b.maxZ = bb.getFloat();
        b.chunkSize = bb.getFloat();
        int nBuild = bb.getInt();
        int nRoad = bb.getInt();
        int nArea = bb.getInt();
        int nPoi = bb.getInt();
        int nNames = bb.getInt();
        bb.getFloat();
        bb.getFloat();
        b.demCols = bb.getInt();
        b.demRows = bb.getInt();
        b.demMinX = bb.getFloat();
        b.demMinZ = bb.getFloat();
        b.demStep = bb.getFloat();
        b.dem = new short[b.demCols * b.demRows];
        for (int i = 0; i < b.dem.length; i++) {
            b.dem[i] = bb.getShort();
        }

        for (int i = 0; i < nNames; i++) {
            int len = bb.getShort() & 0xFFFF;
            byte[] nb = new byte[len];
            bb.get(nb);
            b.names.add(new String(nb, StandardCharsets.UTF_8));
        }
        for (int i = 0; i < nBuild; i++) {
            bb.get(); // hasName flag
            int nv = bb.get() & 0xFF;
            float h = bb.getFloat();
            int fac = bb.get() & 0xFF;
            int roof = bb.get() & 0xFF;
            bb.getShort();
            int nameIdx = bb.getInt();
            float[] pts = new float[nv * 2];
            for (int v = 0; v < nv; v++) {
                pts[v * 2] = bb.getFloat();
                pts[v * 2 + 1] = bb.getFloat();
            }
            b.buildings.add(new Building(pts, h, fac, roof, nameIdx));
        }
        for (int i = 0; i < nRoad; i++) {
            int id = bb.getInt();
            int cls = bb.get() & 0xFF;
            int flags = bb.get() & 0xFF;
            int lanes = bb.get() & 0xFF;
            boolean drivable = bb.get() != 0;
            float width = bb.getFloat();
            int nameIdx = bb.getInt();
            int np = bb.getShort() & 0xFFFF;
            float[] pts = new float[np * 2];
            for (int p = 0; p < np; p++) {
                pts[p * 2] = bb.getFloat();
                pts[p * 2 + 1] = bb.getFloat();
            }
            b.roads.add(new RoadWay(id, cls, flags, lanes, drivable, width, nameIdx, pts));
        }
        for (int i = 0; i < nArea; i++) {
            int kind = bb.get() & 0xFF;
            int nameIdx = bb.getInt();
            int np = bb.getShort() & 0xFFFF;
            float[] pts = new float[np * 2];
            for (int p = 0; p < np; p++) {
                pts[p * 2] = bb.getFloat();
                pts[p * 2 + 1] = bb.getFloat();
            }
            b.areas.add(new Area(kind, nameIdx, pts));
        }
        for (int i = 0; i < nPoi; i++) {
            float x = bb.getFloat();
            float z = bb.getFloat();
            int cat = bb.get() & 0xFF;
            int nameIdx = bb.getInt();
            b.pois.add(new Poi(x, z, cat, nameIdx));
        }
        return new MapData(b);
    }

    /** Bilinear terrain height at a world position (metres above sea level). */
    public float heightAt(float x, float z) {
        if (dem == null || demCols < 2 || demRows < 2 || demStep <= 0) {
            return 0f;
        }
        float fx = (x - demMinX) / demStep;
        float fz = (z - demMinZ) / demStep;
        if (fx < 0) fx = 0;
        if (fz < 0) fz = 0;
        if (fx > demCols - 1.001f) fx = demCols - 1.001f;
        if (fz > demRows - 1.001f) fz = demRows - 1.001f;
        int c0 = (int) fx, r0 = (int) fz;
        float tx = fx - c0, tz = fz - r0;
        float h00 = dem[r0 * demCols + c0];
        float h10 = dem[r0 * demCols + c0 + 1];
        float h01 = dem[(r0 + 1) * demCols + c0];
        float h11 = dem[(r0 + 1) * demCols + c0 + 1];
        float a = h00 + (h10 - h00) * tx;
        float b = h01 + (h11 - h01) * tx;
        return a + (b - a) * tz;
    }

    private static final class Builder {
        float minX, minZ, maxX, maxZ, chunkSize;
        int demCols, demRows;
        float demMinX, demMinZ, demStep;
        short[] dem;
        final List<Building> buildings = new ArrayList<>();
        final List<RoadWay> roads = new ArrayList<>();
        final List<Area> areas = new ArrayList<>();
        final List<Poi> pois = new ArrayList<>();
        final List<String> names = new ArrayList<>();
    }

    public Poi[] nearbyPois(float x, float z, float radius, int... cats) {
        List<Poi> out = new ArrayList<>();
        int c0 = cx(x - radius), c1 = cx(x + radius), r0 = cz(z - radius), r1 = cz(z + radius);
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                for (int idx : poisIn(c, r)) {
                    Poi p = pois[idx];
                    if (Geo.dist2(x, z, p.x, p.z) > radius * radius) {
                        continue;
                    }
                    boolean ok = cats.length == 0;
                    for (int cat : cats) {
                        if (p.cat == cat) {
                            ok = true;
                            break;
                        }
                    }
                    if (ok) {
                        out.add(p);
                    }
                }
            }
        }
        return out.toArray(new Poi[0]);
    }
}

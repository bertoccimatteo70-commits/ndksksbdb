package it.pta.world;

import com.badlogic.gdx.math.MathUtils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spatial-indexed world geometry baked from OpenStreetMap data.
 *
 * <p>The complete map stays in the application's internal storage. This class keeps
 * only a small spatial index in RAM (record offsets and chunk buckets); building,
 * road and area geometry is decoded on demand and kept in bounded caches. That is
 * what makes all 71.844 buildings, 26.296 road ways, 4.785 areas and 2.321 POIs
 * available without holding the whole province in the Java heap.</p>
 *
 * <p>File format is produced by tools/map/build_map.py. A chunk is 512 m; roads and
 * areas that cross a chunk boundary are listed in every intersecting chunk, but the
 * record itself exists once in the file. Rendering clips them to the chunk.</p>
 */
public final class MapData {
    public static final int MAGIC = 0x5041544D; // "PTAM"

    private static final int MAX_BUILDING_CHUNKS = 16;
    private static final int MAX_CACHED_ROADS = 1024;
    private static final int MAX_CACHED_AREAS = 512;

    public final float minX, minZ, maxX, maxZ;
    public final float chunkSize;
    public final int buildingCount, roadCount, areaCount, poiCount, nameCount;
    public final Poi[] pois;
    public final String[] names;

    /** Real elevation grid (metres) baked from Mapzen/AWS terrain tiles. */
    public final int demCols, demRows;
    public final float demMinX, demMinZ, demStep;
    private final short[] dem;

    public final int minCX, minCZ, nCX, nCZ;

    private final ByteBuffer data;
    private final int[] buildingOffset;
    private final int[] roadOffset;
    private final int[] areaOffset;
    private final int[] buildingOwner; // chunk key, -1 if outside the index
    private final int[] buildingStart;
    private final int[] buildingItems;
    private final int[] roadStart;
    private final int[] roadItems;
    private final int[] areaStart;
    private final int[] areaItems;
    private final int[] poiStart;
    private final int[] poiItems;

    private final LinkedHashMap<Long, BuildingChunk> buildingChunks =
            new LinkedHashMap<Long, BuildingChunk>(24, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, BuildingChunk> eldest) {
                    return size() > MAX_BUILDING_CHUNKS;
                }
            };
    private final LinkedHashMap<Integer, RoadWay> roadCache =
            new LinkedHashMap<Integer, RoadWay>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, RoadWay> eldest) {
                    return size() > MAX_CACHED_ROADS;
                }
            };
    private final LinkedHashMap<Integer, Area> areaCache =
            new LinkedHashMap<Integer, Area>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, Area> eldest) {
                    return size() > MAX_CACHED_AREAS;
                }
            };

    private static final class BuildingChunk {
        final long key;
        final Map<Integer, Building> objects;

        BuildingChunk(long key, Map<Integer, Building> objects) {
            this.key = key;
            this.objects = objects;
        }
    }

    private MapData(ByteBuffer bytes) {
        this.data = bytes.order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer b = data;

        byte[] magic = new byte[8];
        b.get(magic);
        if (magic[0] != 'P' || magic[1] != 'T' || magic[2] != 'A' || magic[3] != 'M') {
            throw new IllegalArgumentException("not a PTA map file");
        }
        int version = b.getInt();
        if (version != 2) {
            throw new IllegalArgumentException("unsupported PTA map version " + version);
        }
        b.getFloat(); // lat0
        b.getFloat(); // lon0
        this.minX = b.getFloat();
        this.minZ = b.getFloat();
        this.maxX = b.getFloat();
        this.maxZ = b.getFloat();
        this.chunkSize = b.getFloat();
        this.buildingCount = b.getInt();
        this.roadCount = b.getInt();
        this.areaCount = b.getInt();
        this.poiCount = b.getInt();
        this.nameCount = b.getInt();
        b.getFloat(); // metres per degree latitude
        b.getFloat(); // metres per degree longitude
        this.demCols = b.getInt();
        this.demRows = b.getInt();
        this.demMinX = b.getFloat();
        this.demMinZ = b.getFloat();
        this.demStep = b.getFloat();
        this.dem = new short[demCols * demRows];
        for (int i = 0; i < dem.length; i++) {
            dem[i] = b.getShort();
        }

        this.names = new String[nameCount];
        for (int i = 0; i < nameCount; i++) {
            int len = b.getShort() & 0xFFFF;
            byte[] nb = new byte[len];
            b.get(nb);
            names[i] = new String(nb, StandardCharsets.UTF_8);
        }

        this.minCX = (int) Math.floor(minX / chunkSize);
        this.minCZ = (int) Math.floor(minZ / chunkSize);
        this.nCX = (int) Math.floor(maxX / chunkSize) - minCX + 1;
        this.nCZ = (int) Math.floor(maxZ / chunkSize) - minCZ + 1;

        buildingOffset = new int[buildingCount];
        roadOffset = new int[roadCount];
        areaOffset = new int[areaCount];
        buildingOwner = new int[buildingCount];
        Arrays.fill(buildingOwner, -1);

        Buckets bb = new Buckets(nCX * nCZ, minCX, minCZ, nCX);
        for (int i = 0; i < buildingCount; i++) {
            int start = b.position();
            buildingOffset[i] = start;
            b.get(); // has name flag
            int nv = b.get() & 0xFF;
            b.getFloat(); // height
            b.get(); // facade
            b.get(); // roof
            b.getShort(); // architectural family and roof silhouette
            b.getInt(); // name index
            float sx = 0, sz = 0;
            float mnx = Float.MAX_VALUE, mnz = Float.MAX_VALUE;
            float mxx = -Float.MAX_VALUE, mxz = -Float.MAX_VALUE;
            for (int v = 0; v < nv; v++) {
                float x = b.getFloat();
                float z = b.getFloat();
                sx += x;
                sz += z;
                mnx = Math.min(mnx, x);
                mxx = Math.max(mxx, x);
                mnz = Math.min(mnz, z);
                mxz = Math.max(mxz, z);
            }
            if (nv > 0) {
                int k = key(cx(sx / nv), cz(sz / nv));
                buildingOwner[i] = k;
                bb.add(cx(sx / nv), cz(sz / nv), i);
            }
        }
        bb.build();
        buildingStart = bb.start;
        buildingItems = bb.items;

        Buckets rb = new Buckets(nCX * nCZ, minCX, minCZ, nCX);
        for (int i = 0; i < roadCount; i++) {
            int start = b.position();
            roadOffset[i] = start;
            b.getInt(); // OSM id
            b.get(); // class
            b.get(); // flags
            b.get(); // lanes
            b.get(); // drivable
            b.getFloat(); // width
            b.getInt(); // name index
            int np = b.getShort() & 0xFFFF;
            float mnx = Float.MAX_VALUE, mnz = Float.MAX_VALUE;
            float mxx = -Float.MAX_VALUE, mxz = -Float.MAX_VALUE;
            for (int p = 0; p < np; p++) {
                float x = b.getFloat();
                float z = b.getFloat();
                mnx = Math.min(mnx, x);
                mxx = Math.max(mxx, x);
                mnz = Math.min(mnz, z);
                mxz = Math.max(mxz, z);
            }
            if (np > 0) {
                markBox(rb, i, mnx, mnz, mxx, mxz);
            }
        }
        rb.build();
        roadStart = rb.start;
        roadItems = rb.items;

        Buckets ab = new Buckets(nCX * nCZ, minCX, minCZ, nCX);
        for (int i = 0; i < areaCount; i++) {
            int start = b.position();
            areaOffset[i] = start;
            b.get(); // kind
            b.getInt(); // name index
            int np = b.getShort() & 0xFFFF;
            float mnx = Float.MAX_VALUE, mnz = Float.MAX_VALUE;
            float mxx = -Float.MAX_VALUE, mxz = -Float.MAX_VALUE;
            for (int p = 0; p < np; p++) {
                float x = b.getFloat();
                float z = b.getFloat();
                mnx = Math.min(mnx, x);
                mxx = Math.max(mxx, x);
                mnz = Math.min(mnz, z);
                mxz = Math.max(mxz, z);
            }
            if (np > 0) {
                markBox(ab, i, mnx, mnz, mxx, mxz);
            }
        }
        ab.build();
        areaStart = ab.start;
        areaItems = ab.items;

        Buckets pb = new Buckets(nCX * nCZ, minCX, minCZ, nCX);
        this.pois = new Poi[poiCount];
        for (int i = 0; i < poiCount; i++) {
            float x = b.getFloat();
            float z = b.getFloat();
            int cat = b.get() & 0xFF;
            int nameIdx = b.getInt();
            Poi p = new Poi(x, z, cat, nameIdx);
            pois[i] = p;
            pb.add(cx(x), cz(z), i);
        }
        pb.build();
        poiStart = pb.start;
        poiItems = pb.items;

        verifyCounts();
    }

    private int key(int cx, int cz) {
        int col = cx - minCX;
        int row = cz - minCZ;
        if (col < 0 || row < 0 || col >= nCX || row >= nCZ) {
            return -1;
        }
        return row * nCX + col;
    }

    private void markBox(Buckets buckets, int idx, float x0, float z0, float x1, float z1) {
        int c0 = cx(x0), c1 = cx(x1), r0 = cz(z0), r1 = cz(z1);
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                buckets.add(c, r, idx);
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

    // ------------------------------------------------------------ on-demand data

    /**
     * Returns a building by global record index. Buildings are grouped by their centre
     * chunk, so a request only decodes the buildings of that 512 m chunk. At most
     * {@link #MAX_BUILDING_CHUNKS} chunks are retained in RAM.
     */
    public Building building(int idx) {
        if (idx < 0 || idx >= buildingCount) {
            throw new IndexOutOfBoundsException("building " + idx);
        }
        synchronized (buildingChunks) {
            int owner = buildingOwner[idx];
            if (owner < 0) {
                return readBuilding(idx);
            }
            BuildingChunk chunk = buildingChunks.get((long) owner);
            if (chunk == null) {
                chunk = loadBuildingChunk(owner);
                buildingChunks.put((long) owner, chunk);
            }
            Building b = chunk.objects.get(idx);
            return b != null ? b : readBuilding(idx);
        }
    }

    private BuildingChunk loadBuildingChunk(long owner) {
        int cell = (int) owner;
        int row = nCX > 0 ? cell / nCX : 0;
        int col = nCX > 0 ? cell % nCX : 0;
        int cx = minCX + col;
        int cz = minCZ + row;
        int[] ids = buildingsIn(cx, cz);
        Map<Integer, Building> map = new HashMap<>(Math.max(8, ids.length * 2));
        for (int id : ids) {
            map.put(id, readBuilding(id));
        }
        return new BuildingChunk(owner, map);
    }

    public RoadWay road(int idx) {
        if (idx < 0 || idx >= roadCount) {
            throw new IndexOutOfBoundsException("road " + idx);
        }
        synchronized (roadCache) {
            RoadWay r = roadCache.get(idx);
            if (r == null) {
                r = readRoad(idx);
                roadCache.put(idx, r);
            }
            return r;
        }
    }

    public Area area(int idx) {
        if (idx < 0 || idx >= areaCount) {
            throw new IndexOutOfBoundsException("area " + idx);
        }
        synchronized (areaCache) {
            Area a = areaCache.get(idx);
            if (a == null) {
                a = readArea(idx);
                areaCache.put(idx, a);
            }
            return a;
        }
    }

    public Poi poi(int idx) {
        return pois[idx];
    }

    private ByteBuffer at(int offset) {
        ByteBuffer b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        b.position(offset);
        return b;
    }

    private Building readBuilding(int idx) {
        ByteBuffer b = at(buildingOffset[idx]);
        b.get();
        int nv = b.get() & 0xFF;
        float h = b.getFloat();
        int fac = b.get() & 0xFF;
        int roof = b.get() & 0xFF;
        int style = b.getShort() & 0xFFFF;
        int nameIdx = b.getInt();
        float[] pts = new float[nv * 2];
        for (int v = 0; v < nv; v++) {
            pts[v * 2] = b.getFloat();
            pts[v * 2 + 1] = b.getFloat();
        }
        return new Building(pts, h, fac, roof, nameIdx, style & 0xFF, (style >> 8) & 0x0F);
    }

    private RoadWay readRoad(int idx) {
        ByteBuffer b = at(roadOffset[idx]);
        int id = b.getInt();
        int cls = b.get() & 0xFF;
        int flags = b.get() & 0xFF;
        int lanes = b.get() & 0xFF;
        boolean drivable = b.get() != 0;
        float width = b.getFloat();
        int nameIdx = b.getInt();
        int np = b.getShort() & 0xFFFF;
        float[] pts = new float[np * 2];
        for (int p = 0; p < np; p++) {
            pts[p * 2] = b.getFloat();
            pts[p * 2 + 1] = b.getFloat();
        }
        return new RoadWay(id, cls, flags, lanes, drivable, width, nameIdx, pts);
    }

    private Area readArea(int idx) {
        ByteBuffer b = at(areaOffset[idx]);
        int kind = b.get() & 0xFF;
        int nameIdx = b.getInt();
        int np = b.getShort() & 0xFFFF;
        float[] pts = new float[np * 2];
        for (int p = 0; p < np; p++) {
            pts[p * 2] = b.getFloat();
            pts[p * 2 + 1] = b.getFloat();
        }
        return new Area(kind, nameIdx, pts);
    }

    /** Releases the geometry of a chunk that left the streaming radius. */
    public void releaseChunk(int cx, int cz) {
        synchronized (buildingChunks) {
            buildingChunks.remove((long) key(cx, cz));
        }
    }

    public void releaseAllChunks() {
        synchronized (buildingChunks) {
            buildingChunks.clear();
        }
        synchronized (roadCache) {
            roadCache.clear();
        }
        synchronized (areaCache) {
            areaCache.clear();
        }
    }

    /** Diagnostic: number of building chunks currently decoded in RAM. */
    public int cachedBuildingChunks() {
        synchronized (buildingChunks) {
            return buildingChunks.size();
        }
    }

    public int cachedRoads() {
        synchronized (roadCache) {
            return roadCache.size();
        }
    }

    // --------------------------------------------------------------- map queries

    /** Closest point on road network to (x,z), searching a local 3x3 chunk window. */
    public RoadHit nearestRoad(float x, float z, boolean drivableOnly, float maxDist) {
        int c = cx(x), r = cz(z);
        RoadHit best = new RoadHit();
        best.dist = maxDist;
        float bestScore = maxDist;
        for (int rr = r - 1; rr <= r + 1; rr++) {
            for (int cc = c - 1; cc <= c + 1; cc++) {
                for (int idx : roadsIn(cc, rr)) {
                    RoadWay w = road(idx);
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
                    Building b = building(idx);
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

    /**
     * Resolves a circle against every nearby OSM footprint. The closest point is
     * taken on the polygon edges (not on a vertex), and a few passes are made so
     * corners and adjacent buildings cannot push the circle into each other.
     */
    public boolean resolveCollision(float[] pos, float radius) {
        boolean hit = false;
        float[] edge = new float[4];
        for (int pass = 0; pass < 4; pass++) {
            boolean moved = false;
            int c = cx(pos[0]), r = cz(pos[2]);
            for (int rr = r - 1; rr <= r + 1; rr++) {
                for (int cc = c - 1; cc <= c + 1; cc++) {
                    for (int idx : buildingsIn(cc, rr)) {
                        Building b = building(idx);
                        if (!b.collides) {
                            continue;
                        }
                        if (pos[0] < b.minX - radius || pos[0] > b.maxX + radius
                                || pos[2] < b.minZ - radius || pos[2] > b.maxZ + radius) {
                            continue;
                        }
                        float d = b.nearestBoundary(pos[0], pos[2], edge);
                        boolean inside = b.contains(pos[0], pos[2]);
                        if (!inside && d >= radius) {
                            continue;
                        }
                        // For an outside circle, the vector from edge to centre is
                        // already outward. For an inside point use the polygon edge
                        // normal, which also works for concave footprints.
                        float nx = edge[2], nz = edge[3];
                        if (!inside && d > 1e-4f) {
                            float ox = pos[0] - edge[0];
                            float oz = pos[2] - edge[1];
                            float ol = (float) Math.sqrt(ox * ox + oz * oz);
                            if (ol > 1e-4f) {
                                nx = ox / ol;
                                nz = oz / ol;
                            }
                        }
                        float push = radius + 0.025f;
                        pos[0] = edge[0] + nx * push;
                        pos[2] = edge[1] + nz * push;
                        moved = true;
                        hit = true;
                    }
                }
            }
            if (!moved) {
                break;
            }
        }
        return hit;
    }

    /** First solid footprint containing the point; used to locate a real doorway. */
    public Building buildingAt(float x, float z) {
        int c = cx(x), r = cz(z);
        for (int rr = r - 1; rr <= r + 1; rr++) {
            for (int cc = c - 1; cc <= c + 1; cc++) {
                for (int idx : buildingsIn(cc, rr)) {
                    Building b = building(idx);
                    if (b.collides && b.contains(x, z)) {
                        return b;
                    }
                }
            }
        }
        return null;
    }

    public Area areaAt(float x, float z) {
        int c = cx(x), r = cz(z);
        Area best = null;
        int bestRank = -1;
        for (int idx : areasIn(c, r)) {
            Area a = area(idx);
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

    /**
     * Iterates road segments in a rectangle without ever building a global road list.
     * Used by the minimap and the province map, which only need a few hundred segments.
     */
    public int forEachRoadSegment(float minX, float minZ, float maxX, float maxZ,
                                  RoadSegmentConsumer consumer, int maxSegments) {
        int c0 = cx(minX), c1 = cx(maxX), r0 = cz(minZ), r1 = cz(maxZ);
        Set<Integer> seen = new HashSet<>();
        int count = 0;
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                for (int idx : roadsIn(c, r)) {
                    if (!seen.add(idx)) {
                        continue;
                    }
                    RoadWay road = road(idx);
                    if (road.maxX < minX || road.minX > maxX
                            || road.maxZ < minZ || road.minZ > maxZ) {
                        continue;
                    }
                    float[] pts = road.pts;
                    for (int i = 0; i + 3 < pts.length; i += 2) {
                        float x0 = pts[i], z0 = pts[i + 1];
                        float x1 = pts[i + 2], z1 = pts[i + 3];
                        if (Math.max(x0, x1) < minX || Math.min(x0, x1) > maxX
                                || Math.max(z0, z1) < minZ || Math.min(z0, z1) > maxZ) {
                            continue;
                        }
                        if (!consumer.segment(x0, z0, x1, z1, road.drivable)) {
                            return count;
                        }
                        count++;
                        if (count >= maxSegments) {
                            return count;
                        }
                    }
                }
            }
        }
        return count;
    }

    public interface RoadSegmentConsumer {
        /** Return false to stop iterating. */
        boolean segment(float x0, float z0, float x1, float z1, boolean drivable);
    }

    // ---------------------------------------------------------------- data model

    public static final class Building {
        public static final int HISTORIC = 0;
        public static final int PALAZZO = 1;
        public static final int VILLA = 2;
        public static final int MODERN = 3;
        public static final int SHOP = 4;
        public static final int COMMERCIAL = 5;
        public static final int INDUSTRIAL = 6;
        public static final int PUBLIC = 7;
        public static final int RURAL = 8;

        public static final int ROOF_FLAT = 0;
        public static final int ROOF_GABLE = 1;
        public static final int ROOF_HIPPED = 2;
        public static final int ROOF_SHED = 3;
        public static final int ROOF_PYRAMID = 4;

        public final float[] pts;
        public final float height;
        public final int facade, roof;
        public final int type, roofType;
        public final int nameIdx;
        public final float cx, cz;
        public final float minX, minZ, maxX, maxZ;
        public final float radius;
        public final float area;
        /** Signed winding: positive means CCW in map X/Z coordinates. */
        public final float signedArea;
        public final boolean collides;

        Building(float[] pts, float height, int facade, int roof, int nameIdx,
                 int type, int roofType) {
            this.pts = pts;
            this.height = height;
            this.facade = facade;
            this.roof = roof;
            this.type = type;
            this.roofType = roofType;
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
            signedArea = ringArea(pts);
            area = Math.abs(signedArea);
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
            return nearestBoundary(x, z, null);
        }

        public boolean contains(float x, float z) {
            return pointInRing(pts, x, z);
        }

        /**
         * Distance from (x,z) to the closest polygon edge, not the closest vertex.
         * When {@code out} is supplied it receives edge point x,z and the outward
         * edge normal x,z. This is what makes long walls solid between corners.
         */
        public float nearestBoundary(float x, float z, float[] out) {
            int n = vertexCount();
            float best = Float.MAX_VALUE;
            float bx = x, bz = z, bnx = 1f, bnz = 0f;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                float ax = polyX(j), az = polyZ(j);
                float ex = polyX(i) - ax, ez = polyZ(i) - az;
                float el2 = ex * ex + ez * ez;
                float t = el2 > 1e-12f
                        ? ((x - ax) * ex + (z - az) * ez) / el2 : 0f;
                t = MathUtils.clamp(t, 0f, 1f);
                float qx = ax + ex * t, qz = az + ez * t;
                float dx = x - qx, dz = z - qz;
                float d2 = dx * dx + dz * dz;
                if (d2 < best) {
                    best = d2;
                    bx = qx;
                    bz = qz;
                    float el = (float) Math.sqrt(el2);
                    if (el > 1e-6f) {
                        // CCW polygon: outward is right of the directed edge.
                        bnx = signedArea >= 0f ? ez / el : -ez / el;
                        bnz = signedArea >= 0f ? -ex / el : ex / el;
                    }
                }
            }
            if (out != null) {
                out[0] = bx;
                out[1] = bz;
                out[2] = bnx;
                out[3] = bnz;
            }
            return (float) Math.sqrt(best);
        }
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
            this.nCells = Math.max(0, size);
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
                keys = Arrays.copyOf(keys, cap);
                vals = Arrays.copyOf(vals, cap);
            }
            keys[n] = k;
            vals[n] = idx;
            n++;
        }

        void build() {
            int[] count = new int[Math.max(1, nCells)];
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
            int[] cursor = new int[Math.max(1, nCells)];
            System.arraycopy(start, 0, cursor, 0, nCells);
            for (int i = 0; i < n; i++) {
                int k = keys[i];
                items[cursor[k]++] = vals[i];
            }
        }
    }

    // ------------------------------------------------------------------- loading

    /**
     * Opens the full map from storage. The file is mapped read-only; the operating
     * system keeps the complete data on disk while only touched pages are resident.
     */
    public static MapData open(File file) {
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            long size = channel.size();
            if (size <= 0 || size > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("invalid map size " + size);
            }
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            return new MapData(mapped);
        } catch (IOException e) {
            throw new IllegalStateException("cannot open map " + file, e);
        }
    }

    /** Test/JVM helper. Production uses {@link #open(File)} so the heap is not filled. */
    public static MapData load(byte[] bytes) {
        return new MapData(ByteBuffer.wrap(bytes));
    }

    public void verifyCounts() {
        if (buildingOffset.length != buildingCount) {
            throw new IllegalStateException("building count mismatch: " + buildingOffset.length + " / " + buildingCount);
        }
        if (roadOffset.length != roadCount) {
            throw new IllegalStateException("road count mismatch: " + roadOffset.length + " / " + roadCount);
        }
        if (areaOffset.length != areaCount) {
            throw new IllegalStateException("area count mismatch: " + areaOffset.length + " / " + areaCount);
        }
        if (pois.length != poiCount) {
            throw new IllegalStateException("poi count mismatch: " + pois.length + " / " + poiCount);
        }
        if (names.length != nameCount) {
            throw new IllegalStateException("name count mismatch: " + names.length + " / " + nameCount);
        }
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

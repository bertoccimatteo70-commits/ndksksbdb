package it.pta.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * A* router over the baked drivable ways.
 *
 * <p>The graph is a proper junction graph: every OSM way vertex becomes a node and
 * consecutive vertices are linked with the segment length. Links are directed, so a
 * one-way street only accepts travel in its legal direction ({@code oneway=yes} along
 * the way geometry, {@code oneway=-1} against it). Start and destination are snapped
 * by projecting onto the closest road segment, and a temporary node on that segment
 * is connected only to the endpoints a driver may legally reach.</p>
 *
 * <p>The heuristic is the straight-line distance to the destination: no segment can
 * be shorter than the line between its ends, so the heuristic never overestimates and
 * A* stays optimal.</p>
 *
 * <p>The graph is not the whole province: it only covers a corridor around the
 * requested route. Building the complete 149.225-node / 280.992-edge graph filled
 * about 190 MB of Java heap on a 192 MB process and killed the game with
 * OutOfMemoryError while the city was streaming at the same time. A corridor keeps
 * the graph small, is fast to build and is rebuilt only when a route leaves it.</p>
 *
 * <p>Plain Java on purpose: it runs on the JVM tests without OpenGL, and in the game
 * from a background thread.</p>
 */
public final class RoadRouter {
    /** Distance under which two ways are considered to share a junction node. */
    private static final double NODE_QUANTUM = 0.5;
    /** Upper bound on expanded nodes, so a broken graph cannot hang a route request. */
    private static final int MAX_EXPANSIONS = 120000;
    /** Corridor half-width of the first graph build, metres. */
    private static final float FIRST_BAND = 700f;
    /** Wider retry when the narrow corridor has no path (one-way systems, rivers). */
    private static final float SECOND_BAND = 2200f;

    public static final class Route {
        public final boolean found;
        public final float[] points;
        public final float length;
        public final String message;
        /** Distance from the start to the next real manoeuvre, metres. */
        public final float nextTurnDistance;
        public final float nextTurnX, nextTurnZ;
        /** -1 left, 0 straight on, +1 right (driver's point of view). */
        public final int nextTurnSide;
        public final String nextTurnStreet;

        Route(boolean found, float[] points, float length, String message,
              float turnDistance, float turnX, float turnZ, int side, String street) {
            this.found = found;
            this.points = points;
            this.length = length;
            this.message = message;
            this.nextTurnDistance = turnDistance;
            this.nextTurnX = turnX;
            this.nextTurnZ = turnZ;
            this.nextTurnSide = side;
            this.nextTurnStreet = street;
        }

        static Route none(String message) {
            return new Route(false, new float[0], 0f, message, 0f, 0f, 0f, 0, null);
        }
    }

    private final MapData map;

    // Directed adjacency list in flat arrays: cheaper than one list object per node.
    private float[] nodeX = new float[4096];
    private float[] nodeZ = new float[4096];
    private int nodeCount;
    private int[] head = new int[4096];
    private int[] edgeNext = new int[8192];
    private int[] edgeTo = new int[8192];
    private float[] edgeCost = new float[8192];
    private int edgeCount;

    /**
     * Junction index: packed long keys and int ids in open addressing. The previous
     * {@code HashMap<Long, Integer>} allocated three objects per junction (149k
     * junctions on the province-wide graph) and was a large part of the heap cost.
     */
    private static final class NodeIndex {
        private long[] keys = new long[8192];
        private int[] vals = new int[8192]; // 0 = empty, otherwise node id + 1
        private int size;

        void clear() {
            if (size > 0) {
                Arrays.fill(vals, 0);
            }
            size = 0;
        }

        int get(long key) {
            int mask = vals.length - 1;
            int i = hash(key) & mask;
            while (vals[i] != 0) {
                if (keys[i] == key) {
                    return vals[i] - 1;
                }
                i = (i + 1) & mask;
            }
            return -1;
        }

        void put(long key, int id) {
            if ((size + 1) * 10 >= vals.length * 7) {
                grow();
            }
            int mask = vals.length - 1;
            int i = hash(key) & mask;
            while (vals[i] != 0) {
                if (keys[i] == key) {
                    vals[i] = id + 1;
                    return;
                }
                i = (i + 1) & mask;
            }
            keys[i] = key;
            vals[i] = id + 1;
            size++;
        }

        private static int hash(long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32));
        }

        private void grow() {
            long[] oldKeys = keys;
            int[] oldVals = vals;
            keys = new long[oldKeys.length * 2];
            vals = new int[oldKeys.length * 2];
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldVals[i] != 0) {
                    put(oldKeys[i], oldVals[i] - 1);
                }
            }
        }
    }

    private final NodeIndex nodeIds = new NodeIndex();
    private volatile boolean built;
    private float builtMinX, builtMinZ, builtMaxX, builtMaxZ, builtBand = -1f;

    public RoadRouter(MapData map) {
        this.map = map;
        Arrays.fill(head, -1);
    }

    public int nodeCount() {
        return nodeCount;
    }

    public int edgeCount() {
        return edgeCount;
    }

    public Route route(float startX, float startZ, float targetX, float targetZ) {
        return route(startX, startZ, targetX, targetZ, 0f, 0f);
    }

    /**
     * @param headingX optional travel direction of the player (unit vector); when
     *                 supplied, a one-way segment that contradicts it is not used as
     *                 the start of the route unless nothing else is available.
     */
    public Route route(float startX, float startZ, float targetX, float targetZ,
                       float headingX, float headingZ) {
        ensureGraph(startX, startZ, targetX, targetZ, FIRST_BAND);
        Route route = routeOnGraph(startX, startZ, targetX, targetZ, headingX, headingZ);
        if (!route.found) {
            // The narrow corridor can miss the only legal path around a one-way
            // system; retry once with a wider corridor before giving up.
            ensureGraph(startX, startZ, targetX, targetZ, SECOND_BAND);
            Route wider = routeOnGraph(startX, startZ, targetX, targetZ, headingX, headingZ);
            if (wider.found) {
                return wider;
            }
        }
        return route;
    }

    private Route routeOnGraph(float startX, float startZ, float targetX, float targetZ,
                               float headingX, float headingZ) {
        if (nodeCount == 0 || edgeCount == 0) {
            return Route.none("Rete stradale non disponibile");
        }
        MapData.RoadHit startHit = map.nearestRoad(startX, startZ, true, 180f);
        MapData.RoadHit targetHit = map.nearestRoad(targetX, targetZ, true, 260f);
        if (startHit == null || targetHit == null) {
            return Route.none(startHit == null ? "Nessuna strada vicina" : "Destinazione fuori rete");
        }
        int start = addVirtualNode(startHit.x, startHit.z);
        int target = addVirtualNode(targetHit.x, targetHit.z);
        linkStart(start, startHit, headingX, headingZ);
        linkTarget(target, targetHit);
        if (startHit.way == targetHit.way
                && startHit.segment == targetHit.segment
                && directOnSegment(startHit, targetHit)) {
            // Same carriageway: no junction needed, just drive the difference.
            ArrayList<float[]> direct = new ArrayList<>();
            direct.add(new float[]{startHit.x, startHit.z});
            direct.add(new float[]{targetHit.x, targetHit.z});
            return finish(direct, "Percorso stradale");
        }
        int[] prev = search(start, target);
        if (prev == null) {
            return Route.none("Nessun percorso stradale");
        }
        ArrayList<float[]> path = new ArrayList<>();
        for (int at = target; at >= 0; at = prev[at]) {
            path.add(new float[]{nodeX[at], nodeZ[at]});
            if (path.size() > MAX_EXPANSIONS) {
                return Route.none("Percorso troppo lungo");
            }
        }
        java.util.Collections.reverse(path);
        return finish(path, "Percorso stradale");
    }

    /**
     * True when both projections sit on the same segment and the destination lies
     * ahead in the allowed travel direction.
     */
    private boolean directOnSegment(MapData.RoadHit a, MapData.RoadHit b) {
        MapData.RoadWay w = a.way;
        boolean forward = !w.oneWay() || !w.oneWayReversed();
        boolean backward = !w.oneWay() || w.oneWayReversed();
        if (forward && b.t >= a.t) {
            return true;
        }
        return backward && b.t <= a.t;
    }

    /** Connects the snapped start to the segment endpoints a driver may leave from. */
    private void linkStart(int start, MapData.RoadHit hit, float headingX, float headingZ) {
        MapData.RoadWay w = hit.way;
        int seg = hit.segment;
        if (seg < 0 || seg * 2 + 3 >= w.pts.length) {
            return;
        }
        int a = nodeFor(w.pts[seg * 2], w.pts[seg * 2 + 1], true);
        int b = nodeFor(w.pts[seg * 2 + 2], w.pts[seg * 2 + 3], true);
        boolean forward = !w.oneWay() || !w.oneWayReversed();
        boolean backward = !w.oneWay() || w.oneWayReversed();
        // When the driver is heading against the only legal direction of a two-way
        // link, still allow the geometry: the route can turn around at the junction.
        if (forward) {
            addEdge(start, b, distance(hit.x, hit.z, w.pts[seg * 2 + 2], w.pts[seg * 2 + 3]));
        }
        if (backward) {
            addEdge(start, a, distance(hit.x, hit.z, w.pts[seg * 2], w.pts[seg * 2 + 1]));
        }
    }

    /** Connects the segment endpoints that may legally lead into the destination. */
    private void linkTarget(int target, MapData.RoadHit hit) {
        MapData.RoadWay w = hit.way;
        int seg = hit.segment;
        if (seg < 0 || seg * 2 + 3 >= w.pts.length) {
            return;
        }
        int a = nodeFor(w.pts[seg * 2], w.pts[seg * 2 + 1], true);
        int b = nodeFor(w.pts[seg * 2 + 2], w.pts[seg * 2 + 3], true);
        boolean forward = !w.oneWay() || !w.oneWayReversed();
        boolean backward = !w.oneWay() || w.oneWayReversed();
        if (forward) {
            addEdge(a, target, distance(hit.x, hit.z, w.pts[seg * 2], w.pts[seg * 2 + 1]));
        }
        if (backward) {
            addEdge(b, target, distance(hit.x, hit.z, w.pts[seg * 2 + 2], w.pts[seg * 2 + 3]));
        }
    }

    private Route finish(ArrayList<float[]> path, String message) {
        // Drop repeated vertices left by way joins and virtual nodes.
        ArrayList<float[]> clean = new ArrayList<>(path.size());
        for (float[] p : path) {
            if (clean.isEmpty()) {
                clean.add(p);
                continue;
            }
            float[] last = clean.get(clean.size() - 1);
            if (distance(last[0], last[1], p[0], p[1]) > 0.05f) {
                clean.add(p);
            }
        }
        if (clean.size() < 2) {
            return Route.none("Percorso troppo corto");
        }
        float length = 0f;
        for (int i = 1; i < clean.size(); i++) {
            length += distance(clean.get(i - 1)[0], clean.get(i - 1)[1],
                    clean.get(i)[0], clean.get(i)[1]);
        }
        float[] out = new float[clean.size() * 2];
        for (int i = 0; i < clean.size(); i++) {
            out[i * 2] = clean.get(i)[0];
            out[i * 2 + 1] = clean.get(i)[1];
        }
        float turnDist = length;
        float turnX = out[out.length - 2];
        float turnZ = out[out.length - 1];
        int side = 0;
        String street = null;
        float along = 0f;
        for (int i = 1; i + 1 < clean.size(); i++) {
            float ax = clean.get(i - 1)[0], az = clean.get(i - 1)[1];
            float bx = clean.get(i)[0], bz = clean.get(i)[1];
            float cx = clean.get(i + 1)[0], cz = clean.get(i + 1)[1];
            along += distance(ax, az, bx, bz);
            float inLen = distance(ax, az, bx, bz);
            float outLen = distance(bx, bz, cx, cz);
            if (inLen < 0.5f || outLen < 0.5f) {
                continue;
            }
            float ix = (bx - ax) / inLen, iz = (bz - az) / inLen;
            float ox = (cx - bx) / outLen, oz = (cz - bz) / outLen;
            float delta = wrapAngle((float) Math.atan2(oz, ox) - (float) Math.atan2(iz, ix));
            if (Math.abs(delta) < 0.52f) {
                continue;
            }
            turnDist = along;
            turnX = bx;
            turnZ = bz;
            // Map coordinates have +z downwards on screen, so a growing angle is a
            // clockwise rotation, i.e. a right turn for the driver.
            side = delta > 0f ? 1 : -1;
            MapData.RoadHit h = map.nearestRoad(bx, bz, true, 18f);
            if (h != null && h.way != null) {
                street = map.name(h.way.nameIdx);
            }
            break;
        }
        return new Route(true, out, length, message, turnDist, turnX, turnZ, side, street);
    }

    private static float wrapAngle(float a) {
        while (a > Math.PI) {
            a -= (float) (Math.PI * 2);
        }
        while (a < -Math.PI) {
            a += (float) (Math.PI * 2);
        }
        return a;
    }

    private int[] search(int start, int target) {
        int n = nodeCount;
        float[] g = new float[n];
        int[] prev = new int[n];
        boolean[] closed = new boolean[n];
        Arrays.fill(g, Float.MAX_VALUE);
        Arrays.fill(prev, -1);
        g[start] = 0f;
        PriorityQueue<float[]> open = new PriorityQueue<>((a, b) -> Float.compare(a[1], b[1]));
        open.add(new float[]{start, heuristic(start, target)});
        int expansions = 0;
        while (!open.isEmpty()) {
            float[] item = open.poll();
            int at = (int) item[0];
            if (closed[at]) {
                continue;
            }
            if (at == target) {
                return prev;
            }
            closed[at] = true;
            if (++expansions > MAX_EXPANSIONS) {
                return null;
            }
            for (int e = head[at]; e >= 0; e = edgeNext[e]) {
                int to = edgeTo[e];
                if (closed[to]) {
                    continue;
                }
                float nd = g[at] + edgeCost[e];
                if (nd < g[to]) {
                    g[to] = nd;
                    prev[to] = at;
                    open.add(new float[]{to, nd + heuristic(to, target)});
                }
            }
        }
        return null;
    }

    private float heuristic(int from, int target) {
        return distance(nodeX[from], nodeZ[from], nodeX[target], nodeZ[target]);
    }

    /**
     * Builds the junction graph for a corridor around the requested route. The graph
     * is reused while both ends stay inside the built box with the same band, and
     * rebuilt otherwise. Only the chunks near the straight line between the two ends
     * are decoded, so a cross-province route costs a small fraction of the map.
     */
    private synchronized void ensureGraph(float sx, float sz, float tx, float tz, float band) {
        if (built && band == builtBand
                && sx >= builtMinX && sx <= builtMaxX && sz >= builtMinZ && sz <= builtMaxZ
                && tx >= builtMinX && tx <= builtMaxX && tz >= builtMinZ && tz <= builtMaxZ) {
            return;
        }
        // Start from a clean graph: stale nodes and edges of a previous corridor must
        // not be reused, they belong to a different part of the province.
        nodeCount = 0;
        edgeCount = 0;
        nodeIds.clear();

        float dx = tx - sx, dz = tz - sz;
        float len2 = Math.max(1e-3f, dx * dx + dz * dz);
        float pad = band + map.chunkSize;
        builtMinX = Math.min(sx, tx) - pad;
        builtMaxX = Math.max(sx, tx) + pad;
        builtMinZ = Math.min(sz, tz) - pad;
        builtMaxZ = Math.max(sz, tz) + pad;
        builtBand = band;
        built = true;

        int c0 = map.cx(builtMinX), c1 = map.cx(builtMaxX);
        int r0 = map.cz(builtMinZ), r1 = map.cz(builtMaxZ);
        boolean[] seenRoad = new boolean[map.roadCount];
        for (int cz = r0; cz <= r1; cz++) {
            for (int cx = c0; cx <= c1; cx++) {
                float ccx = (cx + 0.5f) * map.chunkSize;
                float ccz = (cz + 0.5f) * map.chunkSize;
                float t = ((ccx - sx) * dx + (ccz - sz) * dz) / len2;
                t = Math.max(0f, Math.min(1f, t));
                float px = sx + dx * t, pz = sz + dz * t;
                // Skip chunks outside the corridor (the half diagonal of a chunk is
                // ~362 m, so the pad keeps every chunk the corridor may cross).
                if (Math.hypot(ccx - px, ccz - pz) > band + 400f) {
                    continue;
                }
                for (int idx : map.roadsIn(cx, cz)) {
                    if (idx < 0 || idx >= seenRoad.length || seenRoad[idx]) {
                        continue;
                    }
                    seenRoad[idx] = true;
                    MapData.RoadWay w = map.road(idx);
                    if (!w.drivable || w.cls > 8 || w.pts.length < 4) {
                        continue;
                    }
                    int[] ids = new int[w.pts.length / 2];
                    for (int i = 0; i < ids.length; i++) {
                        ids[i] = nodeFor(w.pts[i * 2], w.pts[i * 2 + 1], true);
                    }
                    boolean oneWay = w.oneWay();
                    boolean reversed = w.oneWayReversed();
                    for (int i = 0; i + 1 < ids.length; i++) {
                        float ax = w.pts[i * 2], az = w.pts[i * 2 + 1];
                        float bx = w.pts[i * 2 + 2], bz = w.pts[i * 2 + 3];
                        float len = distance(ax, az, bx, bz);
                        if (len < 0.25f || crossesBuilding(ax, az, bx, bz)) {
                            continue;
                        }
                        if (!oneWay) {
                            link(ids[i], ids[i + 1], len);
                            link(ids[i + 1], ids[i], len);
                        } else if (reversed) {
                            link(ids[i + 1], ids[i], len);
                        } else {
                            link(ids[i], ids[i + 1], len);
                        }
                    }
                }
            }
        }
    }

    private boolean crossesBuilding(float ax, float az, float bx, float bz) {
        // Three samples catch a car-width segment entering a facade. Roads are
        // allowed to touch a kerb; only solid footprints invalidate the edge.
        for (int i = 1; i <= 3; i++) {
            float t = i / 4f;
            float x = ax + (bx - ax) * t;
            float z = az + (bz - az) * t;
            if (map.blocked(x, z, 0.18f)) {
                return true;
            }
        }
        return false;
    }

    private int nodeFor(float x, float z, boolean create) {
        long key = ((long) Math.round(x / NODE_QUANTUM) << 32)
                ^ (Math.round(z / NODE_QUANTUM) & 0xffffffffL);
        int old = nodeIds.get(key);
        if (old >= 0) {
            return old;
        }
        if (!create) {
            return -1;
        }
        int id = addNode(x, z);
        nodeIds.put(key, id);
        return id;
    }

    /** Temporary node on a road segment; never registered in the junction index. */
    private int addVirtualNode(float x, float z) {
        return addNode(x, z);
    }

    private int addNode(float x, float z) {
        if (nodeCount == nodeX.length) {
            int cap = nodeX.length * 2;
            nodeX = Arrays.copyOf(nodeX, cap);
            nodeZ = Arrays.copyOf(nodeZ, cap);
        }
        if (nodeCount == head.length) {
            int cap = head.length * 2;
            int old = head.length;
            head = Arrays.copyOf(head, cap);
            Arrays.fill(head, old, cap, -1);
        }
        int id = nodeCount++;
        nodeX[id] = x;
        nodeZ[id] = z;
        head[id] = -1;
        return id;
    }

    private void link(int from, int to, float cost) {
        if (from < 0 || to < 0 || from == to) {
            return;
        }
        addEdge(from, to, cost);
    }

    private void addEdge(int from, int to, float cost) {
        if (from < 0 || to < 0 || from == to || cost <= 0f) {
            return;
        }
        if (edgeCount == edgeTo.length) {
            int cap = edgeTo.length * 2;
            edgeTo = Arrays.copyOf(edgeTo, cap);
            edgeCost = Arrays.copyOf(edgeCost, cap);
            edgeNext = Arrays.copyOf(edgeNext, cap);
        }
        edgeTo[edgeCount] = to;
        edgeCost[edgeCount] = cost;
        edgeNext[edgeCount] = head[from];
        head[from] = edgeCount++;
    }

    private static float distance(float ax, float az, float bx, float bz) {
        return (float) Math.hypot(bx - ax, bz - az);
    }

    /** Distance from a point to the planned polyline, metres. */
    public static float deviation(float[] route, float x, float z) {
        if (route == null || route.length < 4) {
            return 0f;
        }
        float best = Float.MAX_VALUE;
        for (int i = 0; i + 3 < route.length; i += 2) {
            float ax = route[i], az = route[i + 1];
            float bx = route[i + 2], bz = route[i + 3];
            float dx = bx - ax, dz = bz - az;
            float len2 = dx * dx + dz * dz;
            float t = len2 > 1e-6f ? ((x - ax) * dx + (z - az) * dz) / len2 : 0f;
            t = Math.max(0f, Math.min(1f, t));
            float px = ax + dx * t, pz = az + dz * t;
            float d = (float) Math.hypot(px - x, pz - z);
            if (d < best) {
                best = d;
            }
        }
        return best == Float.MAX_VALUE ? 0f : best;
    }
}

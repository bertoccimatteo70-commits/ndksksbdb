package it.pta.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* router over the baked drivable ways. Edges that sample a solid building
 * footprint are rejected, so a GPS route follows the street geometry instead of
 * cutting through the city. Built lazily and in plain Java so it is testable on
 * the JVM without OpenGL.
 */
public final class RoadRouter {
    public static final class Route {
        public final boolean found;
        public final float[] points;
        public final float length;
        public final String message;

        Route(boolean found, float[] points, float length, String message) {
            this.found = found;
            this.points = points;
            this.length = length;
            this.message = message;
        }
    }

    private static final class Edge {
        final int to;
        final float cost;

        Edge(int to, float cost) {
            this.to = to;
            this.cost = cost;
        }
    }

    private final MapData map;
    private final ArrayList<float[]> nodes = new ArrayList<>();
    private final ArrayList<ArrayList<Edge>> edges = new ArrayList<>();
    private final Map<Long, Integer> nodeIds = new HashMap<>();
    private boolean built;

    public RoadRouter(MapData map) {
        this.map = map;
    }

    public Route route(float startX, float startZ, float targetX, float targetZ) {
        ensureGraph();
        if (nodes.isEmpty()) {
            return new Route(false, new float[0], 0f, "Rete stradale non disponibile");
        }
        MapData.RoadHit startHit = map.nearestRoad(startX, startZ, true, 180f);
        MapData.RoadHit targetHit = map.nearestRoad(targetX, targetZ, true, 260f);
        if (startHit == null || targetHit == null) {
            return new Route(false, new float[0], 0f, "Nessuna strada vicina");
        }
        int start = roadNode(startHit);
        int target = roadNode(targetHit);
        if (start < 0 || target < 0) {
            return new Route(false, new float[0], 0f, "Nessun percorso stradale");
        }
        int[] prev = search(start, target);
        if (prev == null) {
            return new Route(false, new float[0], 0f, "Nessun percorso stradale");
        }
        ArrayList<float[]> path = new ArrayList<>();
        for (int at = target; at >= 0; at = prev[at]) {
            float[] n = nodes.get(at);
            path.add(new float[]{n[0], n[1]});
        }
        java.util.Collections.reverse(path);
        // Keep the route on the carriageway at both ends. The last point is the
        // road under the marker, never the marker itself inside a building.
        if (distance(path.get(0)[0], path.get(0)[1], startHit.x, startHit.z) < 40f
                && distance(startX, startZ, startHit.x, startHit.z) < 25f) {
            path.add(0, new float[]{startHit.x, startHit.z});
        }
        float[] last = path.get(path.size() - 1);
        if (distance(last[0], last[1], targetHit.x, targetHit.z) < 50f) {
            path.add(new float[]{targetHit.x, targetHit.z});
        }
        float length = 0f;
        for (int i = 1; i < path.size(); i++) {
            length += distance(path.get(i - 1)[0], path.get(i - 1)[1],
                    path.get(i)[0], path.get(i)[1]);
        }
        float[] out = new float[path.size() * 2];
        for (int i = 0; i < path.size(); i++) {
            out[i * 2] = path.get(i)[0];
            out[i * 2 + 1] = path.get(i)[1];
        }
        if (out.length < 4) {
            return new Route(false, new float[0], 0f, "Percorso troppo corto");
        }
        return new Route(true, out, length, "Percorso stradale");
    }

    private int roadNode(MapData.RoadHit hit) {
        if (hit == null || hit.way == null) {
            return -1;
        }
        int a = hit.segment * 2;
        if (a + 3 >= hit.way.pts.length) {
            return -1;
        }
        int na = nodeFor(hit.way.pts[a], hit.way.pts[a + 1], false);
        int nb = nodeFor(hit.way.pts[a + 2], hit.way.pts[a + 3], false);
        if (na < 0) return nb;
        if (nb < 0) return na;
        float da = distance(hit.x, hit.z, nodes.get(na)[0], nodes.get(na)[1]);
        float db = distance(hit.x, hit.z, nodes.get(nb)[0], nodes.get(nb)[1]);
        return da <= db ? na : nb;
    }

    private int[] search(int start, int target) {
        int n = nodes.size();
        float[] dist = new float[n];
        int[] prev = new int[n];
        boolean[] done = new boolean[n];
        Arrays.fill(dist, Float.MAX_VALUE);
        Arrays.fill(prev, -1);
        dist[start] = 0f;
        PriorityQueue<float[]> open = new PriorityQueue<>((a, b) ->
                Float.compare(a[1], b[1]));
        open.add(new float[]{start, 0f});
        while (!open.isEmpty()) {
            float[] item = open.poll();
            int at = (int) item[0];
            if (item[1] > dist[at] || done[at]) continue;
            if (at == target) return prev;
            done[at] = true;
            for (Edge e : edges.get(at)) {
                if (done[e.to]) continue;
                float nd = dist[at] + e.cost;
                if (nd < dist[e.to]) {
                    dist[e.to] = nd;
                    prev[e.to] = at;
                    open.add(new float[]{e.to, nd});
                }
            }
        }
        return null;
    }

    private void ensureGraph() {
        if (built) return;
        built = true;
        boolean[] seenRoad = new boolean[map.roadCount];
        for (int cz = map.minCZ; cz < map.minCZ + map.nCZ; cz++) {
            for (int cx = map.minCX; cx < map.minCX + map.nCX; cx++) {
                for (int idx : map.roadsIn(cx, cz)) {
                    if (idx < 0 || idx >= seenRoad.length || seenRoad[idx]) continue;
                    seenRoad[idx] = true;
                    MapData.RoadWay w = map.road(idx);
                    if (!w.drivable || w.cls > 8 || w.pts.length < 4) continue;
                    int[] ids = new int[w.pts.length / 2];
                    for (int i = 0; i < ids.length; i++) {
                        ids[i] = nodeFor(w.pts[i * 2], w.pts[i * 2 + 1], true);
                    }
                    for (int i = 0; i + 1 < ids.length; i++) {
                        float ax = w.pts[i * 2], az = w.pts[i * 2 + 1];
                        float bx = w.pts[i * 2 + 2], bz = w.pts[i * 2 + 3];
                        float len = distance(ax, az, bx, bz);
                        if (len < 0.25f || crossesBuilding(ax, az, bx, bz)) continue;
                        link(ids[i], ids[i + 1], len);
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
            if (map.blocked(x, z, 0.18f)) return true;
        }
        return false;
    }

    private int nodeFor(float x, float z, boolean create) {
        long key = ((long) Math.round(x * 2f) << 32)
                ^ (Math.round(z * 2f) & 0xffffffffL);
        Integer old = nodeIds.get(key);
        if (old != null) return old;
        if (!create) return -1;
        int id = nodes.size();
        nodes.add(new float[]{x, z});
        edges.add(new ArrayList<>());
        nodeIds.put(key, id);
        return id;
    }

    private void link(int a, int b, float cost) {
        if (a < 0 || b < 0 || a == b) return;
        edges.get(a).add(new Edge(b, cost));
        edges.get(b).add(new Edge(a, cost));
    }

    private static float distance(float ax, float az, float bx, float bz) {
        return (float) Math.hypot(bx - ax, bz - az);
    }
}

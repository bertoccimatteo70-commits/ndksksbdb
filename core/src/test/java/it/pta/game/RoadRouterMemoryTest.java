package it.pta.game;

import it.pta.world.MapData;
import it.pta.world.RoadRouter;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The router used to build the junction graph of the whole province (149.225 nodes,
 * 280.992 edges) in Java structures, which on a 192 MB heap alongside the streaming
 * city ended in OutOfMemoryError. These tests pin the corridor behaviour that replaced
 * it: a route only builds the part of the graph it can possibly use.
 */
public class RoadRouterMemoryTest {
    private File mapFile() {
        File f = new File("../app/src/main/assets/map/pta_map.bin");
        if (f.exists()) {
            return f;
        }
        f = new File("app/src/main/assets/map/pta_map.bin");
        if (f.exists()) {
            return f;
        }
        File parent = new File(System.getProperty("user.dir")).getParentFile();
        return new File(parent, "app/src/main/assets/map/pta_map.bin");
    }

    private static final float START_X = 40.08344f;
    private static final float START_Z = -11.0574f;

    /** A real point of interest as far as possible from the start, on the road net. */
    private MapData.RoadHit farDestination(MapData map) {
        MapData.RoadHit best = null;
        float bestDist = 0f;
        for (MapData.Poi p : map.pois) {
            float d = (p.x - START_X) * (p.x - START_X) + (p.z - START_Z) * (p.z - START_Z);
            if (d <= bestDist) {
                continue;
            }
            MapData.RoadHit hit = map.nearestRoad(p.x, p.z, true, 600f);
            if (hit != null) {
                best = hit;
                bestDist = d;
            }
        }
        return best;
    }

    @Test
    public void longRouteBuildsOnlyItsCorridor() {
        MapData map = MapData.open(mapFile());
        MapData.RoadHit a = map.nearestRoad(START_X, START_Z, true, 200f);
        MapData.RoadHit b = farDestination(map);
        assertNotNull("start must snap to a road", a);
        assertNotNull("destination must snap to a road", b);

        RoadRouter router = new RoadRouter(map);
        router.route(a.x, a.z, b.x, b.z);

        assertTrue("the route must build something", router.nodeCount() > 0);
        assertTrue("corridor graph is " + router.nodeCount() + " nodes and "
                        + router.edgeCount() + " edges",
                router.nodeCount() < 60000);
        assertTrue("corridor edges are " + router.edgeCount(), router.edgeCount() < 140000);
    }

    @Test
    public void repeatedRoutesReuseOrRebuildSafely() {
        MapData map = MapData.open(mapFile());
        MapData.RoadHit a = map.nearestRoad(START_X, START_Z, true, 200f);
        MapData.RoadHit b = farDestination(map);
        assertNotNull(a);
        assertNotNull(b);
        RoadRouter router = new RoadRouter(map);
        for (int i = 0; i < 3; i++) {
            router.route(a.x, a.z, b.x, b.z);
            router.route(b.x, b.z, a.x, a.z);
        }
        assertTrue("graph must stay bounded across rebuilds: " + router.nodeCount(),
                router.nodeCount() < 60000);
    }
}

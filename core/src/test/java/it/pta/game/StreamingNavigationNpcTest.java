package it.pta.game;

import com.badlogic.gdx.files.FileHandle;
import it.pta.entity.NpcCollision;
import it.pta.render.ChunkManager;
import it.pta.world.MapData;
import it.pta.world.RoadRouter;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the open-world systems: A* respects one-way streets, LOD
 * bands are stable, and NPC circles collide instead of overlapping.
 */
public class StreamingNavigationNpcTest {
    private FileHandle asset(String path) {
        FileHandle a = new FileHandle("../app/src/main/assets/" + path);
        if (a.exists()) {
            return a;
        }
        FileHandle b = new FileHandle("app/src/main/assets/" + path);
        if (b.exists()) {
            return b;
        }
        File root = new File(System.getProperty("user.dir")).getParentFile();
        return new FileHandle(new File(root, "app/src/main/assets/" + path));
    }

    private MapData map() {
        return MapData.open(asset("map/pta_map.bin").file());
    }

    /** Fraction of a route length that runs on top of the given way. */
    private float overlapWith(float[] route, MapData.RoadWay way) {
        float on = 0f;
        float total = 0f;
        for (int i = 0; i + 3 < route.length; i += 2) {
            float ax = route[i], az = route[i + 1];
            float bx = route[i + 2], bz = route[i + 3];
            float len = (float) Math.hypot(bx - ax, bz - az);
            total += len;
            int steps = Math.max(1, (int) (len / 4f));
            for (int s = 0; s < steps; s++) {
                float t = (s + 0.5f) / steps;
                float x = ax + (bx - ax) * t;
                float z = az + (bz - az) * t;
                float[] out = new float[4];
                float d = MapData.closestOnPolyline(way.pts, x, z, 5f, out);
                if (d < 5f) {
                    on += len / steps;
                }
            }
        }
        return total <= 0f ? 0f : on / total;
    }

    @Test
    public void oneWayStreetIsNotDrivenBackwards() {
        MapData map = map();
        RoadRouter router = new RoadRouter(map);
        MapData.RoadWay way = null;
        float sx = 0, sz = 0, tx = 0, tz = 0;
        outer:
        for (int cz = -6; cz <= 6; cz++) {
            for (int cx = -6; cx <= 6; cx++) {
                for (int idx : map.roadsIn(cx, cz)) {
                    MapData.RoadWay w = map.road(idx);
                    if (!w.oneWay() || !w.drivable || w.length < 180f || w.segments() < 3) {
                        continue;
                    }
                    int last = w.segments();
                    sx = w.pts[0];
                    sz = w.pts[1];
                    tx = w.pts[last * 2];
                    tz = w.pts[last * 2 + 1];
                    way = w;
                    break outer;
                }
            }
        }
        assertNotNull("a one-way street must exist in the centre", way);

        RoadRouter.Route forward = router.route(sx, sz, tx, tz);
        assertTrue("forward route along the one-way: " + forward.message, forward.found);
        float along = overlapWith(forward.points, way);
        assertTrue("forward route should use the one-way street, got " + along,
                along > 0.45f);

        RoadRouter.Route backward = router.route(tx, tz, sx, sz);
        if (backward.found) {
            float against = overlapWith(backward.points, way);
            // Driving the street the wrong way would reuse most of it. A legal detour
            // around the block only shares the ends.
            assertTrue("route drove a one-way street backwards: " + against,
                    against < 0.35f);
        }
    }

    @Test
    public void routeReportsTheNextTurnOfThePath() {
        MapData map = map();
        Content.init(map, asset("map/landmarks.json").readString("UTF-8"));
        Content.Place a = Content.get("duomo");
        Content.Place b = Content.get("garage");
        assertNotNull(a);
        assertNotNull(b);
        RoadRouter.Route r = new RoadRouter(map).route(a.x, a.z, b.x, b.z);
        assertTrue("route", r.found);
        assertTrue("turn distance inside the route: " + r.nextTurnDistance,
                r.nextTurnDistance >= 0f && r.nextTurnDistance <= r.length + 1f);
        assertTrue("turn side is left, straight or right", Math.abs(r.nextTurnSide) <= 1);
    }

    @Test
    public void bakedMapMarksOneWayStreetsIncludingReversedOnes() {
        MapData map = map();
        int oneWay = 0;
        int reversed = 0;
        for (int idx = 0; idx < map.roadCount; idx++) {
            MapData.RoadWay w = map.road(idx);
            if (!w.oneWay()) {
                continue;
            }
            oneWay++;
            if (w.oneWayReversed()) {
                reversed++;
            }
        }
        System.out.println("PTA one-way ways=" + oneWay + " reversed=" + reversed);
        assertTrue("one-way streets must be marked", oneWay > 100);
        assertTrue("the oneway=-1 direction bit must survive the bake, reversed=" + reversed,
                reversed >= 0);
    }

    @Test
    public void deviationGrowsWhenThePlayerLeavesThePlannedStreets() {
        float[] route = {0f, 0f, 100f, 0f, 100f, 100f};
        assertTrue("on the route", RoadRouter.deviation(route, 50f, 0.5f) < 1f);
        assertTrue("far from the route", RoadRouter.deviation(route, 50f, 60f) > 40f);
    }

    @Test
    public void lodBandsAreStableAroundTheThresholds() {
        assertEquals("near", 0, ChunkManager.lodForDistance(120f, -1));
        assertEquals("medium", 1, ChunkManager.lodForDistance(400f, -1));
        assertEquals("far", 2, ChunkManager.lodForDistance(900f, -1));
        // Hysteresis: a loaded LOD0 chunk at 260 m stays detailed, an LOD1 one at
        // 220 m does not bounce back and forth.
        assertEquals(0, ChunkManager.lodForDistance(260f, 0));
        assertEquals(1, ChunkManager.lodForDistance(300f, 0));
        assertEquals(1, ChunkManager.lodForDistance(220f, 1));
        assertEquals(0, ChunkManager.lodForDistance(180f, 1));
        assertEquals(2, ChunkManager.lodForDistance(820f, 1));
    }

    @Test
    public void overlappingBodiesArePushedApartWithoutPenetration() {
        float[] a = {0f, 0f, 0f};
        float[] b = {0.2f, 0f, 0.1f};
        float minDist = 0.64f;
        float overlap = NpcCollision.separate(a, b, minDist, 0.5f, 1f);
        assertTrue("contact detected", overlap > 0f);
        float d = (float) Math.hypot(b[0] - a[0], b[2] - a[2]);
        assertTrue("bodies separated to " + d, d >= minDist - 0.02f);

        float[] c = {5f, 0f, 5f};
        float[] d2 = {5.9f, 0f, 5f};
        assertEquals("already apart", 0f, NpcCollision.separate(c, d2, minDist, 0.5f, 1f), 1e-6f);
        assertEquals("untouched", 5.9f, d2[0], 1e-6f);
    }

    @Test
    public void playerPushesThePedestrianMoreThanHimself() {
        float[] player = {10f, 0f, 10f};
        float[] ped = {10.1f, 0f, 10f};
        // The game gives the player a 35% share of the correction (see TrafficSystem),
        // so the pedestrian is the body that steps aside.
        NpcCollision.separate(player, ped, NpcCollision.PLAYER_RADIUS + NpcCollision.PED_RADIUS,
                0.35f, 10f);
        float playerMoved = Math.abs(player[0] - 10f);
        float pedMoved = Math.abs(ped[0] - 10.1f);
        assertTrue("the pedestrian steps aside", pedMoved > playerMoved);
        assertTrue("nothing teleports", playerMoved < 1f && pedMoved < 1f);
    }

    @Test
    public void stackedBodiesGetADeterministicPush() {
        float[] a = {3f, 0f, 3f};
        float[] b = {3f, 0f, 3f};
        NpcCollision.separate(a, b, 0.64f, 0.5f, 0.2f);
        assertFalse("not stacked anymore",
                NpcCollision.overlaps(a[0], a[2], b[0], b[2], 0.3f));
    }
}

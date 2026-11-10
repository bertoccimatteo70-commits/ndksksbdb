package it.pta.game;

import com.badlogic.gdx.files.FileHandle;
import it.pta.entity.Player;
import it.pta.entity.Vehicle;
import it.pta.entity.VehicleType;
import it.pta.world.MapData;
import it.pta.world.RoadRouter;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the cases that made buildings feel like decorations:
 * long polygon edges, corners, running, vehicle sweep and a road-following GPS.
 */
public class CollisionAndNavigationTest {
    private FileHandle asset(String path) {
        FileHandle a = new FileHandle("../app/src/main/assets/" + path);
        if (a.exists()) return a;
        FileHandle b = new FileHandle("app/src/main/assets/" + path);
        if (b.exists()) return b;
        File root = new File(System.getProperty("user.dir")).getParentFile();
        return new FileHandle(new File(root, "app/src/main/assets/" + path));
    }

    private MapData map() {
        return MapData.open(asset("map/pta_map.bin").file());
    }

    private MapData.Building testBuilding(MapData map) {
        for (int cz = -2; cz <= 2; cz++) {
            for (int cx = -2; cx <= 2; cx++) {
                for (int id : map.buildingsIn(cx, cz)) {
                    MapData.Building b = map.building(id);
                    if (!b.collides || b.area < 20f || b.height < 4f) continue;
                    // Prefer a long edge so the old vertex-only distance is clearly wrong.
                    for (int i = 0, j = b.vertexCount() - 1; i < b.vertexCount(); j = i++) {
                        if (Math.hypot(b.polyX(i) - b.polyX(j), b.polyZ(i) - b.polyZ(j)) > 8f) {
                            return b;
                        }
                    }
                }
            }
        }
        throw new AssertionError("no suitable building");
    }

    @Test
    public void collisionUsesPolygonEdgesAndLeavesNoPlayerInside() {
        MapData map = map();
        MapData.Building b = testBuilding(map);
        float[] edge = new float[4];
        int best = -1;
        float bestLen = 0f;
        for (int i = 0, j = b.vertexCount() - 1; i < b.vertexCount(); j = i++) {
            float len = (float) Math.hypot(b.polyX(i) - b.polyX(j), b.polyZ(i) - b.polyZ(j));
            if (len > bestLen) {
                bestLen = len;
                best = i;
            }
        }
        int j = (best + b.vertexCount() - 1) % b.vertexCount();
        float mx = (b.polyX(best) + b.polyX(j)) * 0.5f;
        float mz = (b.polyZ(best) + b.polyZ(j)) * 0.5f;
        float ex = b.polyX(best) - b.polyX(j);
        float ez = b.polyZ(best) - b.polyZ(j);
        float el = (float) Math.hypot(ex, ez);
        float nx = b.signedArea >= 0f ? ez / el : -ez / el;
        float nz = b.signedArea >= 0f ? -ex / el : ex / el;

        float outsideX = mx + nx * 0.20f;
        float outsideZ = mz + nz * 0.20f;
        assertFalse("test point must be outside", b.contains(outsideX, outsideZ));
        // The former nearest-vertex implementation reported the distance to the
        // corner, often metres away. The fixed version reports the perpendicular
        // distance to the wall.
        assertTrue("distance must be measured from the edge, was " + b.distanceTo(outsideX, outsideZ),
                b.distanceTo(outsideX, outsideZ) < 0.35f);
        assertTrue("a 34 cm body must collide with the wall", map.blocked(outsideX, outsideZ, 0.34f));

        // Start just inside the same long wall and resolve: the body must be
        // pushed through the nearest edge, not teleported to a polygon vertex.
        float[] pos = {mx - nx * 0.12f, 0f, mz - nz * 0.12f};
        b.nearestBoundary(pos[0], pos[2], edge);
        assertTrue("precondition: start inside the footprint", b.contains(pos[0], pos[2]));
        map.resolveCollision(pos, 0.34f);
        assertFalse("resolved player is still inside", b.contains(pos[0], pos[2]));
        assertFalse("resolved player overlaps a wall", map.blocked(pos[0], pos[2], 0.30f));
    }

    @Test
    public void walkingAndRunningIntoAWallNeverEnterTheFootprint() {
        MapData map = map();
        MapData.Building b = testBuilding(map);
        float x = (b.minX + b.maxX) * 0.5f;
        float z = b.maxZ + 1.4f;
        Player p = new Player(0, null);
        p.setPosition(x, z, map);
        for (int i = 0; i < 180; i++) {
            p.update(1f / 60f, map, 0f, 1f, 0f, true, false, false);
            assertFalse("running player entered building at frame " + i, b.contains(p.x, p.z));
        }
        assertTrue("player should stay on the street side", p.z > b.maxZ - 0.05f);
    }

    @Test
    public void fastVehicleSweepStopsAtAWallAndStillReacts() {
        MapData map = map();
        MapData.Building b = testBuilding(map);
        float x = (b.minX + b.maxX) * 0.5f;
        float z = b.maxZ + 9f;
        Vehicle v = new Vehicle(VehicleType.HATCHBACK, null, x, z, 0f, map);
        v.speed = 34f;
        boolean reacted = false;
        for (int i = 0; i < 24; i++) {
            v.update(0.08f, map, 1f, 0f, false, true, 1f);
            assertFalse("vehicle passed through building at frame " + i, b.contains(v.x, v.z));
            if (v.lastImpact > 0f || v.speed < 0f) reacted = true;
        }
        assertTrue("vehicle must register the impact", reacted);
        assertTrue("vehicle must remain outside the wall", v.z > b.maxZ - 0.1f);
    }

    @Test
    public void gpsRouteFollowsDrivableRoadsAndNeverCutsThroughBuildings() {
        MapData map = map();
        Content.init(map, asset("map/landmarks.json").readString("UTF-8"));
        Content.Place a = Content.get("duomo");
        Content.Place b = Content.get("garage");
        assertNotNull(a);
        assertNotNull(b);
        RoadRouter.Route r = new RoadRouter(map).route(a.x, a.z, b.x, b.z);
        assertTrue("expected a connected road route: " + r.message, r.found);
        assertTrue("route must have geometry", r.points.length >= 4);
        assertTrue("route should not be a straight replacement", r.points.length > 4);
        for (int i = 0; i + 3 < r.points.length; i += 2) {
            for (int s = 1; s <= 3; s++) {
                float t = s / 4f;
                float x = r.points[i] + (r.points[i + 2] - r.points[i]) * t;
                float z = r.points[i + 1] + (r.points[i + 3] - r.points[i + 1]) * t;
                assertFalse("GPS crosses a building at " + x + "," + z,
                        map.blocked(x, z, 0.12f));
            }
        }
    }
}

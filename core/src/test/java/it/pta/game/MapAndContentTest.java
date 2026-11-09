package it.pta.game;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import it.pta.world.Geo;
import it.pta.world.MapData;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MapAndContentTest {
    private FileHandle asset(String path) {
        FileHandle a = new FileHandle("../app/src/main/assets/" + path);
        if (a.exists()) {
            return a;
        }
        FileHandle b = new FileHandle("app/src/main/assets/" + path);
        if (b.exists()) {
            return b;
        }
        File projectRoot = new File(System.getProperty("user.dir")).getParentFile();
        return new FileHandle(new File(projectRoot, "app/src/main/assets/" + path));
    }

    @Test
    public void realPistoiaMapLoadsWithStreetsAndElevation() {
        FileHandle f = asset("map/pta_map.bin");
        assertTrue("map binary missing at " + f.file().getAbsolutePath(), f.exists());
        MapData map = MapData.open(f.file());
        // These are the complete OSM counts baked in pta_map.json. None may be cut.
        assertEquals("buildings", 71844, map.buildingCount);
        assertEquals("roads", 26296, map.roadCount);
        assertEquals("areas", 4785, map.areaCount);
        assertEquals("pois", 2321, map.poiCount);
        assertEquals("names", 3876, map.nameCount);
        map.verifyCounts();
        JsonValue meta = new JsonReader().parse(asset("map/pta_map.json").readString("UTF-8"));
        JsonValue counts = meta.get("counts");
        assertEquals("json buildings", counts.getInt("buildings"), map.buildingCount);
        assertEquals("json roads", counts.getInt("roads"), map.roadCount);
        assertEquals("json areas", counts.getInt("areas"), map.areaCount);
        assertEquals("json pois", counts.getInt("pois"), map.poiCount);

        float duomoX = Geo.x(10.91720);
        float duomoZ = Geo.z(43.93340);
        MapData.RoadHit hit = map.nearestRoad(duomoX, duomoZ, true, 80f);
        assertNotNull("a drivable street must exist at the Duomo", hit);
        assertTrue("street width", hit.way.width > 2f);

        float pistoia = map.heightAt(duomoX, duomoZ);
        float serravalle = map.heightAt(Geo.x(10.83260), Geo.z(43.90640));
        assertTrue("Pistoia elevation plausible: " + pistoia, pistoia > -20 && pistoia < 300);
        assertTrue("Serravalle hills must be higher than Pistoia: " + serravalle + " vs " + pistoia,
                serravalle > pistoia + 20);
    }

    @Test
    public void everyOsmBuildingIsStillIndexed() {
        MapData map = MapData.open(asset("map/pta_map.bin").file());
        boolean[] seen = new boolean[map.buildingCount];
        for (int cz = map.minCZ; cz < map.minCZ + map.nCZ; cz++) {
            for (int cx = map.minCX; cx < map.minCX + map.nCX; cx++) {
                for (int id : map.buildingsIn(cx, cz)) {
                    assertTrue("building id in range", id >= 0 && id < seen.length);
                    seen[id] = true;
                }
            }
        }
        for (int i = 0; i < seen.length; i++) {
            assertTrue("building missing from chunks: " + i, seen[i]);
        }
    }

    @Test
    public void allSeventyOsmInteriorsAreAvailableAndLoadedOnDemand() {
        FileHandle f = asset("map/pta_map.bin");
        MapData map = MapData.open(f.file());
        Content.init(map, asset("map/landmarks.json").readString("UTF-8"));
        assertEquals("interiors", 70, Content.interiors().size());
        assertEquals("streaming cache starts empty", 0, map.cachedBuildingChunks());
        // Reading chunks must not decode the whole province, and the cache must stay
        // bounded even after walking across many chunks.
        int touched = 0;
        for (int cz = -4; cz <= 4; cz++) {
            for (int cx = -4; cx <= 4; cx++) {
                int[] ids = map.buildingsIn(cx, cz);
                if (ids.length > 0) {
                    map.building(ids[0]);
                    touched++;
                }
            }
        }
        assertTrue("chunks touched", touched > 2);
        assertTrue("bounded building cache: " + map.cachedBuildingChunks(),
                map.cachedBuildingChunks() <= 16);
        assertTrue("bounded road cache", map.cachedRoads() <= 1024);
        map.releaseAllChunks();
        assertEquals("release", 0, map.cachedBuildingChunks());
    }

    @Test
    public void garageSpawnPointIsWalkable() {
        MapData map = MapData.open(asset("map/pta_map.bin").file());
        Content.init(map, asset("map/landmarks.json").readString("UTF-8"));
        Content.Place garage = Content.get("garage");
        assertNotNull("garage place", garage);
        MapData.RoadHit hit = map.nearestRoad(garage.x, garage.z, true, 160f);
        assertNotNull("road near garage", hit);
        float x = hit.x;
        float z = hit.z;
        // This is the same candidate used by World.spawnAtStart. It must not start
        // inside a building footprint, otherwise the first frame is a black wall.
        assertTrue("garage spawn must be walkable at " + x + "," + z,
                !map.blocked(x, z, 0.55f));
        float h = map.heightAt(x, z);
        assertTrue("garage spawn height plausible: " + h, h > -20 && h < 300);
        // Camera position logged by the Android build for the same spawn.
        assertTrue("garage camera must not be inside a wall",
                !map.blocked(-242.66412f, -62.891857f, 0.4f));
        // The initial view must follow the road instead of facing the nearest facade.
        int seg = hit.segment * 2;
        float dx = hit.way.pts[seg + 2] - hit.way.pts[seg];
        float dz = hit.way.pts[seg + 3] - hit.way.pts[seg + 1];
        float yaw = (float) Math.atan2(dx, -dz);
        float lookX = hit.x + (float) Math.sin(yaw) * 10f;
        float lookZ = hit.z - (float) Math.cos(yaw) * 10f;
        assertTrue("spawn view must follow an open road", !map.blocked(lookX, lookZ, 0.55f));
    }

    @Test
    public void openDuomoSpawnHasAReadableView() {
        MapData map = MapData.open(asset("map/pta_map.bin").file());
        Content.init(map, asset("map/landmarks.json").readString("UTF-8"));
        Content.Place duomo = Content.get("duomo");
        assertNotNull("duomo place", duomo);
        assertFalse("duomo spawn must be walkable", map.blocked(duomo.x, duomo.z, 0.55f));
        // The camera starts south of the player and looks north across the piazza.
        assertFalse("duomo camera must not be inside a facade",
                map.blocked(duomo.x, duomo.z + 5.05f, 0.4f));
    }

    @Test
    public void campaignHasTwelveCompleteMissions() {
        FileHandle f = asset("data/missions.json");
        assertTrue("missions file missing", f.exists());
        JsonValue root = new JsonReader().parse(f.readString("UTF-8"));
        JsonValue arr = root.get("missions");
        assertNotNull(arr);
        int main = 0;
        int side = 0;
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (JsonValue m = arr.child; m != null; m = m.next) {
            String id = m.getString("id", "");
            assertTrue("mission id", id.startsWith("m") || id.startsWith("s"));
            assertTrue("unique id " + id, ids.add(id));
            assertTrue("title", m.getString("title", "").length() > 3);
            assertTrue("reward", m.getInt("reward", 0) > 0);
            JsonValue objs = m.get("objectives");
            assertNotNull("objectives for " + id, objs);
            assertTrue("at least one objective for " + id, objs.child != null);
            JsonValue intro = m.get("intro");
            assertNotNull("dialogue intro for " + id, intro);
            assertTrue("intro lines for " + id, intro.child != null);
            if (m.getBoolean("side", false)) {
                side++;
            } else {
                main++;
            }
        }
        assertEquals("main missions", 12, main);
        assertTrue("side missions", side >= 2);
    }

    @Test
    public void landmarksCoverTheRequestedTowns() {
        FileHandle f = asset("map/landmarks.json");
        JsonValue root = new JsonReader().parse(f.readString("UTF-8"));
        String all = root.toString().toLowerCase();
        String[] towns = {"pistoia", "montecatini", "pescia", "quarrata", "agliana", "montale",
                "serravalle", "casalguidi"};
        for (String t : towns) {
            assertTrue("missing town " + t, all.contains(t));
        }
    }
}

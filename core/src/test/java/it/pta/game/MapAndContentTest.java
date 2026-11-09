package it.pta.game;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import it.pta.world.Geo;
import it.pta.world.MapData;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
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
        MapData map = MapData.load(f.readBytes());
        assertTrue("buildings", map.buildings.length > 60000);
        assertTrue("roads", map.roads.length > 20000);
        assertTrue("areas", map.areas.length > 3000);
        assertTrue("pois", map.pois.length > 1000);

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

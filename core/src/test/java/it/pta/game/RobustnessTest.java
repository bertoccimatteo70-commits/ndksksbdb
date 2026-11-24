package it.pta.game;

import com.badlogic.gdx.files.FileHandle;
import it.pta.world.MapData;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the failure modes that matter in the field: a corrupted save
 * must never crash or teleport the player, and touching many map zones must not grow
 * the decoded caches without bound.
 */
public class RobustnessTest {
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

    private FileHandle tempDir() throws Exception {
        File dir = Files.createTempDirectory("pta-saves").toFile();
        return new FileHandle(dir);
    }

    @Test
    public void saveAndLoadRoundTripKeepsPosition() throws Exception {
        SaveManager saves = new SaveManager(tempDir());
        SaveManager.SaveData data = new SaveManager.SaveData();
        data.x = 123.5f;
        data.z = -450.25f;
        data.cash = 4200;
        data.playTime = 600f;
        assertTrue(saves.save(1, data));
        SaveManager.SaveData back = saves.load(1);
        assertNotNull(back);
        assertEquals(123.5f, back.x, 1e-4f);
        assertEquals(-450.25f, back.z, 1e-4f);
        assertEquals(4200, back.cash);
        assertEquals(SaveManager.VERSION, back.version);
    }

    @Test
    public void corruptedSaveFallsBackToThePreviousGoodFile() throws Exception {
        FileHandle dir = tempDir();
        SaveManager saves = new SaveManager(dir);
        SaveManager.SaveData first = new SaveManager.SaveData();
        first.x = 10f;
        first.z = 20f;
        assertTrue(saves.save(2, first));
        SaveManager.SaveData second = new SaveManager.SaveData();
        second.x = 99f;
        second.z = 88f;
        assertTrue(saves.save(2, second));

        // Simulate a half-written file: the loader must not throw and must recover.
        saves.fileFor(2).writeString("{ this is not json", false, "UTF-8");
        SaveManager.SaveData recovered = saves.load(2);
        assertNotNull("backup must be used", recovered);
        assertTrue("recovered coordinates are finite",
                Float.isFinite(recovered.x) && Float.isFinite(recovered.z));
        assertTrue("corruption was counted", saves.corruptFiles >= 1);
        assertTrue("recovery was counted", saves.recoveries >= 1);
    }

    @Test
    public void unreadableSaveReturnsNullInsteadOfThrowing() throws Exception {
        FileHandle dir = tempDir();
        SaveManager saves = new SaveManager(dir);
        saves.fileFor(3).writeString("", false, "UTF-8");
        assertNull(saves.load(3));
        assertTrue(saves.corruptFiles >= 1);
    }

    @Test
    public void sanitizingClampsCorruptValues() {
        SaveManager.SaveData data = new SaveManager.SaveData();
        data.x = Float.NaN;
        data.z = Float.POSITIVE_INFINITY;
        data.health = 5000f;
        data.cash = -10;
        data.wanted = 99;
        data.completedMissions = null;
        SaveManager.sanitize(data);
        assertEquals(0f, data.x, 0f);
        assertEquals(0f, data.z, 0f);
        assertEquals(100f, data.health, 0f);
        assertEquals(0, data.cash);
        assertEquals(5, data.wanted);
        assertNotNull(data.completedMissions);
    }

    @Test
    public void touchingManyZonesKeepsDecodedCachesBounded() {
        MapData map = MapData.open(asset("map/pta_map.bin").file());
        int touched = 0;
        long start = System.nanoTime();
        for (int cz = map.minCZ; cz < map.minCZ + map.nCZ; cz++) {
            for (int cx = map.minCX; cx < map.minCX + map.nCX; cx++) {
                int[] ids = map.buildingsIn(cx, cz);
                if (ids.length > 0) {
                    map.building(ids[0]);
                    touched++;
                }
                if ((touched % 40) == 0) {
                    assertTrue("building cache must stay bounded: " + map.cachedBuildingChunks(),
                            map.cachedBuildingChunks() <= 16);
                    assertTrue("road cache must stay bounded: " + map.cachedRoads(),
                            map.cachedRoads() <= 1024);
                }
            }
        }
        assertTrue("the soak must visit many zones: " + touched, touched > 500);
        long ms = (System.nanoTime() - start) / 1000000;
        System.out.println("PTA soak: zones=" + touched + " cachedChunks=" + map.cachedBuildingChunks()
                + " cachedRoads=" + map.cachedRoads() + " ms=" + ms);
        map.releaseAllChunks();
        assertEquals(0, map.cachedBuildingChunks());
    }
}

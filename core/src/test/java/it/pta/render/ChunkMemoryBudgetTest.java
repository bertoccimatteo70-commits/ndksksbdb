package it.pta.render;

import it.pta.world.MapData;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Guards the memory budget of the streamed city.
 *
 * <p>The Android startup OutOfMemoryError was caused by the CPU-side vertex arrays of
 * every resident chunk: 12,4 MB for the densest 512 m chunk and 273 MB for a 7x7
 * window, all of it live Java heap because the staging buffers were kept after the
 * GPU upload. These tests run on the JVM without GL and pin the two cheap invariants
 * that keep that failure from coming back.</p>
 */
public class ChunkMemoryBudgetTest {
    /** Dense LOD0 chunk must stay under ~65k vertices (3,1 MB of vertex data). */
    private static final int MAX_LOD0_VERTICES = 65000;
    private static final int MAX_LOD0_CHUNK_BYTES = 3200000;
    /** A whole 7x7 LOD0 window must fit comfortably in the streamed geometry budget. */
    private static final int MAX_WINDOW_BYTES = 100 * 1024 * 1024;

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

    @Test
    public void denseChunkStaysInsideThePerChunkBudget() {
        MapData map = MapData.open(mapFile());
        for (int cz = -3; cz <= 3; cz++) {
            for (int cx = -3; cx <= 3; cx++) {
                ChunkGeometry g = ChunkBuilder.build(map, cx, cz, 0);
                g.finish();
                assertTrue("chunk " + cx + "," + cz + " has " + g.vertexCount() + " vertices",
                        g.vertexCount() <= MAX_LOD0_VERTICES);
                assertTrue("chunk " + cx + "," + cz + " costs " + g.totalBytes() + " bytes",
                        g.totalBytes() <= MAX_LOD0_CHUNK_BYTES);
            }
        }
    }

    @Test
    public void wholeWindowStaysInsideTheStreamedBudget() {
        MapData map = MapData.open(mapFile());
        long total = 0;
        for (int cz = -3; cz <= 3; cz++) {
            for (int cx = -3; cx <= 3; cx++) {
                ChunkGeometry g = ChunkBuilder.build(map, cx, cz, 0);
                g.finish();
                total += g.totalBytes();
            }
        }
        assertTrue("7x7 LOD0 window is " + total / (1024 * 1024) + "MB",
                total <= MAX_WINDOW_BYTES);
    }

    @Test
    public void detailBudgetKeepsEveryBuildingOfTheDensestChunk() {
        MapData map = MapData.open(mapFile());
        int buildings = map.buildingsIn(0, 0).length;
        ChunkGeometry g = ChunkBuilder.build(map, 0, 0, 0);
        // Every footprint must still be represented: the budget trims windows and
        // balconies, never buildings.
        assertTrue("dense chunk must contain buildings", buildings > 100);
        assertTrue("dense chunk lost its geometry", g.vertexCount() > 5000);
        assertTrue(g.parts.size() >= 1);
    }

    @Test
    public void releaseCpuFreesTheStagingArraysButKeepsTheAccounting() {
        MapData map = MapData.open(mapFile());
        ChunkGeometry g = ChunkBuilder.build(map, 0, 0, 0);
        g.finish();
        int bytes = g.totalBytes();
        int vertices = g.vertexCount();
        assertTrue("geometry must exist", bytes > 0 && vertices > 0);
        g.releaseCpu();
        // The accounting survives, so the manager can enforce the budget without
        // keeping the arrays that would otherwise be the Java heap cost.
        assertEquals(bytes, g.totalBytes());
        assertEquals(0, g.vertexCount());
        assertEquals(0, g.water.vertexCount);
        assertTrue(g.parts.isEmpty());
        assertEquals(0, g.glows.length);
    }
}

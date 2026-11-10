package it.pta.render;

import it.pta.world.MapData;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Guards the procedural city overhaul at the geometry level, without GL. */
public class CityGeometryTest {
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
    public void everyLodKeepsTheDuomoDistrictGeometry() {
        MapData map = MapData.open(mapFile());
        ChunkGeometry near = ChunkBuilder.build(map, 0, 0, 0);
        ChunkGeometry middle = ChunkBuilder.build(map, 0, 0, 1);
        ChunkGeometry far = ChunkBuilder.build(map, 0, 0, 2);
        assertTrue("LOD0 must contain facades and street furniture", near.vertexCount() > 5000);
        assertTrue("LOD1 must still contain every building shell", middle.vertexCount() > 1500);
        assertTrue("LOD2 must retain a complete city silhouette", far.vertexCount() > 500);
    }

    @Test
    public void everyLodDrawsRoadSurfacesWithStrongContrast() {
        MapData map = MapData.open(mapFile());
        // Chunk 0,0 contains the dense Duomo street network. Sample the material
        // vertices rather than relying on a GL screenshot: asphalt must be present
        // in all three LODs and must be darker than the surrounding terrain.
        for (int lod = 0; lod <= 2; lod++) {
            ChunkGeometry g = ChunkBuilder.build(map, 0, 0, lod);
            boolean road = false;
            boolean lightMarking = false;
            for (MeshBuilder part : g.parts) {
                for (int i = 0; i < part.vertexCount; i++) {
                    int p = i * MeshBuilder.FLOATS_PER_VERTEX;
                    float r = part.data[p + 6], gg = part.data[p + 7], b = part.data[p + 8];
                    if (r < 0.55f && gg < 0.55f && b < 0.55f) {
                        road = true;
                    }
                    if (r > 0.84f && gg > 0.82f) {
                        lightMarking = true;
                    }
                }
            }
            assertTrue("LOD" + lod + " must contain asphalt", road);
            assertTrue("LOD" + lod + " must retain a readable road marking", lightMarking);
        }
    }

    @Test
    public void detailedDuomoChunkProducesRaycastableGeometry() {
        MapData map = MapData.open(mapFile());
        ChunkGeometry chunk = ChunkBuilder.build(map, 0, 0, 0);
        assertNotNull(chunk);
        assertTrue("detailed chunk must contain world geometry", chunk.vertexCount() > 2000);
        for (MeshBuilder part : chunk.parts) {
            for (int i = 0; i < part.vertexCount; i++) {
                int p = i * MeshBuilder.FLOATS_PER_VERTEX;
                float x = part.data[p];
                float y = part.data[p + 1];
                float z = part.data[p + 2];
                assertTrue("finite vertex " + i, Float.isFinite(x) && Float.isFinite(y)
                        && Float.isFinite(z));
            }
        }
    }
}

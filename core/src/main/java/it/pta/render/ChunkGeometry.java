package it.pta.render;

import java.util.ArrayList;
import java.util.List;

/**
 * CPU-side chunk geometry, produced on a worker thread and uploaded to the GPU on
 * the render thread. A chunk is split into parts because GLES uses 16-bit indices
 * (60k vertices per part maximum).
 */
public final class ChunkGeometry {
    public final int cx, cz, lod;
    public final List<MeshBuilder> parts = new ArrayList<>();
    public final MeshBuilder water = new MeshBuilder();
    /** Lamp/headlight glow positions, xyz triples, for night-time light blooms. */
    public float[] glows = new float[0];
    public int glowCount;
    public float centerX, centerY, centerZ;
    public float radius = 360f;

    private MeshBuilder current;

    public ChunkGeometry(int cx, int cz, int lod) {
        this.cx = cx;
        this.cz = cz;
        this.lod = lod;
    }

    public MeshBuilder current() {
        if (current == null || current.vertexCount > MeshBuilder.MAX_VERTICES - 1200) {
            current = new MeshBuilder();
            parts.add(current);
        }
        return current;
    }

    public void addGlow(float x, float y, float z) {
        if (glowCount * 3 + 3 > glows.length) {
            glows = java.util.Arrays.copyOf(glows, Math.max(30, glows.length * 2 + 3));
        }
        glows[glowCount * 3] = x;
        glows[glowCount * 3 + 1] = y;
        glows[glowCount * 3 + 2] = z;
        glowCount++;
    }

    public int vertexCount() {
        int n = 0;
        for (MeshBuilder b : parts) {
            n += b.vertexCount;
        }
        return n;
    }
}

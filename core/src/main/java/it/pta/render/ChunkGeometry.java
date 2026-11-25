package it.pta.render;

import java.util.ArrayList;
import java.util.List;

/**
 * CPU-side chunk geometry, produced on a worker thread and uploaded to the GPU on
 * the render thread. A chunk is split into parts because GLES uses 16-bit indices
 * (60k vertices per part maximum).
 *
 * <p>The CPU arrays are only a staging buffer: once the render thread has uploaded
 * them, {@link #releaseCpu()} drops them. A resident chunk therefore costs 12 floats
 * per vertex only while it is being built and uploaded, not for its whole life. That
 * is the difference between a streamed province and a few hundred MB of Java heap.</p>
 */
public final class ChunkGeometry {
    public final int cx, cz, lod;
    public final List<MeshBuilder> parts = new ArrayList<>();
    public MeshBuilder water = new MeshBuilder();
    /** Lamp/headlight glow positions, xyz triples, for night-time light blooms. */
    public float[] glows = new float[0];
    public int glowCount;
    public float centerX, centerY, centerZ;
    public float radius = 360f;
    /** Interleaved vertex bytes and index bytes of this chunk, kept after release. */
    public int vertexBytes;
    public int indexBytes;
    private boolean counted;

    private MeshBuilder current;

    public ChunkGeometry(int cx, int cz, int lod) {
        this.cx = cx;
        this.cz = cz;
        this.lod = lod;
    }

    public MeshBuilder current() {
        if (current == null || current.vertexCount > MeshBuilder.MAX_VERTICES - 1200) {
            current = MeshBuilderPool.acquire();
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

    public int indexCount() {
        int n = water.indexCount;
        for (MeshBuilder b : parts) {
            n += b.indexCount;
        }
        return n;
    }

    /** Total GPU bytes this chunk will occupy, including the water mesh. */
    public int totalBytes() {
        return vertexBytes + indexBytes;
    }

    /**
     * Records the size of the geometry. Must be called before {@link #releaseCpu()},
     * because the arrays that would be counted are gone afterwards.
     */
    public void finish() {
        if (counted) {
            return;
        }
        counted = true;
        vertexBytes = vertexCount() * MeshBuilder.FLOATS_PER_VERTEX * 4;
        indexBytes = indexCount() * 2;
    }

    /**
     * Drops the CPU copies after the GPU upload and returns the compact glow array.
     * The render thread is the only caller: no worker may still touch this geometry.
     */
    public float[] releaseCpu() {
        finish();
        float[] lampPositions = java.util.Arrays.copyOf(glows, glowCount * 3);
        // The buffers go back to the pool instead of being dropped: the next chunk
        // reuses the same arrays, so streaming stops producing megabytes of garbage
        // per second. The pool has its own hard byte cap.
        for (MeshBuilder b : parts) {
            MeshBuilderPool.recycle(b);
        }
        parts.clear();
        MeshBuilderPool.recycle(water);
        water = new MeshBuilder();
        current = null;
        glows = lampPositions;
        return lampPositions;
    }
}

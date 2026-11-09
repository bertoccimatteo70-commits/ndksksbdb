package it.pta.render;

/**
 * Growable interleaved vertex buffer: position(3), normal(3), colour(4), uv(2).
 *
 * The colour alpha channel carries the atlas tile index, and the shader uses it to
 * pick the material sheet, so an entire world chunk is a single draw call.
 */
public final class MeshBuilder {
    public static final int FLOATS_PER_VERTEX = 12;
    public static final int MAX_VERTICES = 60000;

    public float[] data = new float[2048 * FLOATS_PER_VERTEX];
    public int vertexCount;
    public short[] indices = new short[4096];
    public int indexCount;

    public void reset() {
        vertexCount = 0;
        indexCount = 0;
    }

    public boolean isEmpty() {
        return indexCount == 0;
    }

    /** Adds one vertex and returns its index. tile selects the atlas cell (0..8). */
    public int vertex(float x, float y, float z, float nx, float ny, float nz,
                      float r, float g, float b, int tile, float u, float v) {
        if ((vertexCount + 1) * FLOATS_PER_VERTEX > data.length) {
            int cap = Math.max(data.length * 2, (vertexCount + 2) * FLOATS_PER_VERTEX);
            data = java.util.Arrays.copyOf(data, cap);
        }
        int p = vertexCount * FLOATS_PER_VERTEX;
        data[p] = x;
        data[p + 1] = y;
        data[p + 2] = z;
        data[p + 3] = nx;
        data[p + 4] = ny;
        data[p + 5] = nz;
        data[p + 6] = r;
        data[p + 7] = g;
        data[p + 8] = b;
        data[p + 9] = tile;
        data[p + 10] = u;
        data[p + 11] = v;
        return vertexCount++;
    }

    public void tri(int a, int b, int c) {
        if (indexCount + 3 > indices.length) {
            indices = java.util.Arrays.copyOf(indices, Math.max(indices.length * 2, indexCount + 3));
        }
        indices[indexCount++] = (short) a;
        indices[indexCount++] = (short) b;
        indices[indexCount++] = (short) c;
    }

    public void quad(int a, int b, int c, int d) {
        tri(a, b, c);
        tri(a, c, d);
    }

    /** Flat quad with an upward normal, used for ground, roofs and road paint. */
    public void flatQuad(float x0, float z0, float x1, float z1, float y,
                         float r, float g, float b, int tile, float uScale, float vScale) {
        int a = vertex(x0, y, z0, 0, 1, 0, r, g, b, tile, x0 * uScale, z0 * vScale);
        int bb = vertex(x1, y, z0, 0, 1, 0, r, g, b, tile, x1 * uScale, z0 * vScale);
        int c = vertex(x1, y, z1, 0, 1, 0, r, g, b, tile, x1 * uScale, z1 * vScale);
        int d = vertex(x0, y, z1, 0, 1, 0, r, g, b, tile, x0 * uScale, z1 * vScale);
        quad(a, bb, c, d);
    }

    /** Axis-aligned box. Colour is flat, uv is metre-based so textures tile. */
    public void box(float cx, float cy, float cz, float hx, float hy, float hz,
                    float r, float g, float bl, int tile, float uScale) {
        float x0 = cx - hx, x1 = cx + hx;
        float y0 = cy - hy, y1 = cy + hy;
        float z0 = cz - hz, z1 = cz + hz;
        // top
        flatQuad(x0, z0, x1, z1, y1, r, g, bl, tile, uScale, uScale);
        // sides: wallQuad expects the wall height, not the absolute top Y
        float wallHeight = y1 - y0;
        wallQuad(x0, y0, z0, x1, y0, z0, wallHeight, 0, 0, -1, r, g, bl, tile, uScale);
        wallQuad(x1, y0, z0, x1, y0, z1, wallHeight, 1, 0, 0, r, g, bl, tile, uScale);
        wallQuad(x1, y0, z1, x0, y0, z1, wallHeight, 0, 0, 1, r, g, bl, tile, uScale);
        wallQuad(x0, y0, z1, x0, y0, z0, wallHeight, -1, 0, 0, r, g, bl, tile, uScale);
    }

    /** Vertical quad from (x0,z0) to (x1,z1), with outward normal (nx,0,nz). */
    public void wallQuad(float x0, float y0, float z0, float x1, float y0b, float z1,
                         float height, float nx, float ny, float nz,
                         float r, float g, float b, int tile, float uScale) {
        float len = (float) Math.hypot(x1 - x0, z1 - z0);
        float u = len * uScale;
        float v = height * uScale;
        int a = vertex(x0, y0, z0, nx, ny, nz, r, g, b, tile, 0, 0);
        int bb = vertex(x1, y0, z1, nx, ny, nz, r, g, b, tile, u, 0);
        int c = vertex(x1, y0 + height, z1, nx, ny, nz, r, g, b, tile, u, v);
        int d = vertex(x0, y0 + height, z0, nx, ny, nz, r, g, b, tile, 0, v);
        quad(a, bb, c, d);
    }

    /** Copies this builder's vertices into a compact array. */
    public float[] compactVertices() {
        return java.util.Arrays.copyOf(data, vertexCount * FLOATS_PER_VERTEX);
    }

    public short[] compactIndices() {
        return java.util.Arrays.copyOf(indices, indexCount);
    }
}

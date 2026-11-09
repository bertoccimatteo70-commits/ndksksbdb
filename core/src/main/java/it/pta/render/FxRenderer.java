package it.pta.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/**
 * Real-time light bloom cards, mission beacons and contact (blob) shadows.
 * Geometry is rebuilt each frame into dynamic meshes; tiny vertex counts.
 */
public final class FxRenderer implements Disposable {
    private static final int MAX_GLOW_QUADS = 700;
    private static final int MAX_SHADOW_QUADS = 260;

    private final ShaderProgram glowShader;
    private final ShaderProgram shadowShader;
    private final Mesh glowMesh;
    private final Mesh shadowMesh;

    private final float[] glowVerts = new float[MAX_GLOW_QUADS * 4 * 9];
    private final short[] glowIdx = new short[MAX_GLOW_QUADS * 6];
    private int glowQuads;

    private final float[] shadowVerts = new float[MAX_SHADOW_QUADS * 4 * 5];
    private final short[] shadowIdx = new short[MAX_SHADOW_QUADS * 6];
    private int shadowQuads;

    private final Vector3 up = new Vector3();
    private final Vector3 right = new Vector3();

    public FxRenderer() {
        glowShader = new ShaderProgram(Shaders.GLOW_VERT, Shaders.GLOW_FRAG);
        if (!glowShader.isCompiled()) {
            Gdx.app.error("PTA", "glow shader: " + glowShader.getLog());
        }
        shadowShader = new ShaderProgram(Shaders.SHADOW_VERT, Shaders.SHADOW_FRAG);
        if (!shadowShader.isCompiled()) {
            Gdx.app.error("PTA", "shadow shader: " + shadowShader.getLog());
        }
        glowMesh = new Mesh(false, MAX_GLOW_QUADS * 4, MAX_GLOW_QUADS * 6,
                new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
                new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, "a_color"),
                new VertexAttribute(VertexAttributes.Usage.Generic, 2, "a_corner"));
        shadowMesh = new Mesh(false, MAX_SHADOW_QUADS * 4, MAX_SHADOW_QUADS * 6,
                new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
                new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"));
        buildIndices(glowIdx, MAX_GLOW_QUADS);
        buildIndices(shadowIdx, MAX_SHADOW_QUADS);
    }

    private static void buildIndices(short[] arr, int quads) {
        for (int q = 0; q < quads; q++) {
            int v = q * 4;
            int i = q * 6;
            arr[i] = (short) v;
            arr[i + 1] = (short) (v + 1);
            arr[i + 2] = (short) (v + 2);
            arr[i + 3] = (short) v;
            arr[i + 4] = (short) (v + 2);
            arr[i + 5] = (short) (v + 3);
        }
    }

    public void begin() {
        glowQuads = 0;
        shadowQuads = 0;
    }

    /** Adds an additive glow card. size is the radius in metres. */
    public void addGlow(float x, float y, float z, float size, float r, float g, float b) {
        if (glowQuads >= MAX_GLOW_QUADS) {
            return;
        }
        int v = glowQuads * 4;
        int p = v * 9;
        putGlow(glowVerts, p, x, y, z, r, g, b, size, -1, -1);
        putGlow(glowVerts, p + 9, x, y, z, r, g, b, size, 1, -1);
        putGlow(glowVerts, p + 18, x, y, z, r, g, b, size, 1, 1);
        putGlow(glowVerts, p + 27, x, y, z, r, g, b, size, -1, 1);
        glowQuads++;
    }

    private static void putGlow(float[] a, int p, float x, float y, float z,
                                float r, float g, float b, float size, float cx, float cy) {
        a[p] = x;
        a[p + 1] = y;
        a[p + 2] = z;
        a[p + 3] = r;
        a[p + 4] = g;
        a[p + 5] = b;
        a[p + 6] = size;
        a[p + 7] = cx;
        a[p + 8] = cy;
    }

    /** Vertical light beam for mission markers and interaction points. */
    public void addBeacon(float x, float z, float ground, float r, float g, float b) {
        for (int i = 0; i < 7; i++) {
            float t = i / 6f;
            addGlow(x, ground + 1.2f + t * 9f, z, 1.6f - t * 0.7f,
                    r * (1.1f - t * 0.5f), g * (1.1f - t * 0.5f), b * (1.1f - t * 0.5f));
        }
    }

    public void addShadow(float x, float z, float radius, float y) {
        if (shadowQuads >= MAX_SHADOW_QUADS) {
            return;
        }
        int v = shadowQuads * 4;
        int p = v * 5;
        putShadow(shadowVerts, p, x - radius, y, z - radius, 0, 0);
        putShadow(shadowVerts, p + 5, x + radius, y, z - radius, 1, 0);
        putShadow(shadowVerts, p + 10, x + radius, y, z + radius, 1, 1);
        putShadow(shadowVerts, p + 15, x - radius, y, z + radius, 0, 1);
        shadowQuads++;
    }

    private static void putShadow(float[] a, int p, float x, float y, float z, float u, float v) {
        a[p] = x;
        a[p + 1] = y;
        a[p + 2] = z;
        a[p + 3] = u;
        a[p + 4] = v;
    }

    public void render(Camera camera, float alpha) {
        if (shadowQuads > 0) {
            shadowMesh.setVertices(shadowVerts, 0, shadowQuads * 4 * 5);
            shadowMesh.setIndices(shadowIdx, 0, shadowQuads * 6);
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            Gdx.gl.glDepthMask(false);
            shadowShader.begin();
            shadowShader.setUniformMatrix("u_projView", camera.combined);
            shadowShader.setUniformf("u_alpha", alpha);
            shadowMesh.render(shadowShader, GL20.GL_TRIANGLES);
            shadowShader.end();
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    public void renderGlows(Camera camera, float intensity) {
        if (glowQuads == 0) {
            return;
        }
        right.set(camera.direction).crs(camera.up).nor();
        up.set(camera.up).nor();
        glowMesh.setVertices(glowVerts, 0, glowQuads * 4 * 9);
        glowMesh.setIndices(glowIdx, 0, glowQuads * 6);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        Gdx.gl.glDepthMask(false);
        glowShader.begin();
        glowShader.setUniformMatrix("u_projView", camera.combined);
        glowShader.setUniformf("u_camRight", right);
        glowShader.setUniformf("u_camUp", up);
        glowMesh.render(glowShader, GL20.GL_TRIANGLES);
        glowShader.end();
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    @Override
    public void dispose() {
        glowShader.dispose();
        shadowShader.dispose();
        glowMesh.dispose();
        shadowMesh.dispose();
    }
}

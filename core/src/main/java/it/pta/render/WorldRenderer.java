package it.pta.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;
import it.pta.world.MapData;

/**
 * Frame pipeline: procedural sky, streamed chunk world, deferred haze, glows and a
 * resolution-scaled post pass. Each graphics preset changes the internal render
 * resolution and view distance here, not just a label in a menu.
 */
public final class WorldRenderer implements Disposable {
    public final Sun sun = new Sun();
    public final FxRenderer fx = new FxRenderer();
    public final ChunkManager chunks;

    private final MapData map;
    private final Texture atlas;
    private final ShaderProgram worldShader;
    private final ShaderProgram skyShader;
    private final ShaderProgram postShader;
    private final Mesh fullscreen;
    private final Mesh skyQuad;
    private final Matrix4 invProjView = new Matrix4();
    private final Matrix4 tmp = new Matrix4();

    private FrameBuffer fbo;
    private int screenW, screenH;
    private int sceneW, sceneH;
    private int targetHeight = 720;
    private boolean fxaa = true;
    public boolean directRender;
    private float time;
    private float rain;
    private float clouds = 0.35f;
    public float fogStart = 220f;
    public float fogEnd = 900f;
    public int lastTriangles;
    public int lastDrawnChunks;
    public float lastResolutionScale = 1f;

    public WorldRenderer(MapData map, int initialTargetHeight) {
        this.map = map;
        this.targetHeight = initialTargetHeight;
        this.atlas = Atlas.build();
        worldShader = new ShaderProgram(Shaders.WORLD_VERT, Shaders.WORLD_FRAG);
        if (!worldShader.isCompiled()) {
            Gdx.app.error("PTA", "world shader: " + worldShader.getLog());
        }
        skyShader = new ShaderProgram(Shaders.SKY_VERT, Shaders.SKY_FRAG);
        if (!skyShader.isCompiled()) {
            Gdx.app.error("PTA", "sky shader: " + skyShader.getLog());
        }
        postShader = new ShaderProgram(Shaders.POST_VERT, Shaders.POST_FRAG);
        if (!postShader.isCompiled()) {
            Gdx.app.error("PTA", "post shader: " + postShader.getLog());
        }
        chunks = new ChunkManager(map);

        fullscreen = new Mesh(true, 4, 6,
                new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"));
        fullscreen.setVertices(new float[]{-1, -1, 1, -1, 1, 1, -1, 1});
        fullscreen.setIndices(new short[]{0, 1, 2, 0, 2, 3});
        skyQuad = fullscreen;

        screenW = Gdx.graphics.getWidth();
        screenH = Gdx.graphics.getHeight();
        resize(screenW, screenH);
    }

    public ShaderProgram worldShader() {
        return worldShader;
    }

    public ChunkManager chunkManager() {
        return chunks;
    }

    public MapData map() {
        return map;
    }

    /** Sets internal render height and view distance for the current preset. */
    public void configure(int renderHeight, float viewDistance, boolean antialias) {
        this.targetHeight = renderHeight;
        this.fxaa = antialias;
        chunks.setViewDistance(viewDistance);
        fogStart = viewDistance * 0.42f;
        fogEnd = viewDistance * 0.98f;
        recreateFbo();
    }

    public void setWeather(float rain, float clouds) {
        this.rain = Math.max(0f, Math.min(1f, rain));
        this.clouds = Math.max(0f, Math.min(1f, clouds));
    }

    public void resize(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        screenW = w;
        screenH = h;
        recreateFbo();
    }

    private void recreateFbo() {
        float aspect = (float) screenW / (float) Math.max(1, screenH);
        int h = Math.min(screenH, targetHeight);
        int w = Math.max(2, Math.round(h * aspect));
        if (w == sceneW && h == sceneH && fbo != null) {
            return;
        }
        sceneW = w;
        sceneH = h;
        directRender = (h >= screenH);
        if (fbo != null) {
            fbo.dispose();
            fbo = null;
        }
        if (!directRender) {
            try {
                fbo = new FrameBuffer(Pixmap.Format.RGBA8888, sceneW, sceneH, true);
            } catch (Exception e) {
                Gdx.app.error("PTA", "framebuffer failed, rendering direct", e);
                directRender = true;
            }
        }
        lastResolutionScale = (float) sceneH / Math.max(1, screenH);
    }

    public void beginFrame(Camera camera, float delta) {
        time += delta;
        sun.advance(delta, 1f);
        GL20 gl = Gdx.gl;

        if (!directRender && fbo != null) {
            fbo.begin();
            gl.glViewport(0, 0, sceneW, sceneH);
        } else {
            gl.glViewport(0, 0, screenW, screenH);
        }
        gl.glDisable(GL20.GL_CULL_FACE);
        gl.glEnable(GL20.GL_DEPTH_TEST);
        gl.glDepthMask(true);
        gl.glClearColor(sun.fogColor.x, sun.fogColor.y, sun.fogColor.z, 1f);
        gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        drawSky(camera);

        worldShader.begin();
        worldShader.setUniformMatrix("u_projView", camera.combined);
        worldShader.setUniformf("u_camPos", camera.position);
        worldShader.setUniformf("u_sunDir", sun.direction);
        worldShader.setUniformf("u_sunColor", sun.color);
        worldShader.setUniformf("u_ambient", sun.ambient);
        worldShader.setUniformf("u_fogColor", sun.fogColor);
        worldShader.setUniformf("u_fogStart", fogStart);
        worldShader.setUniformf("u_fogEnd", fogEnd);
        worldShader.setUniformf("u_night", sun.night);
        worldShader.setUniformf("u_time", time);
        worldShader.setUniformf("u_tint", 1f, 1f, 1f, 1f);
        worldShader.setUniformi("u_tex", 0);
        atlas.bind(0);
        worldShader.end();

        fx.begin();
    }

    public void drawSky(Camera camera) {
        GL20 gl = Gdx.gl;
        gl.glDisable(GL20.GL_DEPTH_TEST);
        gl.glDepthMask(false);
        invProjView.set(camera.projection).mul(camera.view).inv();
        skyShader.begin();
        skyShader.setUniformMatrix("u_invProjView", invProjView);
        skyShader.setUniformf("u_camPos", camera.position);
        skyShader.setUniformf("u_zenith", sun.zenith);
        skyShader.setUniformf("u_horizon", sun.horizon);
        skyShader.setUniformf("u_fogColor", sun.fogColor);
        skyShader.setUniformf("u_sunDir", sun.direction);
        skyShader.setUniformf("u_sunColor", sun.color);
        skyShader.setUniformf("u_night", sun.night);
        skyShader.setUniformf("u_time", time);
        skyShader.setUniformf("u_cloud", clouds);
        skyQuad.render(skyShader, GL20.GL_TRIANGLES);
        skyShader.end();
        gl.glDepthMask(true);
        gl.glEnable(GL20.GL_DEPTH_TEST);
    }

    public void drawWorld(Camera camera) {
        worldShader.begin();
        worldShader.setUniformMatrix("u_projView", camera.combined);
        worldShader.setUniformf("u_camPos", camera.position);
        worldShader.setUniformf("u_sunDir", sun.direction);
        worldShader.setUniformf("u_sunColor", sun.color);
        worldShader.setUniformf("u_ambient", sun.ambient);
        worldShader.setUniformf("u_fogColor", sun.fogColor);
        worldShader.setUniformf("u_fogStart", fogStart);
        worldShader.setUniformf("u_fogEnd", fogEnd);
        worldShader.setUniformf("u_night", sun.night);
        worldShader.setUniformf("u_tint", 1f, 1f, 1f, 1f);
        worldShader.setUniformi("u_tex", 0);
        atlas.bind(0);
        chunks.draw(worldShader, camera);
        chunks.drawWater(worldShader, camera);
        worldShader.end();
        lastTriangles = chunks.triangles;
        lastDrawnChunks = chunks.drawnChunks;
    }

    /** Re-binds the world shader for entity meshes, preserving frame uniforms. */
    public void beginEntities(Camera camera) {
        worldShader.begin();
        worldShader.setUniformMatrix("u_projView", camera.combined);
        worldShader.setUniformf("u_camPos", camera.position);
        worldShader.setUniformf("u_sunDir", sun.direction);
        worldShader.setUniformf("u_sunColor", sun.color);
        worldShader.setUniformf("u_ambient", sun.ambient);
        worldShader.setUniformf("u_fogColor", sun.fogColor);
        worldShader.setUniformf("u_fogStart", fogStart);
        worldShader.setUniformf("u_fogEnd", fogEnd);
        worldShader.setUniformf("u_night", sun.night);
        worldShader.setUniformf("u_tint", 1f, 1f, 1f, 1f);
        worldShader.setUniformi("u_tex", 0);
        atlas.bind(0);
    }

    public void endEntities() {
        worldShader.end();
    }

    public void finishFrame(Camera camera) {
        fx.render(camera, 0.34f);
        fx.renderGlows(camera, 1f);

        GL20 gl = Gdx.gl;
        if (!directRender && fbo != null) {
            fbo.end();
            gl.glViewport(0, 0, screenW, screenH);
            gl.glDisable(GL20.GL_DEPTH_TEST);
            gl.glDisable(GL20.GL_BLEND);
            postShader.begin();
            fbo.getColorBufferTexture().bind(0);
            postShader.setUniformi("u_tex", 0);
            postShader.setUniformf("u_texel", 1f / sceneW, 1f / sceneH);
            postShader.setUniformf("u_aa", fxaa ? 1f : 0f);
            postShader.setUniformf("u_vignette", 0.42f);
            postShader.setUniformf("u_saturation", 1.05f);
            postShader.setUniformf("u_rain", rain);
            postShader.setUniformf("u_time", time);
            fullscreen.render(postShader, GL20.GL_TRIANGLES);
            postShader.end();
            gl.glEnable(GL20.GL_DEPTH_TEST);
        }
    }

    public float time() {
        return time;
    }

    public void addGlow(float x, float y, float z, float size, float r, float g, float b) {
        fx.addGlow(x, y, z, size, r, g, b);
    }

    public void addShadow(float x, float z, float radius, float y) {
        fx.addShadow(x, z, radius, y);
    }

    public void addBeacon(float x, float z, float ground, float r, float g, float b) {
        fx.addBeacon(x, z, ground, r, g, b);
    }

    public void dispose() {
        worldShader.dispose();
        skyShader.dispose();
        postShader.dispose();
        fullscreen.dispose();
        atlas.dispose();
        fx.dispose();
        chunks.dispose();
        if (fbo != null) {
            fbo.dispose();
        }
    }
}

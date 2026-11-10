package it.pta.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import it.pta.world.MapData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streams the province in 512 m chunks, generating meshes on worker threads and
 * uploading them on the GL thread. Only chunks inside the view distance exist on
 * the GPU, which keeps the game inside Android memory budgets.
 */
public final class ChunkManager implements Disposable {
    private static final VertexAttribute[] ATTRS = {
            new VertexAttribute(VertexAttributes.Usage.Position, 3, ShaderProgram.POSITION_ATTRIBUTE),
            new VertexAttribute(VertexAttributes.Usage.Normal, 3, ShaderProgram.NORMAL_ATTRIBUTE),
            new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, ShaderProgram.COLOR_ATTRIBUTE),
            new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, ShaderProgram.TEXCOORD_ATTRIBUTE + "0")
    };

    public static final class Chunk {
        public final int cx, cz;
        public int lod;
        public ChunkGeometry geometry;
        public Mesh[] parts;
        public Mesh water;
        public float centerX, centerY, centerZ, radius;
        public long lastUsed;

        Chunk(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    private final MapData map;
    private final Map<Long, Chunk> chunks = new HashMap<>();
    private final Set<Long> building = new HashSet<>();
    private final ConcurrentLinkedQueue<ChunkGeometry> finished = new ConcurrentLinkedQueue<>();
    private final ExecutorService pool;
    private final Matrix4 identity = new Matrix4();
    private final Vector3 tmp = new Vector3();

    private float viewDistance = 900f;
    private long frame;
    public int drawnChunks;
    public int triangles;
    public volatile int queued;

    public ChunkManager(MapData map) {
        this.map = map;
        pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "pta-chunk-builder");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    public void setViewDistance(float d) {
        this.viewDistance = Math.max(400f, d);
    }

    public float viewDistance() {
        return viewDistance;
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }

    public void update(Vector3 pos, float delta) {
        frame++;
        uploadFinished(2);
        int cx = map.cx(pos.x);
        int cz = map.cz(pos.z);
        int radiusChunks = (int) Math.ceil((viewDistance + 220f) / map.chunkSize);
        queued = building.size();
        for (int r = cz - radiusChunks; r <= cz + radiusChunks; r++) {
            for (int c = cx - radiusChunks; c <= cx + radiusChunks; c++) {
                // Distance to the chunk rectangle, not to its centre: a player
                // standing near a chunk border must not see a coarse facade on the
                // other side of that border just because the chunk centre is far.
                float minX = c * map.chunkSize, maxX = minX + map.chunkSize;
                float minZ = r * map.chunkSize, maxZ = minZ + map.chunkSize;
                float nearestX = Math.max(minX, Math.min(pos.x, maxX));
                float nearestZ = Math.max(minZ, Math.min(pos.z, maxZ));
                float dx = nearestX - pos.x, dz = nearestZ - pos.z;
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                if (dist > viewDistance + 180f) {
                    continue;
                }
                // Full facades follow the real visible edge; the middle band keeps
                // roofs/windows and the rest is a cheap box.
                int lod = dist < 240f ? 0 : (dist < 760f ? 1 : 2);
                long k = key(c, r);
                Chunk existing = chunks.get(k);
                if (existing != null) {
                    existing.lastUsed = frame;
                    if (existing.geometry != null && existing.parts != null
                            && existing.lod != lod && !building.contains(k)) {
                        // LOD changed: rebuild sharper when approaching, cheaper when leaving.
                        existing.lod = lod;
                        schedule(c, r, lod, k);
                    }
                    continue;
                }
                if (building.contains(k) || building.size() >= 5) {
                    continue;
                }
                schedule(c, r, lod, k);
            }
        }
        evict(pos);
    }

    private void schedule(int cx, int cz, int lod, long k) {
        building.add(k);
        final int fcx = cx, fcz = cz, flod = lod;
        pool.execute(() -> {
            try {
                ChunkGeometry g = ChunkBuilder.build(map, fcx, fcz, flod);
                finished.add(g);
            } catch (Throwable t) {
                Gdx.app.error("PTA", "chunk build failed " + fcx + "," + fcz, t);
            } finally {
                building.remove(k);
            }
        });
    }

    private void uploadFinished(int maxPerFrame) {
        int n = 0;
        ChunkGeometry g;
        while (n < maxPerFrame && (g = finished.poll()) != null) {
            long k = key(g.cx, g.cz);
            Chunk c = chunks.get(k);
            if (c == null) {
                c = new Chunk(g.cx, g.cz);
                chunks.put(k, c);
            }
            if (c.parts != null) {
                disposeMeshes(c);
            }
            c.geometry = g;
            c.lod = g.lod;
            c.centerX = g.centerX;
            c.centerY = g.centerY;
            c.centerZ = g.centerZ;
            c.radius = g.radius;
            c.lastUsed = frame;
            c.parts = new Mesh[g.parts.size()];
            for (int i = 0; i < g.parts.size(); i++) {
                MeshBuilder mb = g.parts.get(i);
                if (mb.isEmpty()) {
                    continue;
                }
                Mesh m = new Mesh(true, mb.vertexCount, mb.indexCount, ATTRS);
                m.setVertices(mb.compactVertices());
                m.setIndices(mb.compactIndices());
                c.parts[i] = m;
            }
            if (!g.water.isEmpty()) {
                Mesh m = new Mesh(true, g.water.vertexCount, g.water.indexCount, ATTRS);
                m.setVertices(g.water.compactVertices());
                m.setIndices(g.water.compactIndices());
                c.water = m;
            }
            n++;
        }
    }

    private void evict(Vector3 pos) {
        Iterator<Map.Entry<Long, Chunk>> it = chunks.entrySet().iterator();
        List<Chunk> drop = new ArrayList<>();
        while (it.hasNext()) {
            Chunk c = it.next().getValue();
            float dist = (float) Math.hypot(c.centerX - pos.x, c.centerZ - pos.z);
            boolean stale = dist > viewDistance + 420f;
            boolean old = frame - c.lastUsed > 90;
            if (stale) {
                drop.add(c);
            } else if (old && dist > viewDistance + 200f) {
                drop.add(c);
            }
        }
        for (Chunk c : drop) {
            disposeMeshes(c);
            chunks.remove(key(c.cx, c.cz));
            map.releaseChunk(c.cx, c.cz);
        }
    }

    private void disposeMeshes(Chunk c) {
        if (c.parts != null) {
            for (Mesh m : c.parts) {
                if (m != null) {
                    m.dispose();
                }
            }
            c.parts = null;
        }
        if (c.water != null) {
            c.water.dispose();
            c.water = null;
        }
    }

    public void draw(ShaderProgram shader, Camera camera) {
        shader.setUniformMatrix("u_model", identity);
        drawnChunks = 0;
        triangles = 0;
        for (Chunk c : chunks.values()) {
            if (c.parts == null) {
                continue;
            }
            if (!camera.frustum.sphereInFrustum(c.centerX, c.centerY, c.centerZ, c.radius)) {
                continue;
            }
            drawnChunks++;
            for (Mesh m : c.parts) {
                if (m != null) {
                    m.render(shader, GL20.GL_TRIANGLES);
                    triangles += m.getNumIndices() / 3;
                }
            }
        }
    }

    public void drawWater(ShaderProgram shader, Camera camera) {
        shader.setUniformMatrix("u_model", identity);
        for (Chunk c : chunks.values()) {
            if (c.water == null) {
                continue;
            }
            if (!camera.frustum.sphereInFrustum(c.centerX, c.centerY, c.centerZ, c.radius)) {
                continue;
            }
            c.water.render(shader, GL20.GL_TRIANGLES);
        }
    }

    /** Appends nearby lamp positions (x,y,z) to out for night glow rendering. */
    public void collectGlows(Vector3 pos, float maxDist, com.badlogic.gdx.utils.FloatArray out) {
        float md2 = maxDist * maxDist;
        for (Chunk c : chunks.values()) {
            ChunkGeometry g = c.geometry;
            if (g == null || g.glowCount == 0) {
                continue;
            }
            for (int i = 0; i < g.glowCount; i++) {
                float x = g.glows[i * 3];
                float y = g.glows[i * 3 + 1];
                float z = g.glows[i * 3 + 2];
                float dx = x - pos.x, dz = z - pos.z;
                if (dx * dx + dz * dz > md2) {
                    continue;
                }
                out.add(x);
                out.add(y);
                out.add(z);
            }
        }
    }

    public int loadedChunks() {
        return chunks.size();
    }

    public int vertexMemoryBytes() {
        int n = 0;
        for (Chunk c : chunks.values()) {
            if (c.geometry == null) {
                continue;
            }
            n += c.geometry.vertexCount() * MeshBuilder.FLOATS_PER_VERTEX * 4;
        }
        return n;
    }

    /** Blocks until all queued chunk builds and uploads are done (used by loading screens). */
    public void preloadAround(Vector3 pos, float radius, int maxWaitMillis) {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        while (System.currentTimeMillis() < deadline) {
            update(pos, 0.016f);
            uploadFinished(3);
            boolean ready = true;
            int cx = map.cx(pos.x), cz = map.cz(pos.z);
            int r = (int) Math.ceil(radius / map.chunkSize);
            for (int z = cz - r; z <= cz + r && ready; z++) {
                for (int c = cx - r; c <= cx + r; c++) {
                    float ccx = (c + 0.5f) * map.chunkSize;
                    float ccz = (z + 0.5f) * map.chunkSize;
                    if (Math.hypot(ccx - pos.x, ccz - pos.z) > radius) {
                        continue;
                    }
                    Chunk ch = chunks.get(key(c, z));
                    if (ch == null || ch.parts == null) {
                        ready = false;
                        break;
                    }
                }
            }
            if (ready && finished.isEmpty() && building.isEmpty()) {
                return;
            }
            try {
                Thread.sleep(16);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void dispose() {
        pool.shutdownNow();
        for (Chunk c : chunks.values()) {
            disposeMeshes(c);
        }
        chunks.clear();
        map.releaseAllChunks();
    }
}

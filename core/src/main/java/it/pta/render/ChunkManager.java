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
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streams the province in 512 m chunks, generating meshes on worker threads and
 * uploading them on the GL thread. Only chunks inside the view distance exist on
 * the GPU, which keeps the game inside Android memory budgets.
 *
 * <p>Threading contract: {@link #chunks} and the pending-request set are only touched
 * on the GL thread. The set of chunks currently being built is a concurrent set,
 * because a worker removes its key when it finishes while the GL thread reads it to
 * decide what to schedule and reports it as {@code queued}. The finished queue hands
 * CPU geometry to the GL thread; meshes are created and disposed there only.</p>
 *
 * <p>Chunk requests are ordered by distance minus a forward bonus, so the world in
 * front of the camera (and ahead of the player's movement, when known) is built
 * first: that is the predictive part of the streaming. LOD changes use a hysteresis
 * band so a player walking along a threshold does not rebuild the same chunk every
 * frame.</p>
 */
public final class ChunkManager implements Disposable {
    private static final VertexAttribute[] ATTRS = {
            new VertexAttribute(VertexAttributes.Usage.Position, 3, ShaderProgram.POSITION_ATTRIBUTE),
            new VertexAttribute(VertexAttributes.Usage.Normal, 3, ShaderProgram.NORMAL_ATTRIBUTE),
            new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, ShaderProgram.COLOR_ATTRIBUTE),
            new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, ShaderProgram.TEXCOORD_ATTRIBUTE + "0")
    };

    /** Bands of the distance-based LOD, metres. */
    private static final float LOD0_DIST = 240f;
    private static final float LOD1_DIST = 760f;
    /** Hysteresis around each band so LOD borders do not flip every frame. */
    private static final float LOD_HYSTERESIS = 45f;
    /** Hard cap on live chunk meshes, whatever the device. */
    private static final int MAX_LIVE_CHUNKS = 260;
    /** Hard cap on CPU vertex bytes retained for loaded chunks. */
    private static final long MAX_VERTEX_BYTES = 96L * 1024 * 1024;

    public static final class Chunk {
        public final int cx, cz;
        public int lod;
        public Mesh[] parts;
        public Mesh water;
        public float centerX, centerY, centerZ, radius;
        public long lastUsed;
        /** Compact glow positions (xyz triples) kept after the CPU geometry is freed. */
        public float[] glows = EMPTY_GLOWS;
        public int glowCount;
        /** GPU bytes this chunk occupies, retained after the CPU staging arrays are freed. */
        public int vertexBytes;
        public int indexBytes;

        Chunk(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }

        public int totalBytes() {
            return vertexBytes + indexBytes;
        }
    }

    private static final float[] EMPTY_GLOWS = new float[0];

    private final MapData map;
    private final Map<Long, Chunk> chunks = new HashMap<>();
    private final Set<Long> building = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<ChunkGeometry> finished = new ConcurrentLinkedQueue<>();
    private final ExecutorService pool;
    private final Matrix4 identity = new Matrix4();
    private final AtomicInteger buildsStarted = new AtomicInteger();
    private final AtomicInteger buildsFailed = new AtomicInteger();

    private float viewDistance = 900f;
    private long frame;
    private float focusX, focusZ;
    private boolean hasFocus;
    private float lastX, lastZ;
    private boolean hasLast;
    public int drawnChunks;
    public int triangles;
    public volatile int queued;
    /** Chunks discarded because they left the streaming radius or the memory budget. */
    public int evicted;
    public int maxLiveChunks = MAX_LIVE_CHUNKS;
    public long maxVertexBytes = MAX_VERTEX_BYTES;

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

    /**
     * Direction the player is looking or moving in, used to prioritise and to reach a
     * little further ahead of the camera. Values are normalised here.
     */
    public void setFocus(float dx, float dz) {
        float len = (float) Math.hypot(dx, dz);
        if (len < 1e-4f) {
            hasFocus = false;
            return;
        }
        focusX = dx / len;
        focusZ = dz / len;
        hasFocus = true;
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }

    private static final class Request {
        final int cx, cz, lod;
        final long key;
        final float distance;
        final float score;

        Request(int cx, int cz, int lod, long key, float distance, float score) {
            this.cx = cx;
            this.cz = cz;
            this.lod = lod;
            this.key = key;
            this.distance = distance;
            this.score = score;
        }
    }

    public void update(Vector3 pos, float delta) {
        frame++;
        uploadFinished(2);
        // Movement-derived focus when the caller did not supply one: the streaming
        // then follows the player's travel direction instead of only the camera.
        if (!hasFocus && hasLast) {
            float vx = pos.x - lastX;
            float vz = pos.z - lastZ;
            if (vx * vx + vz * vz > 0.04f) {
                setFocus(vx, vz);
            }
        }
        lastX = pos.x;
        lastZ = pos.z;
        hasLast = true;

        int cx = map.cx(pos.x);
        int cz = map.cz(pos.z);
        int radiusChunks = (int) Math.ceil((viewDistance + 220f) / map.chunkSize);
        ArrayList<Request> requests = new ArrayList<>();
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
                float forward = 0f;
                if (hasFocus) {
                    float len = (float) Math.sqrt(dx * dx + dz * dz);
                    if (len > 1f) {
                        forward = (dx / len) * focusX + (dz / len) * focusZ;
                    }
                }
                // The cone in front of the player is loaded earlier than the sides.
                float reach = viewDistance + 180f + Math.max(0f, forward) * 320f;
                if (dist > reach) {
                    continue;
                }
                long k = key(c, r);
                Chunk existing = chunks.get(k);
                int lod = lodFor(dist, existing);
                if (existing != null) {
                    existing.lastUsed = frame;
                    if (existing.parts != null && existing.lod != lod && !building.contains(k)) {
                        float score = dist - Math.max(0f, forward) * 260f;
                        requests.add(new Request(c, r, lod, k, dist, score));
                    }
                    continue;
                }
                if (!building.contains(k)) {
                    float score = dist - Math.max(0f, forward) * 260f;
                    requests.add(new Request(c, r, lod, k, dist, score));
                }
            }
        }
        // Nearest-in-view first. The old loop scheduled the far corner of the window
        // first, which could starve the chunk under the player for a minute.
        requests.sort(Comparator.comparingDouble(a -> a.score));
        for (Request q : requests) {
            if (building.size() >= 5) {
                break;
            }
            if (building.contains(q.key)) {
                continue;
            }
            Chunk old = chunks.get(q.key);
            if (old != null && old.lod == q.lod && old.parts != null) {
                continue;
            }
            schedule(q.cx, q.cz, q.lod, q.key);
        }
        queued = building.size();
        evict(pos);
    }

    /** LOD for a chunk that is not loaded yet. Pure, so it is unit tested. */
    public static int lodForDistance(float dist, int currentLod) {
        return lodFor(dist, null, currentLod);
    }

    /** LOD with a hysteresis band per current level, to avoid rebuild pop-corner. */
    private static int lodFor(float dist, Chunk current) {
        return lodFor(dist, current, current == null ? 0 : current.lod);
    }

    private static int lodFor(float dist, Chunk current, int currentLod) {
        if (current != null) {
            currentLod = current.lod;
        }
        if (current == null && currentLod < 0) {
            return dist < LOD0_DIST ? 0 : (dist < LOD1_DIST ? 1 : 2);
        }
        switch (currentLod) {
            case 0:
                return dist > LOD0_DIST + LOD_HYSTERESIS ? (dist < LOD1_DIST ? 1 : 2) : 0;
            case 1:
                if (dist < LOD0_DIST - LOD_HYSTERESIS) {
                    return 0;
                }
                return dist > LOD1_DIST + LOD_HYSTERESIS ? 2 : 1;
            default:
                return dist > LOD1_DIST - LOD_HYSTERESIS ? 2 : (dist < LOD0_DIST ? 0 : 1);
        }
    }

    private void schedule(int cx, int cz, int lod, long k) {
        building.add(k);
        buildsStarted.incrementAndGet();
        final int fcx = cx, fcz = cz, flod = lod;
        pool.execute(() -> {
            try {
                ChunkGeometry g = ChunkBuilder.build(map, fcx, fcz, flod);
                finished.add(g);
            } catch (Throwable t) {
                buildsFailed.incrementAndGet();
                Gdx.app.error("PTA", "chunk build failed " + fcx + "," + fcz, t);
            } finally {
                // Concurrent set: the GL thread may be reading it right now.
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
            g.finish();
            c.lod = g.lod;
            c.centerX = g.centerX;
            c.centerY = g.centerY;
            c.centerZ = g.centerZ;
            c.radius = g.radius;
            c.lastUsed = frame;
            c.vertexBytes = g.vertexBytes;
            c.indexBytes = g.indexBytes;
            c.parts = new Mesh[g.parts.size()];
            try {
                for (int i = 0; i < g.parts.size(); i++) {
                    MeshBuilder mb = g.parts.get(i);
                    if (mb.isEmpty()) {
                        continue;
                    }
                    // Upload straight from the builder's array: no second full copy of
                    // the vertices is created just to hand it to the GPU.
                    Mesh m = new Mesh(true, mb.vertexCount, mb.indexCount, ATTRS);
                    m.setVertices(mb.data, 0, mb.vertexCount * MeshBuilder.FLOATS_PER_VERTEX);
                    m.setIndices(mb.indices, 0, mb.indexCount);
                    c.parts[i] = m;
                }
                if (!g.water.isEmpty()) {
                    Mesh m = new Mesh(true, g.water.vertexCount, g.water.indexCount, ATTRS);
                    m.setVertices(g.water.data, 0, g.water.vertexCount * MeshBuilder.FLOATS_PER_VERTEX);
                    m.setIndices(g.water.indices, 0, g.water.indexCount);
                    c.water = m;
                }
            } catch (Throwable t) {
                // A failed GPU allocation must not take the whole game down: drop this
                // chunk, free what was created and let the streamer retry it later.
                buildsFailed.incrementAndGet();
                Gdx.app.error("PTA", "chunk upload failed " + g.cx + "," + g.cz, t);
                disposeMeshes(c);
                c.vertexBytes = 0;
                c.indexBytes = 0;
                c.glows = EMPTY_GLOWS;
                c.glowCount = 0;
                chunks.remove(k);
                g.releaseCpu();
                continue;
            }
            // The GPU owns the geometry now: release the Java staging arrays so a
            // resident chunk no longer costs 48 bytes per vertex in the heap.
            c.glows = g.releaseCpu();
            c.glowCount = g.glowCount;
            n++;
        }
    }

    private void evict(Vector3 pos) {
        ArrayList<Chunk> live = new ArrayList<>();
        ArrayList<Chunk> stale = new ArrayList<>();
        long bytes = 0;
        // Removal happens after the iteration: dropping a chunk inside the loop would
        // modify the map being iterated and throw ConcurrentModificationException on
        // the GL thread (caught on the emulator as a fatal render failure).
        for (Chunk c : chunks.values()) {
            float dist = (float) Math.hypot(c.centerX - pos.x, c.centerZ - pos.z);
            boolean out = dist > viewDistance + 420f;
            boolean old = frame - c.lastUsed > 90;
            if (out || (old && dist > viewDistance + 200f)) {
                stale.add(c);
                continue;
            }
            live.add(c);
            bytes += c.totalBytes();
        }
        for (Chunk c : stale) {
            dropChunk(c);
        }
        // The byte budget is a hard limit, not a hint: the farthest chunks go first,
        // but the 3x3 around the player is never touched so the ground under the
        // camera cannot disappear while the budget is being enforced.
        if (live.size() <= maxLiveChunks && bytes <= maxVertexBytes) {
            return;
        }
        live.sort((a, b) -> Float.compare(
                (float) Math.hypot(a.centerX - pos.x, a.centerZ - pos.z),
                (float) Math.hypot(b.centerX - pos.x, b.centerZ - pos.z)));
        for (int i = live.size() - 1; i >= 9
                && (live.size() > maxLiveChunks || bytes > maxVertexBytes); i--) {
            Chunk c = live.remove(i);
            bytes -= c.totalBytes();
            dropChunk(c);
        }
        if (live.size() > maxLiveChunks || bytes > maxVertexBytes) {
            // Below nine chunks the budget is impossible; keep them anyway, the
            // camera needs something to stand on, and log the impossible state.
            Gdx.app.log("PTA", "chunk budget exceeded with " + live.size()
                    + " chunks and " + (bytes / 1048576) + "MB resident");
        }
    }

    private void dropChunk(Chunk c) {
        disposeMeshes(c);
        chunks.remove(key(c.cx, c.cz));
        c.vertexBytes = 0;
        c.indexBytes = 0;
        c.glows = EMPTY_GLOWS;
        c.glowCount = 0;
        map.releaseChunk(c.cx, c.cz);
        evicted++;
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
            if (c.glowCount == 0 || c.glows.length < c.glowCount * 3) {
                continue;
            }
            for (int i = 0; i < c.glowCount; i++) {
                float x = c.glows[i * 3];
                float y = c.glows[i * 3 + 1];
                float z = c.glows[i * 3 + 2];
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

    /**
     * Real loading progress for the loading screen: the fraction of the chunks inside
     * {@code radius} whose meshes are already resident. Only counts what the streaming
     * layer actually has, so the number is never invented.
     */
    public float progressAround(Vector3 pos, float radius) {
        int total = 0;
        int ready = 0;
        int cx = map.cx(pos.x), cz = map.cz(pos.z);
        int r = Math.max(1, (int) Math.ceil(radius / map.chunkSize));
        for (int z = cz - r; z <= cz + r; z++) {
            for (int c = cx - r; c <= cx + r; c++) {
                float ccx = (c + 0.5f) * map.chunkSize;
                float ccz = (z + 0.5f) * map.chunkSize;
                if (Math.hypot(ccx - pos.x, ccz - pos.z) > radius) {
                    continue;
                }
                total++;
                Chunk ch = chunks.get(key(c, z));
                if (ch != null && ch.parts != null) {
                    ready++;
                }
            }
        }
        return total == 0 ? 1f : ready / (float) total;
    }

    public int chunksAround(Vector3 pos, float radius) {
        int total = 0;
        int cx = map.cx(pos.x), cz = map.cz(pos.z);
        int r = Math.max(1, (int) Math.ceil(radius / map.chunkSize));
        for (int z = cz - r; z <= cz + r; z++) {
            for (int c = cx - r; c <= cx + r; c++) {
                float ccx = (c + 0.5f) * map.chunkSize;
                float ccz = (z + 0.5f) * map.chunkSize;
                if (Math.hypot(ccx - pos.x, ccz - pos.z) <= radius) {
                    total++;
                }
            }
        }
        return total;
    }

    /**
     * Memory pressure response: drops the farthest chunks down to half the live budget
     * and releases the CPU geometry of everything that leaves. Called by the memory
     * watchdog, never on the worker threads.
     */
    public int trim(Vector3 pos, int keepChunks) {
        int keep = Math.max(9, Math.min(maxLiveChunks, keepChunks));
        if (chunks.size() <= keep) {
            return 0;
        }
        ArrayList<Chunk> live = new ArrayList<>(chunks.values());
        live.sort((a, b) -> Float.compare(
                (float) Math.hypot(b.centerX - pos.x, b.centerZ - pos.z),
                (float) Math.hypot(a.centerX - pos.x, a.centerZ - pos.z)));
        int removed = 0;
        for (int i = 0; i < live.size() - keep; i++) {
            Chunk c = live.get(i);
            dropChunk(c);
            removed++;
        }
        return removed;
    }

    /**
     * Resident GPU vertex bytes. The CPU staging arrays are not counted because they
     * no longer exist after the upload; the value is what the device really holds.
     */
    public int vertexMemoryBytes() {
        int n = 0;
        for (Chunk c : chunks.values()) {
            n += c.vertexBytes;
        }
        return n;
    }

    /** Sets the hard resident-geometry budget (vertex + index bytes). */
    public void setMemoryBudget(long bytes) {
        maxVertexBytes = Math.max(12L * 1024 * 1024, Math.min(bytes, 96L * 1024 * 1024));
    }

    public int buildsStarted() {
        return buildsStarted.get();
    }

    public int buildsFailed() {
        return buildsFailed.get();
    }

    /** One-line diagnostics for the log and the performance report. */
    public String stats() {
        return "chunks=" + chunks.size()
                + " queued=" + building.size()
                + " drawn=" + drawnChunks
                + " vram=" + (vertexMemoryBytes() / (1024 * 1024)) + "MB"
                + " built=" + buildsStarted.get()
                + " evicted=" + evicted
                + " failed=" + buildsFailed.get();
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
            c.vertexBytes = 0;
            c.indexBytes = 0;
            c.glows = EMPTY_GLOWS;
            c.glowCount = 0;
        }
        chunks.clear();
        finished.clear();
        building.clear();
        map.releaseAllChunks();
    }
}

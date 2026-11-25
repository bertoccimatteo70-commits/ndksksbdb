package it.pta.render;

import java.util.ArrayDeque;

/**
 * Recycles chunk geometry buffers.
 *
 * <p>Building a chunk used to allocate fresh float arrays (up to 3 MB each) and throw
 * them away after the GPU upload. On a 3 GB phone that is tens of megabytes of
 * large-object allocations per second while the player moves, which is what kept the
 * ART heap pinned at its growth limit and made a 786 KB allocation fail even though
 * almost nothing was live. Buffers are now reused: steady-state streaming allocates
 * no vertex arrays at all.</p>
 *
 * <p>The pool is bounded: it never retains more than {@link #MAX_BYTES} of buffers,
 * and the memory watchdog can drop it entirely under pressure.</p>
 */
public final class MeshBuilderPool {
    private static final long MAX_BYTES = 24L * 1024 * 1024;
    private static final int MAX_BUILDERS = 12;

    private static final ArrayDeque<MeshBuilder> FREE = new ArrayDeque<>();
    private static long heldBytes;

    private MeshBuilderPool() {
    }

    /** A reset builder, from the pool when possible. */
    public static synchronized MeshBuilder acquire() {
        MeshBuilder b = FREE.pollFirst();
        if (b == null) {
            return new MeshBuilder();
        }
        heldBytes -= b.capacityBytes();
        b.reset();
        return b;
    }

    /** Returns a builder whose arrays can be reused by the next chunk. */
    public static synchronized void recycle(MeshBuilder b) {
        if (b == null) {
            return;
        }
        b.reset();
        long cap = b.capacityBytes();
        if (cap > MAX_BYTES || heldBytes + cap > MAX_BYTES || FREE.size() >= MAX_BUILDERS) {
            b.release();
            return;
        }
        heldBytes += cap;
        FREE.addLast(b);
    }

    /** Drops every retained buffer (memory pressure or shutdown). */
    public static synchronized void clear() {
        for (MeshBuilder b : FREE) {
            b.release();
        }
        FREE.clear();
        heldBytes = 0;
    }

    public static synchronized long heldBytes() {
        return heldBytes;
    }

    public static synchronized int pooled() {
        return FREE.size();
    }
}

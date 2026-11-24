package it.pta.game;

/**
 * Process memory watchdog.
 *
 * <p>The game runs on phones with 3 GB of RAM where the Java heap is typically a few
 * hundred MB and the ART large-object space holds the chunk vertex arrays. This class
 * samples the heap against a budget derived from the maximum heap, adds the native
 * allocation (Android only, read through reflection so the JVM tests do not need the
 * platform classes) and reports a pressure level the game can react to.</p>
 *
 * <p>It never frees anything itself: it only tells the caller how bad it is, so the
 * policy (lower the preset, trim the chunks, drop caches) stays in one place.</p>
 */
public final class MemoryBudget {
    public enum Pressure {OK, HIGH, CRITICAL}

    /** Fraction of the maximum heap the game may use before reacting. */
    private static final float SOFT_LIMIT = 0.72f;
    private static final float HARD_LIMIT = 0.88f;

    public final long budgetBytes;
    public float javaUsedMb;
    public float heapMaxMb;
    public float nativeUsedMb = -1f;
    public float chunkVertexMb;
    public int degradations;
    public Pressure pressure = Pressure.OK;

    private float timer;

    public MemoryBudget() {
        long max = Runtime.getRuntime().maxMemory();
        budgetBytes = (long) (max * SOFT_LIMIT);
        heapMaxMb = max / 1048576f;
    }

    /** Samples at most every 1.5 s; cheap enough to call once per frame. */
    public Pressure sample(float delta) {
        timer += delta;
        if (timer < 1.5f) {
            return pressure;
        }
        timer = 0f;
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        javaUsedMb = used / 1048576f;
        heapMaxMb = rt.maxMemory() / 1048576f;
        nativeUsedMb = nativeHeapMb();
        float ratio = used / (float) Math.max(1L, budgetBytes);
        pressure = ratio >= HARD_LIMIT ? Pressure.CRITICAL
                : ratio >= SOFT_LIMIT ? Pressure.HIGH : Pressure.OK;
        return pressure;
    }

    public void countDegradation() {
        degradations++;
    }

    /**
     * Native allocation of the process. Reflection keeps this class usable in the JVM
     * tests and on desktop, where {@code android.os.Debug} does not exist.
     */
    private static float nativeHeapMb() {
        try {
            Class<?> debug = Class.forName("android.os.Debug");
            Object value = debug.getMethod("getNativeHeapAllocatedSize").invoke(null);
            return ((Number) value).longValue() / 1048576f;
        } catch (Throwable ignored) {
            return -1f;
        }
    }

    public String summary() {
        return "heap " + (int) javaUsedMb + "/" + (int) heapMaxMb + "MB"
                + " budget " + (int) (budgetBytes / 1048576f) + "MB"
                + " native " + (nativeUsedMb < 0 ? "n/d" : (int) nativeUsedMb + "MB")
                + " chunkVerts " + (int) chunkVertexMb + "MB"
                + " pressure " + pressure
                + " degradations " + degradations;
    }
}

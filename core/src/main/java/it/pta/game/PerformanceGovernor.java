package it.pta.game;

/**
 * Keeps the frame budget on devices that cannot hold the selected preset.
 *
 * <p>The presets are authored for a mid-range phone; a weaker GPU (or a 1080p
 * screen with a software renderer) can sit at 5-10 fps while the world is still
 * perfectly streamed. Instead of hiding that behind a warning dialog, this
 * controller lowers the internal render resolution, the view distance and the
 * ambient traffic step by step, and slowly restores them when the device has
 * headroom again. It is pure logic, so the behaviour is unit tested without a GL
 * context.</p>
 */
public final class PerformanceGovernor {
    public static final int MAX_STEP = 3;

    private static final float[] RESOLUTION = {1f, 0.85f, 0.72f, 0.62f};
    private static final float[] VIEW = {1f, 0.90f, 0.80f, 0.72f};
    private static final float[] TRAFFIC = {1f, 0.85f, 0.70f, 0.55f};

    /** Seconds under the low threshold before one step is taken. */
    private static final float LOW_SECONDS = 5f;
    /** Seconds above the recovery threshold before one step is given back. */
    private static final float RECOVER_SECONDS = 30f;

    private int step;
    private float lowTime;
    private float highTime;

    /**
     * Feeds one measurement window.
     *
     * @return +1 when quality was lowered, -1 when it was raised, 0 otherwise.
     */
    public int update(float delta, float fps, int targetFps) {
        if (delta <= 0f || fps <= 0f) {
            return 0;
        }
        float target = targetFps <= 0 ? 60f : targetFps;
        // Starts adapting below 62% of the target (18.6 fps at a 30 fps cap,
        // 37 at an uncapped 60). Between the low mark and 92% of the target the
        // device sits in a dead zone and is left alone.
        float lowMark = target * 0.62f;
        if (fps < lowMark) {
            lowTime += delta;
            highTime = 0f;
        } else if (fps >= target * 0.92f) {
            highTime += delta;
            lowTime = 0f;
        } else {
            lowTime = 0f;
            highTime = 0f;
        }
        if (lowTime >= LOW_SECONDS && step < MAX_STEP) {
            lowTime = 0f;
            step++;
            return 1;
        }
        if (highTime >= RECOVER_SECONDS && step > 0) {
            highTime = 0f;
            step--;
            return -1;
        }
        return 0;
    }

    public int step() {
        return step;
    }

    public float resolutionScale() {
        return RESOLUTION[step];
    }

    public float viewScale() {
        return VIEW[step];
    }

    public float trafficScale() {
        return TRAFFIC[step];
    }

    public void reset() {
        step = 0;
        lowTime = 0f;
        highTime = 0f;
    }
}

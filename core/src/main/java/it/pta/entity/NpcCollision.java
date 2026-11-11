package it.pta.entity;

/**
 * Circle separation shared by the player, pedestrians and vehicles.
 *
 * <p>Kept free of libGDX and of GL resources so it can be unit tested on the JVM:
 * positions are plain x/z pairs in a float array, which is also what MapData
 * collision resolution uses.</p>
 *
 * <p>Resolution is split by mass ({@code shareA} + share B = 1). A pedestrian
 * pushed by the player gets most of the correction, a pedestrian pushed by another
 * pedestrian gets half, and a car barely moves. Movements are clamped to a maximum
 * per call so a spawn overlap cannot teleport a character across a street.</p>
 */
public final class NpcCollision {
    /** Player capsule radius, metres. */
    public static final float PLAYER_RADIUS = 0.34f;
    /** Pedestrian body radius, metres. */
    public static final float PED_RADIUS = 0.30f;

    private NpcCollision() {
    }

    /**
     * Separates two circles.
     *
     * @param a       x,y,z of the first body; x and z are updated in place
     * @param b       x,y,z of the second body; x and z are updated in place
     * @param minDist required distance between the centres
     * @param shareA  fraction of the correction applied to {@code a} (0..1)
     * @param maxMove upper bound on the movement of each body in this call, metres
     * @return penetration that was resolved (0 when the circles were apart)
     */
    public static float separate(float[] a, float[] b, float minDist, float shareA,
                                 float maxMove) {
        float dx = b[0] - a[0];
        float dz = b[2] - a[2];
        float d2 = dx * dx + dz * dz;
        if (d2 >= minDist * minDist) {
            return 0f;
        }
        float d = (float) Math.sqrt(d2);
        float nx;
        float nz;
        if (d < 1e-5f) {
            // Perfectly stacked bodies: push along a deterministic axis so the
            // result is stable frame to frame instead of random jitter.
            nx = 1f;
            nz = 0f;
            d = 0f;
        } else {
            nx = dx / d;
            nz = dz / d;
        }
        float overlap = minDist - d;
        float share = Math.max(0f, Math.min(1f, shareA));
        float moveA = Math.min(maxMove, overlap * share);
        float moveB = Math.min(maxMove, overlap * (1f - share));
        a[0] -= nx * moveA;
        a[2] -= nz * moveA;
        b[0] += nx * moveB;
        b[2] += nz * moveB;
        return overlap;
    }

    /** True when the two circles overlap. */
    public static boolean overlaps(float ax, float az, float bx, float bz, float minDist) {
        float dx = bx - ax;
        float dz = bz - az;
        return dx * dx + dz * dz < minDist * minDist;
    }

    /** Distance from a circle centre to a rectangle, 0 when inside. */
    public static float distanceToRect(float x, float z, float minX, float minZ,
                                       float maxX, float maxZ) {
        float dx = Math.max(Math.max(minX - x, 0f), x - maxX);
        float dz = Math.max(Math.max(minZ - z, 0f), z - maxZ);
        return (float) Math.hypot(dx, dz);
    }
}

package it.pta.world;

/**
 * Local tangent-plane projection shared with the map baker (tools/map/build_map.py).
 *
 * World axes: +X east, +Z south, +Y up, distances in metres. Origin is Pistoia's
 * Piazza del Duomo, so coordinates stay small near the city centre.
 */
public final class Geo {
    public static final double LAT0 = 43.93330;
    public static final double LON0 = 10.91670;
    public static final double M_PER_DEG_LAT = 110574.0;
    public static final double M_PER_DEG_LON = 111320.0 * Math.cos(Math.toRadians(LAT0));

    private Geo() {
    }

    public static float x(double lon) {
        return (float) ((lon - LON0) * M_PER_DEG_LON);
    }

    public static float z(double lat) {
        return (float) (-(lat - LAT0) * M_PER_DEG_LAT);
    }

    public static double lon(float x) {
        return LON0 + x / M_PER_DEG_LON;
    }

    public static double lat(float z) {
        return LAT0 - z / M_PER_DEG_LAT;
    }

    /** Great-circle-ish distance in metres between two world points. */
    public static float dist(float x1, float z1, float x2, float z2) {
        float dx = x2 - x1;
        float dz = z2 - z1;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    public static float dist2(float x1, float z1, float x2, float z2) {
        float dx = x2 - x1;
        float dz = z2 - z1;
        return dx * dx + dz * dz;
    }
}

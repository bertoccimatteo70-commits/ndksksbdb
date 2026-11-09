package it.pta.render;

/** Colour palettes shared with the map baker (indices are baked into the map file). */
public final class Palette {
    /** Tuscan plaster facades. Order must match FACADE_COLORS in build_map.py. */
    private static final int[] FACADE = {
            0xD8BB8A, 0xC9A470, 0xE4D1AD, 0xC58D68, 0xEBE3D0, 0xCBC5B5, 0xAE765B,
            0xDEC598, 0xBDAA88, 0xE7DBC4, 0xA08F79, 0xD4B283, 0xF0ECE1, 0xBA9C76
    };
    /** Terracotta and stone roofs. */
    private static final int[] ROOF = {
            0xA8543A, 0x964832, 0xB76242, 0x804233, 0x8C867E, 0x6C6862, 0x9C5C42, 0x787670
    };

    public static final float[] GROUND_RESIDENTIAL = {0.56f, 0.55f, 0.50f};
    public static final float[] GROUND_INDUSTRIAL = {0.47f, 0.47f, 0.45f};
    public static final float[] GROUND_FARMLAND = {0.63f, 0.58f, 0.35f};
    public static final float[] GROUND_GRASS = {0.42f, 0.57f, 0.30f};
    public static final float[] GROUND_FOREST = {0.29f, 0.42f, 0.24f};
    public static final float[] GROUND_PARK = {0.38f, 0.56f, 0.29f};
    public static final float[] GROUND_CEMETERY = {0.46f, 0.50f, 0.42f};
    public static final float[] GROUND_PARKING = {0.43f, 0.43f, 0.42f};
    public static final float[] GROUND_WATER = {0.14f, 0.24f, 0.30f};
    public static final float[] GROUND_RAIL = {0.40f, 0.38f, 0.36f};
    public static final float[] GROUND_CIVIC = {0.52f, 0.51f, 0.47f};
    public static final float[] GROUND_DEFAULT = {0.47f, 0.55f, 0.34f};
    public static final float[] GROUND_QUARRY = {0.55f, 0.52f, 0.46f};

    public static float[] groundColor(int kind) {
        switch (kind) {
            case 1: return GROUND_RESIDENTIAL;
            case 2: return GROUND_RESIDENTIAL;
            case 3: return GROUND_INDUSTRIAL;
            case 4: return GROUND_FARMLAND;
            case 5: return GROUND_GRASS;
            case 6: return GROUND_FOREST;
            case 7: return GROUND_PARK;
            case 8: return GROUND_PARK;
            case 9: return GROUND_CEMETERY;
            case 10: return GROUND_QUARRY;
            case 11: return GROUND_RAIL;
            case 12: return GROUND_PARKING;
            case 13: return GROUND_WATER;
            case 14: return GROUND_CIVIC;
            default: return GROUND_DEFAULT;
        }
    }

    public static float[] facade(int idx) {
        return unpack(FACADE[Math.floorMod(idx, FACADE.length)]);
    }

    public static float[] roof(int idx) {
        return unpack(ROOF[Math.floorMod(idx, ROOF.length)]);
    }

    private static float[] unpack(int rgb) {
        return new float[]{
                ((rgb >> 16) & 0xFF) / 255f,
                ((rgb >> 8) & 0xFF) / 255f,
                (rgb & 0xFF) / 255f
        };
    }

    private Palette() {
    }
}

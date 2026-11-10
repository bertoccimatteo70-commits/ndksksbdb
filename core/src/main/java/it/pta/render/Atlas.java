package it.pta.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;

/**
 * Procedurally generated 4x4 material atlas (no external art assets required).
 *
 * <p>The first nine tiles keep their original indices so vehicles, characters and
 * interiors continue to work. The second half adds the materials used by the city
 * overhaul: bare plaster, stone, brick, modern concrete, window glass, wooden
 * shutters and stone paving. Tiles are grayscale detail maps multiplied by the
 * per-vertex colour; window glass carries alpha 255 so the shader can add a warm
 * night emission without an extra texture.</p>
 */
public final class Atlas {
    public static final int ASHPALT = 0;
    public static final int FACADE = 1;
    public static final int ROOF = 2;
    public static final int GROUND = 3;
    public static final int PAINT = 4;
    public static final int CONCRETE = 5;
    public static final int METAL = 6;
    public static final int FOLIAGE = 7;
    public static final int GLASS = 8;

    // Added for the architectural overhaul. Indices 0..8 are deliberately stable.
    public static final int PLASTER = 9;
    public static final int STONE = 10;
    public static final int BRICK = 11;
    public static final int MODERN = 12;
    public static final int WINDOW = 13;
    public static final int SHUTTER = 14;
    public static final int PAVING = 15;

    public static final int COLS = 4;
    public static final int SIZE = 1024;
    public static final int CELL = SIZE / COLS;

    public static Texture build() {
        Pixmap pm = new Pixmap(SIZE, SIZE, Pixmap.Format.RGBA8888);
        pm.setColor(new Color(0, 0, 0, 0));
        pm.fill();

        tileAsphalt(pm, 0, 0);
        tileFacade(pm, 1, 0);
        tileRoof(pm, 2, 0);
        tileGround(pm, 3, 0);
        tilePaint(pm, 0, 1);
        tileConcrete(pm, 1, 1);
        tileMetal(pm, 2, 1);
        tileFoliage(pm, 3, 1);
        tileGlass(pm, 0, 2);
        tilePlaster(pm, 1, 2);
        tileStone(pm, 2, 2);
        tileBrick(pm, 3, 2);
        tileModern(pm, 0, 3);
        tileWindow(pm, 1, 3);
        tileShutter(pm, 2, 3);
        tilePaving(pm, 3, 3);

        // The atlas is sampled with fract() tiling. Mipmaps are not needed here,
        // and an incomplete mip chain would make every world surface sample black
        // on some GLES drivers, so use a simple complete base-level texture.
        Texture tex = new Texture(pm);
        tex.setFilter(TextureFilter.Linear, TextureFilter.Linear);
        tex.setWrap(TextureWrap.ClampToEdge, TextureWrap.ClampToEdge);
        pm.dispose();
        return tex;
    }

    private static void set(Pixmap pm, int cx, int cy, int x, int y, int v, int a) {
        if (x < 1 || y < 1 || x >= CELL - 1 || y >= CELL - 1) {
            return;
        }
        int r = clamp(v);
        int g = clamp(v);
        int b = clamp(v);
        pm.drawPixel(cx * CELL + x, cy * CELL + y,
                Color.rgba8888(r / 255f, g / 255f, b / 255f, a / 255f));
    }

    private static int clamp(int v) {
        return Math.min(255, Math.max(0, v));
    }

    private static void noiseFill(Pixmap pm, int cx, int cy, int base, int amp,
                                  long seed, int alpha) {
        java.util.Random rnd = new java.util.Random(seed);
        for (int y = 2; y < CELL - 2; y++) {
            for (int x = 2; x < CELL - 2; x++) {
                int v = base + rnd.nextInt(amp * 2 + 1) - amp;
                // Soft low-frequency variation plus fine aggregate.
                v += (int) (Math.sin(x * 0.037) * Math.cos(y * 0.029) * amp * 0.9);
                v += (int) (Math.sin((x + y) * 0.19) * amp * 0.25);
                set(pm, cx, cy, x, y, v, alpha);
            }
        }
    }

    private static void tileAsphalt(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 106, 13, 11, 0);
        java.util.Random rnd = new java.util.Random(7);
        // Aggregate flecks, tar seams and a few repaired patches.
        for (int i = 0; i < 850; i++) {
            int x = rnd.nextInt(CELL - 8) + 4;
            int y = rnd.nextInt(CELL - 8) + 4;
            int v = 74 + rnd.nextInt(54);
            set(pm, cx, cy, x, y, v, 0);
            if ((i & 3) == 0) {
                set(pm, cx, cy, x + 1, y, v - 18, 0);
            }
        }
        for (int i = 0; i < 5; i++) {
            int y = 18 + rnd.nextInt(CELL - 36);
            int x = 12 + rnd.nextInt(CELL / 2);
            int len = 26 + rnd.nextInt(70);
            for (int k = 0; k < len; k++) {
                set(pm, cx, cy, x + k, y + (int) (Math.sin(k * 0.17) * 3), 62, 0);
            }
        }
        for (int i = 0; i < 3; i++) {
            int x = 12 + rnd.nextInt(CELL - 60);
            int y = 12 + rnd.nextInt(CELL - 60);
            for (int yy = y; yy < Math.min(CELL - 3, y + 15); yy++) {
                for (int xx = x; xx < Math.min(CELL - 3, x + 25); xx++) {
                    set(pm, cx, cy, xx, yy, 90 + rnd.nextInt(18), 0);
                }
            }
        }
    }

    private static void tileFacade(Pixmap pm, int cx, int cy) {
        // Distance facade: stucco with real window/shutter silhouettes. Close
        // buildings use PLASTER plus geometric windows instead, so this is mainly
        // LOD1 and the cheaper fallback.
        noiseFill(pm, cx, cy, 198, 8, 23, 0);
        int cols = 3, rows = 2;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int ww = (int) (CELL * 0.16f);
                int wh = (int) (CELL * 0.27f);
                int x0 = (int) (CELL * (0.08f + c * 0.30f));
                int y0 = (int) (CELL * (0.09f + r * 0.46f));
                // stone surround
                for (int y = y0 - 5; y < y0 + wh + 5; y++) {
                    for (int x = x0 - 5; x < x0 + ww + 5; x++) {
                        set(pm, cx, cy, x, y, 219, 0);
                    }
                }
                // glass, with emissive alpha for night
                for (int y = y0; y < y0 + wh; y++) {
                    for (int x = x0; x < x0 + ww; x++) {
                        int shade = 44 + ((x * 3 + y * 2) % 17);
                        set(pm, cx, cy, x, y, shade, 255);
                    }
                }
                for (int y = y0; y < y0 + wh; y++) {
                    set(pm, cx, cy, x0 + ww / 2, y, 174, 0);
                }
                for (int x = x0 - 7; x < x0 + ww + 7; x++) {
                    set(pm, cx, cy, x, y0 + wh + 4, 231, 0);
                }
                // shutters on the left/right of the opening
                for (int side = 0; side < 2; side++) {
                    int sx = side == 0 ? x0 - 15 : x0 + ww + 7;
                    for (int y = y0; y < y0 + wh; y++) {
                        for (int x = sx; x < sx + 7; x++) {
                            set(pm, cx, cy, x, y,
                                    124 + ((x + y) % 9) * 3, 0);
                        }
                    }
                }
            }
        }
    }

    private static void tilePlaster(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 198, 7, 24, 0);
        java.util.Random rnd = new java.util.Random(221);
        // Weathering streaks and a faint stone plinth at the bottom.
        for (int i = 0; i < 22; i++) {
            int x = 10 + rnd.nextInt(CELL - 20);
            int y = 8 + rnd.nextInt(CELL / 2);
            int len = 14 + rnd.nextInt(72);
            for (int k = 0; k < len; k++) {
                set(pm, cx, cy, x + (k % 3 == 0 ? 1 : 0), y + k,
                        180 + rnd.nextInt(12), 0);
            }
        }
        for (int y = CELL - 22; y < CELL - 3; y++) {
            for (int x = 3; x < CELL - 3; x++) {
                set(pm, cx, cy, x, y, 151 + (x % 9), 0);
            }
        }
    }

    private static void tileStone(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 169, 8, 31, 0);
        int rowH = 28;
        for (int y = 4; y < CELL - 4; y++) {
            int row = y / rowH;
            int off = (row % 2) * 22;
            for (int x = 4; x < CELL - 4; x++) {
                if ((x + off) % 44 <= 2 || y % rowH <= 2) {
                    set(pm, cx, cy, x, y, 111, 0);
                } else if ((x * 7 + y * 3) % 29 == 0) {
                    set(pm, cx, cy, x, y, 190, 0);
                }
            }
        }
    }

    private static void tileBrick(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 157, 7, 37, 0);
        int rowH = 18, brickW = 38;
        for (int y = 4; y < CELL - 4; y++) {
            int row = y / rowH;
            int off = (row % 2) * (brickW / 2);
            for (int x = 4; x < CELL - 4; x++) {
                boolean mortar = (y % rowH <= 2) || ((x + off) % brickW <= 2);
                if (mortar) {
                    set(pm, cx, cy, x, y, 103, 0);
                } else {
                    int v = 146 + ((x * 5 + y * 3) % 27);
                    set(pm, cx, cy, x, y, v, 0);
                }
            }
        }
    }

    private static void tileModern(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 166, 6, 41, 0);
        for (int y = 4; y < CELL - 4; y++) {
            for (int x = 4; x < CELL - 4; x++) {
                if (x % 62 <= 2 || y % 50 <= 2) {
                    set(pm, cx, cy, x, y, 112, 0);
                }
            }
        }
        java.util.Random rnd = new java.util.Random(401);
        for (int i = 0; i < 9; i++) {
            int x = 8 + rnd.nextInt(CELL - 40);
            int y = 8 + rnd.nextInt(CELL - 40);
            for (int yy = y; yy < y + 18; yy++) {
                for (int xx = x; xx < x + 26; xx++) {
                    set(pm, cx, cy, xx, yy, 143 + rnd.nextInt(10), 0);
                }
            }
        }
    }

    private static void tileRoof(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 158, 9, 31, 0);
        // Barrel tiles: alternating rounded highlights and dark channels.
        int rowH = 24;
        for (int y = 4; y < CELL - 4; y++) {
            int row = y / rowH;
            int off = (row % 2) * 12;
            for (int x = 4; x < CELL - 4; x++) {
                int phase = (x + off) % 24;
                int v = 142 + (int) (Math.cos(phase * Math.PI / 24.0) * 28);
                if (y % rowH <= 2) {
                    v = 95;
                } else if (phase == 11) {
                    v = 113;
                }
                set(pm, cx, cy, x, y, v, 0);
            }
        }
    }

    private static void tileGround(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 171, 17, 43, 0);
        java.util.Random rnd = new java.util.Random(53);
        for (int i = 0; i < 1500; i++) {
            int x = rnd.nextInt(CELL - 8) + 4;
            int y = rnd.nextInt(CELL - 8) + 4;
            int v = 112 + rnd.nextInt(91);
            set(pm, cx, cy, x, y, v, 0);
            set(pm, cx, cy, x, Math.min(CELL - 4, y + 1), v - 27, 0);
            if ((i & 7) == 0) {
                set(pm, cx, cy, x + 1, y, v + 18, 0);
            }
        }
    }

    private static void tilePaint(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 231, 7, 61, 0);
        java.util.Random rnd = new java.util.Random(67);
        for (int i = 0; i < 240; i++) {
            int x = rnd.nextInt(CELL - 8) + 4;
            int y = rnd.nextInt(CELL - 8) + 4;
            set(pm, cx, cy, x, y, 185 + rnd.nextInt(28), 0);
        }
    }

    private static void tileConcrete(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 178, 8, 71, 0);
        int step = CELL / 4;
        for (int y = 3; y < CELL - 3; y += step) {
            for (int x = 3; x < CELL - 3; x++) {
                set(pm, cx, cy, x, y, 133, 0);
            }
        }
        for (int x = 3; x < CELL - 3; x += step * 2) {
            for (int y = 3; y < CELL - 3; y++) {
                set(pm, cx, cy, x, y, 140, 0);
            }
        }
        java.util.Random rnd = new java.util.Random(733);
        for (int i = 0; i < 4; i++) {
            int x = rnd.nextInt(CELL - 20);
            int y = rnd.nextInt(CELL - 20);
            for (int k = 0; k < 18; k++) {
                set(pm, cx, cy, x + k, y + (k % 4), 124, 0);
            }
        }
    }

    private static void tileMetal(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 94, 6, 83, 0);
        for (int x = 3; x < CELL - 3; x += 9) {
            for (int y = 3; y < CELL - 3; y++) {
                int v = 74 + ((x / 9) % 2) * 17;
                set(pm, cx, cy, x, y, v, 0);
                set(pm, cx, cy, x + 1, y, v + 13, 0);
            }
        }
    }

    private static void tileFoliage(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 148, 21, 97, 0);
        java.util.Random rnd = new java.util.Random(101);
        for (int i = 0; i < 2200; i++) {
            int x = rnd.nextInt(CELL - 8) + 4;
            int y = rnd.nextInt(CELL - 8) + 4;
            int v = 87 + rnd.nextInt(109);
            set(pm, cx, cy, x, y, v, 0);
            set(pm, cx, cy, x + 1, y, v - 22, 0);
            set(pm, cx, cy, x, y + 1, v - 37, 0);
        }
    }

    private static void tileGlass(Pixmap pm, int cx, int cy) {
        for (int y = 2; y < CELL - 2; y++) {
            for (int x = 2; x < CELL - 2; x++) {
                int v = 45 + (int) (Math.sin((x + y) * 0.018) * 14);
                if ((x * 5 + y * 3) % 131 == 0) {
                    v += 70;
                }
                set(pm, cx, cy, x, y, v, 0);
            }
        }
    }

    private static void tileWindow(Pixmap pm, int cx, int cy) {
        // A dark, slightly reflective pane with a pale frame and two mullions.
        for (int y = 2; y < CELL - 2; y++) {
            for (int x = 2; x < CELL - 2; x++) {
                int frame = 18;
                boolean border = x < frame || y < frame || x >= CELL - frame
                        || y >= CELL - frame;
                if (border) {
                    set(pm, cx, cy, x, y, 216, 0);
                } else {
                    // Mid-tone glass, not a black hole: the per-vertex cool tint
                    // turns this into a readable blue-grey reflection in daylight.
                    int v = 146 + (int) (Math.sin(x * 0.11 + y * 0.035) * 14);
                    if (Math.abs(x - CELL / 2) <= 2 || Math.abs(y - CELL / 2) <= 2) {
                        v = 190;
                    }
                    set(pm, cx, cy, x, y, v, 255);
                }
            }
        }
    }

    private static void tileShutter(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 137, 7, 113, 0);
        for (int y = 4; y < CELL - 4; y++) {
            for (int x = 4; x < CELL - 4; x++) {
                int v = 106 + ((x % 12) < 7 ? 24 : 0);
                if (y % 18 == 0) {
                    v = 78;
                }
                set(pm, cx, cy, x, y, v, 0);
            }
        }
    }

    private static void tilePaving(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 167, 9, 151, 0);
        int rowH = 37;
        for (int y = 4; y < CELL - 4; y++) {
            int row = y / rowH;
            int off = (row % 2) * 31;
            for (int x = 4; x < CELL - 4; x++) {
                if (y % rowH <= 2 || (x + off) % 62 <= 2) {
                    set(pm, cx, cy, x, y, 116, 0);
                } else if ((x * 3 + y * 7) % 43 == 0) {
                    set(pm, cx, cy, x, y, 205, 0);
                }
            }
        }
    }

    private Atlas() {
    }
}

package it.pta.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;

/**
 * Procedurally generated 3x3 material atlas (no external art assets required).
 *
 * Tiles are grayscale detail maps multiplied by per-vertex colour, except the
 * facade tile whose alpha channel marks windows that glow at night.
 * Tiles: 0 asphalt, 1 facade, 2 roof, 3 ground, 4 road paint, 5 concrete,
 *        6 metal, 7 foliage, 8 glass.
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
    public static final int SIZE = 512;
    public static final int CELL = SIZE / 3;

    public static Texture build() {
        Pixmap pm = new Pixmap(SIZE, SIZE, Pixmap.Format.RGBA8888);
        pm.setColor(new Color(0, 0, 0, 0));
        pm.fill();
        tileAsphalt(pm, 0, 0);
        tileFacade(pm, 1, 0);
        tileRoof(pm, 2, 0);
        tileGround(pm, 0, 1);
        tilePaint(pm, 1, 1);
        tileConcrete(pm, 2, 1);
        tileMetal(pm, 0, 2);
        tileFoliage(pm, 1, 2);
        tileGlass(pm, 2, 2);

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
        int r = Math.min(255, Math.max(0, v));
        int g = Math.min(255, Math.max(0, v));
        int b = Math.min(255, Math.max(0, v));
        pm.drawPixel(cx * CELL + x, cy * CELL + y, Color.rgba8888(r / 255f, g / 255f, b / 255f, a / 255f));
    }

    private static void noiseFill(Pixmap pm, int cx, int cy, int base, int amp, long seed, int alpha) {
        java.util.Random rnd = new java.util.Random(seed);
        for (int y = 2; y < CELL - 2; y++) {
            for (int x = 2; x < CELL - 2; x++) {
                int v = base + rnd.nextInt(amp * 2 + 1) - amp;
                // Soft low-frequency variation.
                v += (int) (Math.sin(x * 0.045) * Math.cos(y * 0.037) * amp);
                set(pm, cx, cy, x, y, v, alpha);
            }
        }
    }

    private static void tileAsphalt(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 112, 12, 11, 0);
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 26; i++) {
            int x = rnd.nextInt(CELL - 20) + 4;
            int y = rnd.nextInt(CELL - 20) + 4;
            int len = 6 + rnd.nextInt(26);
            for (int k = 0; k < len; k++) {
                set(pm, cx, cy, Math.min(CELL - 3, x + k), Math.min(CELL - 3, y + (k % 3 == 0 ? 1 : 0)), 40, 0);
            }
        }
        for (int i = 0; i < 700; i++) {
            int x = rnd.nextInt(CELL - 6) + 3;
            int y = rnd.nextInt(CELL - 6) + 3;
            set(pm, cx, cy, x, y, 90 + rnd.nextInt(40), 0);
        }
    }

    private static void tileFacade(Pixmap pm, int cx, int cy) {
        // Stucco base with faint horizontal shading; multiplied by the facade tint.
        noiseFill(pm, cx, cy, 196, 9, 23, 0);
        int w = CELL;
        // 3 columns x 2 rows of windows.
        int cols = 3, rows = 2;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int ww = (int) (w * 0.16f);
                int wh = (int) (w * 0.26f);
                int x0 = (int) (w * (0.09f + c * 0.29f));
                int y0 = (int) (w * (0.10f + r * 0.45f));
                // frame
                for (int y = y0 - 4; y < y0 + wh + 4; y++) {
                    for (int x = x0 - 4; x < x0 + ww + 4; x++) {
                        if (x > 2 && y > 2 && x < CELL - 3 && y < CELL - 3) {
                            set(pm, cx, cy, x, y, 214, 0);
                        }
                    }
                }
                // glass (alpha = emissive at night)
                for (int y = y0; y < y0 + wh; y++) {
                    for (int x = x0; x < x0 + ww; x++) {
                        if (x > 2 && y > 2 && x < CELL - 3 && y < CELL - 3) {
                            int shade = 46 + ((x + y) % 9) * 3;
                            set(pm, cx, cy, x, y, shade, 255);
                        }
                    }
                }
                // mullion
                for (int y = y0; y < y0 + wh; y++) {
                    set(pm, cx, cy, x0 + ww / 2, y, 190, 0);
                }
                // sill
                for (int x = x0 - 6; x < x0 + ww + 6; x++) {
                    if (x > 2 && x < CELL - 3) {
                        set(pm, cx, cy, x, y0 + wh + 3, 226, 0);
                    }
                }
            }
        }
        // shutter hints
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int ww = (int) (w * 0.16f);
                int wh = (int) (w * 0.26f);
                int x0 = (int) (w * (0.09f + c * 0.29f));
                int y0 = (int) (w * (0.10f + r * 0.45f));
                for (int y = y0; y < y0 + wh; y++) {
                    for (int x = x0 - 12; x < x0 - 6; x++) {
                        if (x > 2) {
                            set(pm, cx, cy, x, y, 150, 0);
                        }
                    }
                }
            }
        }
    }

    private static void tileRoof(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 150, 10, 31, 0);
        for (int y = 4; y < CELL - 4; y += 18) {
            for (int x = 3; x < CELL - 3; x++) {
                set(pm, cx, cy, x, y, 108, 0);
            }
            for (int x = 3; x < CELL - 3; x += 16) {
                int off = ((y / 18) % 2) * 8;
                for (int k = 0; k < 3; k++) {
                    set(pm, cx, cy, x + off + k, Math.min(CELL - 4, y + 1), 118, 0);
                }
            }
            for (int x = 3; x < CELL - 4; x++) {
                set(pm, cx, cy, x, y + 9, 168, 0);
            }
        }
    }

    private static void tileGround(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 170, 16, 43, 0);
        java.util.Random rnd = new java.util.Random(53);
        for (int i = 0; i < 900; i++) {
            int x = rnd.nextInt(CELL - 8) + 4;
            int y = rnd.nextInt(CELL - 8) + 4;
            int v = 120 + rnd.nextInt(80);
            set(pm, cx, cy, x, y, v, 0);
            set(pm, cx, cy, x, Math.min(CELL - 4, y + 1), v - 25, 0);
        }
    }

    private static void tilePaint(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 226, 8, 61, 0);
        java.util.Random rnd = new java.util.Random(67);
        for (int i = 0; i < 220; i++) {
            int x = rnd.nextInt(CELL - 8) + 4;
            int y = rnd.nextInt(CELL - 8) + 4;
            set(pm, cx, cy, x, y, 186 + rnd.nextInt(30), 0);
        }
    }

    private static void tileConcrete(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 178, 10, 71, 0);
        int step = CELL / 8;
        for (int y = 3; y < CELL - 3; y += step) {
            for (int x = 3; x < CELL - 3; x++) {
                set(pm, cx, cy, x, y, 134, 0);
            }
        }
        for (int x = 3; x < CELL - 3; x += step * 2) {
            for (int y = 3; y < CELL - 3; y++) {
                set(pm, cx, cy, x, y, 140, 0);
            }
        }
    }

    private static void tileMetal(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 92, 7, 83, 0);
        for (int y = 3; y < CELL - 3; y += 3) {
            int v = 78 + (y % 7) * 4;
            for (int x = 3; x < CELL - 3; x++) {
                set(pm, cx, cy, x, y, v, 0);
            }
        }
    }

    private static void tileFoliage(Pixmap pm, int cx, int cy) {
        noiseFill(pm, cx, cy, 150, 22, 97, 0);
        java.util.Random rnd = new java.util.Random(101);
        for (int i = 0; i < 1400; i++) {
            int x = rnd.nextInt(CELL - 8) + 4;
            int y = rnd.nextInt(CELL - 8) + 4;
            int v = 96 + rnd.nextInt(96);
            set(pm, cx, cy, x, y, v, 0);
            set(pm, cx, cy, x + 1, y, v - 20, 0);
            set(pm, cx, cy, x, y + 1, v - 34, 0);
        }
    }

    private static void tileGlass(Pixmap pm, int cx, int cy) {
        for (int y = 2; y < CELL - 2; y++) {
            for (int x = 2; x < CELL - 2; x++) {
                int v = 40 + (int) (Math.sin((x + y) * 0.02) * 14);
                if ((x + y) % 97 == 0) {
                    v += 70;
                }
                set(pm, cx, cy, x, y, v, 0);
            }
        }
    }

    private Atlas() {
    }
}

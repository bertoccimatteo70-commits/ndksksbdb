package it.pta.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Disposable;

/** Procedurally built UI skin: no third-party art, consistent PTA look. */
public final class UiAssets implements Disposable {
    public final Texture white;
    public final TextureRegion whiteRegion;
    public final Texture circle;
    public final Texture panel;
    public final Texture bar;
    public final Texture buttonUp;
    public final Texture buttonDown;
    public final Texture logo;
    public final BitmapFont font;
    public final BitmapFont small;
    public final BitmapFont title;
    public final BitmapFont huge;
    public final Skin skin;
    public final float density;
    /** Scale applied to the fixed-size font atlases so text follows screen density. */
    public final float fontScale;

    public UiAssets() {
        density = Math.max(1f, Math.min(3f, Gdx.graphics.getDensity()));
        // Font atlases are generated once at fixed, modest sizes and scaled at runtime.
        // Generating 19..64 px * density atlases synchronously on the GL thread could take
        // tens of seconds on low-end phones, leaving the player on a black screen.
        fontScale = Math.max(1f, Math.min(1.5f, density / 2f));
        white = solid();
        whiteRegion = new TextureRegion(white);
        circle = circleTexture(128);
        panel = panelTexture(96);
        bar = barTexture(8, 64);
        buttonUp = buttonTexture(96, new Color(0.13f, 0.18f, 0.26f, 0.96f),
                new Color(0.24f, 0.40f, 0.58f, 1f));
        buttonDown = buttonTexture(96, new Color(0.30f, 0.47f, 0.66f, 1f),
                new Color(0.55f, 0.74f, 0.90f, 1f));
        logo = loadTexture("ui/logo.png");

        // Fixed sizes keep FreeType rasterisation fast on every device; fontScale restores
        // the physical size without rebuilding huge atlases.
        FreeTypeFontGenerator generator =
                new FreeTypeFontGenerator(Gdx.files.internal("fonts/DejaVuSans.ttf"));
        font = font(generator, 18, 0f);
        small = font(generator, 14, 0f);
        title = font(generator, 26, 1);
        huge = font(generator, 36, 2);
        generator.dispose();
        font.getData().setScale(fontScale);
        small.getData().setScale(fontScale);
        title.getData().setScale(fontScale);
        huge.getData().setScale(fontScale);

        skin = new Skin();
        // Skin stores resources by their concrete class. Register drawables explicitly as
        // Drawable, otherwise getDrawable()/newDrawable() cannot find them and the UI
        // constructor throws during startup (the original black-screen bug).
        skin.add("white", new TextureRegionDrawable(new TextureRegion(white)), Drawable.class);
        skin.add("circle", new TextureRegionDrawable(new TextureRegion(circle)), Drawable.class);
        skin.add("panel9", new NinePatchDrawable(new NinePatch(new TextureRegion(panel), 24, 24, 24, 24)),
                Drawable.class);
        skin.add("btn9up", new NinePatchDrawable(new NinePatch(new TextureRegion(buttonUp), 24, 24, 24, 24)),
                Drawable.class);
        skin.add("btn9down", new NinePatchDrawable(new NinePatch(new TextureRegion(buttonDown), 24, 24, 24, 24)),
                Drawable.class);

        skin.add("default", new Label.LabelStyle(font, new Color(0.92f, 0.94f, 0.97f, 1f)));
        skin.add("small", new Label.LabelStyle(small, new Color(0.78f, 0.82f, 0.88f, 1f)));
        skin.add("title", new Label.LabelStyle(title, new Color(0.98f, 0.92f, 0.72f, 1f)));
        skin.add("huge", new Label.LabelStyle(huge, new Color(0.98f, 0.94f, 0.80f, 1f)));
        skin.add("accent", new Label.LabelStyle(font, new Color(0.95f, 0.78f, 0.35f, 1f)));
        skin.add("danger", new Label.LabelStyle(font, new Color(0.95f, 0.42f, 0.38f, 1f)));

        TextButton.TextButtonStyle btn = new TextButton.TextButtonStyle();
        btn.up = skin.getDrawable("btn9up");
        btn.down = skin.getDrawable("btn9down");
        btn.over = skin.getDrawable("btn9up");
        btn.font = font;
        btn.fontColor = new Color(0.94f, 0.96f, 1f, 1f);
        btn.disabledFontColor = new Color(0.45f, 0.48f, 0.52f, 1f);
        skin.add("default", btn);
        TextButton.TextButtonStyle flat = new TextButton.TextButtonStyle(btn);
        flat.up = skin.newDrawable("panel9");
        flat.down = skin.newDrawable("btn9down");
        skin.add("flat", flat);

        Slider.SliderStyle slider = new Slider.SliderStyle();
        TextureRegionDrawable track = new TextureRegionDrawable(new TextureRegion(white));
        track.setMinHeight(6 * density);
        track.setMinWidth(160 * density);
        TextureRegionDrawable knob = new TextureRegionDrawable(new TextureRegion(circle));
        knob.setMinWidth(30 * density);
        knob.setMinHeight(30 * density);
        slider.background = track;
        slider.knob = knob;
        slider.knobBefore = new TextureRegionDrawable(new TextureRegion(white));
        skin.add("default-horizontal", slider);

        ScrollPane.ScrollPaneStyle scroll = new ScrollPane.ScrollPaneStyle();
        scroll.background = skin.newDrawable("panel9");
        skin.add("default", scroll);
    }

    private Texture solid() {
        Pixmap pm = new Pixmap(4, 4, Pixmap.Format.RGBA8888);
        pm.setColor(Color.WHITE);
        pm.fill();
        Texture t = new Texture(pm);
        pm.dispose();
        return t;
    }

    private Texture circleTexture(int size) {
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pm.setColor(0, 0, 0, 0);
        pm.fill();
        float c = size * 0.5f;
        float r = c - 2;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = x - c;
                float dy = y - c;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float a = Math.max(0f, Math.min(1f, (r - d) * 1.6f));
                if (a > 0) {
                    pm.setColor(1f, 1f, 1f, a);
                    pm.drawPixel(x, y);
                }
            }
        }
        Texture t = new Texture(pm);
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pm.dispose();
        return t;
    }

    private Texture panelTexture(int size) {
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pm.setColor(0, 0, 0, 0);
        pm.fill();
        int r = 18;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inside = rounded(x, y, size, size, r);
                if (!inside) {
                    continue;
                }
                boolean border = !rounded(x, y, size, size, r, 3);
                if (border) {
                    pm.setColor(0.55f, 0.68f, 0.82f, 0.55f);
                } else {
                    pm.setColor(0.045f, 0.065f, 0.09f, 0.90f);
                }
                pm.drawPixel(x, y);
            }
        }
        Texture t = new Texture(pm);
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pm.dispose();
        return t;
    }

    private Texture buttonTexture(int size, Color fill, Color border) {
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pm.setColor(0, 0, 0, 0);
        pm.fill();
        int r = 18;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!rounded(x, y, size, size, r)) {
                    continue;
                }
                if (!rounded(x, y, size, size, r, 2)) {
                    pm.setColor(border);
                } else {
                    float v = 1f - (y / (float) size) * 0.18f;
                    pm.setColor(fill.r * v, fill.g * v, fill.b * v, fill.a);
                }
                pm.drawPixel(x, y);
            }
        }
        Texture t = new Texture(pm);
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pm.dispose();
        return t;
    }

    private boolean rounded(int x, int y, int w, int h, int r) {
        return rounded(x, y, w, h, r, 0);
    }

    private boolean rounded(int x, int y, int w, int h, int r, int inset) {
        float rx = r;
        float left = inset;
        float right = w - 1 - inset;
        float top = inset;
        float bottom = h - 1 - inset;
        if (x < left || x > right || y < top || y > bottom) {
            return false;
        }
        float cx = Math.max(left + rx, Math.min(right - rx, x));
        float cy = Math.max(top + rx, Math.min(bottom - rx, y));
        float dx = x - cx;
        float dy = y - cy;
        return dx * dx + dy * dy <= rx * rx;
    }

    private Texture barTexture(int w, int h) {
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        pm.setColor(Color.WHITE);
        pm.fill();
        Texture t = new Texture(pm);
        pm.dispose();
        return t;
    }

    private Texture loadTexture(String path) {
        try {
            return new Texture(Gdx.files.internal(path));
        } catch (Exception e) {
            return white;
        }
    }

    private BitmapFont font(FreeTypeFontGenerator generator, int size, float border) {
        FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size = size;
        p.color = Color.WHITE;
        p.borderWidth = border;
        p.borderColor = new Color(0.02f, 0.03f, 0.05f, 1f);
        p.minFilter = Texture.TextureFilter.Linear;
        p.magFilter = Texture.TextureFilter.Linear;
        p.characters = FreeTypeFontGenerator.DEFAULT_CHARS + "€·←→↑↓▲▼◀▶★✓";
        return generator.generateFont(p);
    }

    public float dp(float v) {
        return v * density;
    }

    @Override
    public void dispose() {
        skin.dispose();
        font.dispose();
        small.dispose();
        title.dispose();
        huge.dispose();
        white.dispose();
        circle.dispose();
        panel.dispose();
        bar.dispose();
        buttonUp.dispose();
        buttonDown.dispose();
        if (logo != white) {
            logo.dispose();
        }
    }
}

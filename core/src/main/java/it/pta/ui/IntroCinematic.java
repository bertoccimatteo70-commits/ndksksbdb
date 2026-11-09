package it.pta.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import it.pta.game.Content;
import it.pta.world.MapData;

import java.util.ArrayList;
import java.util.List;

/** Skippable opening cinematic: Pistoia flyovers, animated PTA logo and titles. */
public final class IntroCinematic {
    private static final float SHOT_TIME = 5.4f;
    private final UiAssets a;
    private final MapData map;
    private final List<float[]> shots = new ArrayList<>();
    private final Vector3 pos = new Vector3();
    private final Vector3 look = new Vector3();
    private final GlyphLayout layout = new GlyphLayout();
    public float time;
    public boolean finished;
    public float total;

    public IntroCinematic(UiAssets assets, MapData map) {
        this.a = assets;
        this.map = map;
        add("duomo", 95f, 42f, 6f);
        add("battistero", 62f, 26f, 14f);
        add("fortezza", 110f, 46f, 12f);
        add("stazione", 70f, 24f, 5f);
        add("serravalle_castello", 150f, 62f, 20f);
        add("duomo", 130f, 55f, 20f);
        total = shots.size() * SHOT_TIME + 2.5f;
    }

    private void add(String place, float radius, float height, float lookHeight) {
        Content.Place p = Content.get(place);
        if (p != null) {
            shots.add(new float[]{p.x, p.z, radius, height, lookHeight});
        }
    }

    public void update(float dt, PerspectiveCamera camera) {
        if (finished) {
            return;
        }
        if (shots.isEmpty()) {
            // Never leave the player on a black frame if content failed to load.
            finished = true;
            return;
        }
        time += dt;
        if (time >= total) {
            finished = true;
            return;
        }
        int count = Math.max(1, shots.size());
        int i = Math.min(count - 1, (int) (time / SHOT_TIME));
        float local = MathUtils.clamp((time - i * SHOT_TIME) / SHOT_TIME, 0f, 1f);
        float smooth = local * local * (3f - 2f * local);
        float[] s = shots.get(i);
        float[] n = shots.get((i + 1) % count);
        float cx = MathUtils.lerp(s[0], n[0], smooth * 0.35f);
        float cz = MathUtils.lerp(s[1], n[1], smooth * 0.35f);
        float radius = MathUtils.lerp(s[2], n[2], smooth);
        float height = MathUtils.lerp(s[3], n[3], smooth);
        float ang = i * 1.35f + local * 0.85f;
        pos.set(cx + MathUtils.cos(ang) * radius, map.heightAt(cx, cz) + height,
                cz + MathUtils.sin(ang) * radius);
        look.set(cx, map.heightAt(cx, cz) + s[4], cz);
        camera.position.set(pos);
        camera.lookAt(look);
        camera.up.set(Vector3.Y);
        camera.update();
    }

    public void skip() {
        finished = true;
    }

    public void drawOverlay(Batch batch, float w, float h) {
        float fade = 1f - MathUtils.clamp(time / 1.4f, 0f, 1f);
        float out = MathUtils.clamp((total - time) / 1.6f, 0f, 1f);
        float black = Math.max(fade, out);
        batch.setColor(0.01f, 0.01f, 0.02f, black);
        batch.draw(a.white, 0, 0, w, h);

        float bar = h * 0.085f;
        batch.setColor(0.005f, 0.005f, 0.01f, 0.95f);
        batch.draw(a.white, 0, 0, w, bar);
        batch.draw(a.white, 0, h - bar, w, bar);

        float logoIn = MathUtils.clamp((time - 1.0f) / 1.2f, 0f, 1f);
        float logoOut = MathUtils.clamp((total - 2.2f - time) / 1.4f, 0f, 1f);
        float logoAlpha = Math.min(logoIn, logoOut);
        if (logoAlpha > 0) {
            float size = Math.min(h * 0.46f, w * 0.26f) * (0.94f + 0.06f * logoIn);
            float lx = w * 0.5f - size * 0.5f;
            float ly = h * 0.52f;
            batch.setColor(1f, 1f, 1f, logoAlpha);
            if (a.logo != null) {
                batch.draw(a.logo, lx, ly, size, size);
            }
            batch.setColor(1f, 1f, 1f, logoAlpha * 0.9f);
            a.title.setColor(0.98f, 0.90f, 0.68f, logoAlpha);
            layout.setText(a.title, "PISTOIA TOWN ADVENTURES");
            a.title.draw(batch, layout, w * 0.5f - layout.width * 0.5f, ly - a.dp(10));
            a.small.setColor(0.80f, 0.86f, 0.92f, logoAlpha * 0.9f);
            layout.setText(a.small, "Un open world nella provincia di Pistoia");
            a.small.draw(batch, layout, w * 0.5f - layout.width * 0.5f, ly - a.dp(34));
        }

        float captions = MathUtils.clamp((time - 5f) / 1.5f, 0f, 1f)
                * MathUtils.clamp((total - 3f - time) / 1.5f, 0f, 1f);
        if (captions > 0) {
            int shot = Math.min(shots.size() - 1, (int) (time / SHOT_TIME));
            String[] titles = {
                    "Piazza del Duomo", "Il Battistero", "Fortezza Santa Barbara",
                    "La stazione", "Serravalle Pistoiese", "Pistoia"
            };
            String[] subs = {
                    "Pistoia, Toscana", "Il cuore della città", "Le mura e la storia",
                    "Binari per Firenze", "Le colline della Valdinievole", "La provincia, tutta da esplorare"
            };
            int idx = Math.min(titles.length - 1, shot);
            a.title.setColor(0.97f, 0.92f, 0.78f, captions * 0.95f);
            a.title.getData().setScale(a.fontScale * 0.58f);
            layout.setText(a.title, titles[idx]);
            a.title.draw(batch, layout, w * 0.5f - layout.width * 0.5f, bar + a.dp(58));
            a.title.getData().setScale(a.fontScale);
            a.small.setColor(0.82f, 0.88f, 0.94f, captions);
            layout.setText(a.small, subs[idx]);
            a.small.draw(batch, layout, w * 0.5f - layout.width * 0.5f, bar + a.dp(34));
        }

        a.small.setColor(0.92f, 0.94f, 0.98f, 0.75f);
        a.small.draw(batch, "Tocca per saltare", w - a.dp(190), h - bar - a.dp(24));
        a.small.setColor(0.55f, 0.60f, 0.66f, 0.65f);
        a.small.draw(batch, "Mappa © OpenStreetMap contributors · ODbL", a.dp(18), bar + a.dp(8));
        batch.setColor(Color.WHITE);
    }
}

package it.pta.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import it.pta.game.MissionSystem;
import it.pta.game.World;
import it.pta.world.MapData;

import java.util.ArrayList;
import java.util.List;

/** In-game HUD: minimap, money, wanted stars, health, fuel, objectives and dialogue. */
public final class Hud extends Actor {
    private final World world;
    private final UiAssets a;
    private final GlyphLayout layout = new GlyphLayout();
    private final List<float[]> segments = new ArrayList<>();
    private float mapTimer;
    private float pulse;

    public boolean visible;
    public String district = "Pistoia";

    public Hud(World world, UiAssets assets) {
        this.world = world;
        this.a = assets;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        pulse += delta;
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        if (!visible) {
            return;
        }
        float w = getWidth();
        float h = getHeight();
        float dp = a.density;
        batch.setColor(Color.WHITE);

        drawMinimap(batch, w, h, dp);

        // money / clock / stars
        a.font.setColor(0.98f, 0.92f, 0.70f, 1f);
        String money = "€ " + it.pta.game.Economy.format(world.economy.cash);
        a.font.draw(batch, money, w - a.dp(24) - 250 * dp * 0.72f, h - a.dp(258));
        int hours = (int) world.renderer.sun.hours;
        int minutes = (int) ((world.renderer.sun.hours - hours) * 60);
        String clock = String.format("%02d:%02d", hours, minutes);
        a.small.setColor(0.85f, 0.90f, 0.96f, 0.9f);
        a.small.draw(batch, clock, w - a.dp(130), h - a.dp(282));

        int stars = world.police.wanted;
        if (stars > 0) {
            a.title.setColor(0.98f, 0.78f, 0.25f, 1f);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append(i < stars ? "★" : "☆");
            }
            a.title.getData().setScale(a.fontScale * 0.62f);
            a.title.draw(batch, sb.toString(), w - a.dp(268), h - a.dp(300));
            a.title.getData().setScale(a.fontScale);
            a.small.setColor(0.96f, 0.55f, 0.30f, 1f);
            a.small.draw(batch, "RICERCATO", w - a.dp(268), h - a.dp(316));
        }

        // health and armour
        drawBar(batch, a.dp(24), a.dp(58), a.dp(150), a.dp(9),
                world.player().health / 100f, new Color(0.85f, 0.30f, 0.28f, 0.92f));
        if (world.player().armor > 0) {
            drawBar(batch, a.dp(24), a.dp(44), a.dp(150), a.dp(6),
                    world.player().armor / 100f, new Color(0.30f, 0.62f, 0.90f, 0.92f));
        }
        if (world.player().inVehicle()) {
            it.pta.entity.Vehicle v = world.player().vehicle;
            drawBar(batch, a.dp(24), a.dp(44), a.dp(150), a.dp(6),
                    Math.min(1f, v.fuel / Math.max(1f, v.fuelCapacity)),
                    new Color(0.90f, 0.76f, 0.30f, 0.92f));
            a.font.setColor(0.92f, 0.95f, 0.99f, 1f);
            a.font.draw(batch, v.type.label + " · " + (int) Math.abs(v.speed * 3.6f) + " km/h",
                    a.dp(24), a.dp(30));
            a.small.setColor(0.78f, 0.84f, 0.90f, 1f);
            a.small.draw(batch, "Carburante " + (int) v.fuel + " L · Danni " + (int) v.damage + "%",
                    a.dp(24), a.dp(14));
        } else if (!world.insideInterior()) {
            a.small.setColor(0.85f, 0.90f, 0.95f, 0.9f);
            a.small.draw(batch, world.player().protagonistName() + " · " + district,
                    a.dp(24), a.dp(30));
        }

        // objective panel
        MissionSystem.Objective ob = world.missions.currentObjective();
        if (ob != null) {
            float pw = Math.min(w * 0.46f, a.dp(430));
            float px = (w - pw) * 0.5f;
            float py = h - a.dp(96);
            batch.setColor(0.03f, 0.05f, 0.08f, 0.72f);
            batch.draw(a.panel, px, py, pw, a.dp(74));
            batch.setColor(Color.WHITE);
            a.small.setColor(0.95f, 0.78f, 0.35f, 1f);
            a.small.draw(batch, world.missions.active.title.toUpperCase(), px + a.dp(14), py + a.dp(58));
            a.font.setColor(0.94f, 0.96f, 0.99f, 1f);
            layout.setText(a.font, ob.text, Color.WHITE, pw - a.dp(28), com.badlogic.gdx.utils.Align.left, true);
            a.font.draw(batch, layout, px + a.dp(14), py + a.dp(40));
            float dist = world.missions.distanceToMarker(world);
            if (world.missions.markerActive) {
                a.small.setColor(0.72f, 0.84f, 0.95f, 1f);
                a.small.draw(batch, (int) dist + " m", px + pw - a.dp(60), py + a.dp(58));
                a.small.setColor(world.gpsAvailable ? 0.45f : 0.95f,
                        world.gpsAvailable ? 0.92f : 0.60f,
                        world.gpsAvailable ? 0.95f : 0.28f, 1f);
                String gps = world.gpsAvailable
                        ? "GPS stradale · " + (int) (world.gpsDistance / 1000f * 10) / 10f + " km"
                        : "GPS: " + (world.gpsMessage.isEmpty() ? "calcolo percorso" : world.gpsMessage);
                a.small.draw(batch, gps, px + a.dp(14), py + a.dp(16));
            }
        }

        // notification toast
        if (!world.notification.isEmpty() && world.notificationTimer > 0) {
            float alpha = MathUtils.clamp(world.notificationTimer, 0f, 1f);
            a.font.setColor(0.96f, 0.94f, 0.85f, alpha);
            layout.setText(a.font, world.notification);
            float tw = layout.width + a.dp(30);
            float tx = (w - tw) * 0.5f;
            float ty = h - a.dp(150);
            batch.setColor(0.04f, 0.06f, 0.09f, 0.78f * alpha);
            batch.draw(a.panel, tx, ty, tw, a.dp(42));
            batch.setColor(Color.WHITE);
            a.font.draw(batch, layout, tx + a.dp(15), ty + a.dp(30));
        }

        // interaction prompt
        if (!world.prompt.isEmpty()) {
            layout.setText(a.font, world.prompt);
            float pw = layout.width + a.dp(34);
            float px = (w - pw) * 0.5f;
            float py = a.dp(150);
            batch.setColor(0.05f, 0.08f, 0.12f, 0.86f);
            batch.draw(a.panel, px, py, pw, a.dp(46));
            batch.setColor(Color.WHITE);
            a.font.setColor(0.98f, 0.93f, 0.78f, 1f);
            a.font.draw(batch, layout, px + a.dp(17), py + a.dp(33));
        }

        // dialogue
        MissionSystem.Line line = world.missions.currentLine();
        if (line != null) {
            float pw = Math.min(w * 0.78f, a.dp(720));
            float ph = a.dp(118);
            float px = (w - pw) * 0.5f;
            float py = a.dp(178);
            batch.setColor(0.02f, 0.03f, 0.05f, 0.88f);
            batch.draw(a.panel, px, py, pw, ph);
            batch.setColor(Color.WHITE);
            a.font.setColor(0.98f, 0.82f, 0.42f, 1f);
            a.font.draw(batch, line.speaker, px + a.dp(18), py + ph - a.dp(16));
            a.font.setColor(0.93f, 0.95f, 0.98f, 1f);
            layout.setText(a.font, line.text, Color.WHITE, pw - a.dp(36),
                    com.badlogic.gdx.utils.Align.left, true);
            a.font.draw(batch, layout, px + a.dp(18), py + ph - a.dp(44));
            a.small.setColor(0.65f, 0.74f, 0.84f, 1f);
            a.small.draw(batch, "tocca per continuare", px + pw - a.dp(170), py + a.dp(14));
        }

        // fps
        if (world.settings.showFps) {
            a.small.setColor(0.85f, 0.95f, 0.85f, 0.85f);
            a.small.draw(batch, (int) com.badlogic.gdx.Gdx.graphics.getFramesPerSecond() + " FPS · " +
                    world.settings.qualitySummary(), a.dp(24), h - a.dp(24));
        }
        batch.setColor(Color.WHITE);
    }

    private void drawBar(Batch batch, float x, float y, float w, float h, float value, Color color) {
        batch.setColor(0.05f, 0.07f, 0.10f, 0.80f);
        batch.draw(a.white, x, y, w, h);
        batch.setColor(color);
        batch.draw(a.white, x, y, w * MathUtils.clamp(value, 0f, 1f), h);
        batch.setColor(Color.WHITE);
    }

    private void drawMinimap(Batch batch, float w, float h, float dp) {
        float radius = a.dp(88);
        float cx = w - radius - a.dp(30);
        float cy = h - radius - a.dp(30);
        batch.setColor(0.02f, 0.04f, 0.06f, 0.88f);
        batch.draw(a.circle, cx - radius, cy - radius, radius * 2, radius * 2);
        float scale = 300f / radius; // metres per pixel

        mapTimer -= com.badlogic.gdx.Gdx.graphics.getDeltaTime();
        if (mapTimer <= 0) {
            mapTimer = 0.30f;
            segments.clear();
            float px = world.player().x;
            float pz = world.player().z;
            world.map.forEachRoadSegment(px - 300, pz - 300, px + 300, pz + 300,
                    (x0, z0, x1, z1, drivable) -> {
                        if (Math.abs(x0 - px) > 300 || Math.abs(z0 - pz) > 300) {
                            return true;
                        }
                        segments.add(new float[]{x0, z0, x1, z1, drivable ? 1 : 0});
                        return segments.size() < 520;
                    }, 520);
        }

        for (float[] s : segments) {
            float x0 = cx + (s[0] - world.player().x) / scale;
            float y0 = cy - (s[1] - world.player().z) / scale;
            float x1 = cx + (s[2] - world.player().x) / scale;
            float y1 = cy - (s[3] - world.player().z) / scale;
            float dx = x1 - x0, dy = y1 - y0;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 0.4f) {
                continue;
            }
            if (Math.hypot(x0 - cx, y0 - cy) > radius) {
                continue;
            }
            batch.setColor(s[4] > 0 ? new Color(0.78f, 0.82f, 0.88f, 0.85f)
                    : new Color(0.48f, 0.52f, 0.58f, 0.55f));
            float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
            float thick = s[4] > 0 ? 2.2f * dp : 1.1f * dp;
            batch.draw(a.whiteRegion, x0, y0 - thick * 0.5f, 0, thick * 0.5f, len, thick, 1f, 1f, angle);
        }

        // GPS route: same points as the 3D ribbon on the asphalt.
        if (world.gpsAvailable && world.gpsRoute != null) {
            batch.setColor(0.10f, 0.92f, 0.96f, 0.95f);
            float[] route = world.gpsRoute;
            for (int i = 0; i + 3 < route.length; i += 2) {
                float x0 = cx + (route[i] - world.player().x) / scale;
                float y0 = cy - (route[i + 1] - world.player().z) / scale;
                float x1 = cx + (route[i + 2] - world.player().x) / scale;
                float y1 = cy - (route[i + 3] - world.player().z) / scale;
                float dx = x1 - x0, dy = y1 - y0;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1f || Math.hypot(x0 - cx, y0 - cy) > radius) {
                    continue;
                }
                float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
                batch.draw(a.whiteRegion, x0, y0 - 1.6f * dp, 0, 1.6f * dp,
                        len, 3.2f * dp, 1f, 1f, angle);
            }
        }

        // mission marker
        if (world.missions.markerActive) {
            float mx = (world.missions.markerX - world.player().x) / scale;
            float mz = -(world.missions.markerZ - world.player().z) / scale;
            float len = (float) Math.sqrt(mx * mx + mz * mz);
            float lim = radius - a.dp(10);
            if (len > lim) {
                mx = mx / len * lim;
                mz = mz / len * lim;
            }
            batch.setColor(0.98f, 0.80f, 0.25f, 1f);
            batch.draw(a.circle, cx + mx - a.dp(6), cy + mz - a.dp(6), a.dp(12), a.dp(12));
        }

        // player arrow
        float hx = MathUtils.sin(world.player().heading);
        float hz = -MathUtils.cos(world.player().heading);
        batch.setColor(1f, 1f, 1f, 1f);
        float px = cx + hx * a.dp(10);
        float py = cy - hz * a.dp(10);
        batch.draw(a.circle, cx - a.dp(7), cy - a.dp(7), a.dp(14), a.dp(14));
        batch.setColor(0.20f, 0.35f, 0.55f, 1f);
        batch.draw(a.circle, cx - a.dp(4), cy - a.dp(4), a.dp(8), a.dp(8));
        batch.setColor(0.98f, 0.82f, 0.30f, 1f);
        batch.draw(a.whiteRegion, cx, cy, 0, 1.5f * dp, a.dp(16), 3f * dp, 1f, 1f,
                (float) Math.toDegrees(world.player().heading) - 90f);

        // ring
        batch.setColor(0.55f, 0.68f, 0.82f, 0.5f);
        for (int i = 0; i < 40; i++) {
            float ang = (float) (i * Math.PI * 2 / 40);
            batch.draw(a.white, cx + MathUtils.cos(ang) * radius - 2, cy + MathUtils.sin(ang) * radius - 2, 4, 4);
        }
        a.small.setColor(0.90f, 0.94f, 1f, 1f);
        a.small.draw(batch, "N", cx - a.dp(5), cy + radius + a.dp(4));
        batch.setColor(Color.WHITE);
    }
}

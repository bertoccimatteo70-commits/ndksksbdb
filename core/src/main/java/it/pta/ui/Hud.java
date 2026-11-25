package it.pta.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import it.pta.game.Content;
import it.pta.game.MissionSystem;
import it.pta.game.World;
import it.pta.world.MapData;

/** In-game HUD: minimap, money, wanted stars, health, fuel, objectives and dialogue. */
public final class Hud extends Actor {
    private static final Color ROAD_DRIVABLE = new Color(0.90f, 0.94f, 0.99f, 0.96f);
    private static final Color ROAD_MINOR = new Color(0.56f, 0.62f, 0.71f, 0.82f);
    private static final Color MAP_BG = new Color(0.015f, 0.030f, 0.050f, 0.93f);
    private static final Color MAP_RING = new Color(0.55f, 0.70f, 0.86f, 0.80f);
    private static final Color POI_HOME = new Color(0.40f, 0.92f, 0.55f, 1f);
    private static final Color POI_SHOP = new Color(0.98f, 0.80f, 0.30f, 1f);
    private static final Color POI_SERVICE = new Color(0.40f, 0.80f, 0.98f, 1f);
    private static final Color POI_POLICE = new Color(0.96f, 0.45f, 0.42f, 1f);

    private final World world;
    private final UiAssets a;
    private final GlyphLayout layout = new GlyphLayout();
    /** Flat x0,z0,x1,z1,drivable records; reused so the minimap allocates nothing. */
    private float[] roads = new float[5 * 720];
    private int roadCount;
    private final float[] poi = new float[3 * 40];
    private int poiCount;
    private String streetName = "";
    private final float[] clipped = new float[4];
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
                if (world.gpsAvailable) {
                    // Same route as the 3D ribbon: next manoeuvre and its street.
                    String arrow = world.gpsNextTurnSide > 0 ? "→ destra"
                            : world.gpsNextTurnSide < 0 ? "← sinistra" : "↑ dritto";
                    String street = world.gpsNextTurnStreet.isEmpty()
                            ? "" : " · " + world.gpsNextTurnStreet;
                    a.small.setColor(0.55f, 0.95f, 1f, 1f);
                    a.small.draw(batch, arrow + " tra " + (int) world.gpsNextTurnDistance
                            + " m" + street, px + pw - a.dp(240), py + a.dp(16));
                }
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

    /**
     * Minimap: the real OSM streets around the player, with the drivable network in
     * white and the rest in grey, landmark dots, a north marker and the name of the
     * street the player is standing on. Everything is clipped to the dial, so no
     * segment spills outside the circle, and the records are reused every refresh.
     */
    private void drawMinimap(Batch batch, float w, float h, float dp) {
        float radius = a.dp(96);
        float cx = w - radius - a.dp(28);
        float cy = h - radius - a.dp(28);
        // 280 m across the dial: enough to read the next junction while walking.
        float scale = 280f / radius;

        mapTimer -= com.badlogic.gdx.Gdx.graphics.getDeltaTime();
        if (mapTimer <= 0) {
            mapTimer = 0.45f;
            float px = world.player().x;
            float pz = world.player().z;
            roadCount = 0;
            world.map.forEachRoadSegment(px - 290, pz - 290, px + 290, pz + 290,
                    (x0, z0, x1, z1, drivable) -> {
                        if (roadCount >= 720) {
                            return false;
                        }
                        int p = roadCount * 5;
                        roads[p] = x0;
                        roads[p + 1] = z0;
                        roads[p + 2] = x1;
                        roads[p + 3] = z1;
                        roads[p + 4] = drivable ? 1f : 0f;
                        roadCount++;
                        return true;
                    }, 720);
            MapData.RoadHit hit = world.map.nearestRoad(px, pz, true, 50f);
            String name = hit == null ? null : world.map.name(hit.way.nameIdx);
            streetName = name == null ? "" : name.trim();
            poiCount = 0;
            for (Content.Place place : Content.all()) {
                if (poiCount >= 40) {
                    break;
                }
                if (Math.hypot(place.x - px, place.z - pz) > 380f) {
                    continue;
                }
                poi[poiCount * 3] = place.x;
                poi[poiCount * 3 + 1] = place.z;
                poi[poiCount * 3 + 2] = place.kind.ordinal();
                poiCount++;
            }
        }

        // Shadow, outer ring and dark dial.
        batch.setColor(0f, 0f, 0f, 0.35f);
        batch.draw(a.circle, cx - radius - a.dp(4), cy - radius - a.dp(6),
                (radius + a.dp(4)) * 2f, (radius + a.dp(4)) * 2f);
        batch.setColor(MAP_RING);
        batch.draw(a.circle, cx - radius - a.dp(2), cy - radius - a.dp(2),
                (radius + a.dp(2)) * 2f, (radius + a.dp(2)) * 2f);
        batch.setColor(MAP_BG);
        batch.draw(a.circle, cx - radius, cy - radius, radius * 2f, radius * 2f);

        // Streets, clipped to the dial so nothing spills over the HUD.
        float px = world.player().x;
        float pz = world.player().z;
        for (int i = 0; i < roadCount; i++) {
            int p = i * 5;
            clipped[0] = cx + (roads[p] - px) / scale;
            clipped[1] = cy - (roads[p + 1] - pz) / scale;
            clipped[2] = cx + (roads[p + 2] - px) / scale;
            clipped[3] = cy - (roads[p + 3] - pz) / scale;
            if (!clipToCircle(clipped, cx, cy, radius - dp)) {
                continue;
            }
            float dx = clipped[2] - clipped[0];
            float dy = clipped[3] - clipped[1];
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 0.5f) {
                continue;
            }
            boolean drivable = roads[p + 4] > 0.5f;
            batch.setColor(drivable ? ROAD_DRIVABLE : ROAD_MINOR);
            float thick = (drivable ? 2.8f : 1.3f) * dp;
            float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
            batch.draw(a.whiteRegion, clipped[0], clipped[1] - thick * 0.5f, 0,
                    thick * 0.5f, len, thick, 1f, 1f, angle);
        }

        // Local landmarks: a few coloured dots make the map readable even without a
        // mission marker (home green, shops gold, services blue, police red).
        for (int i = 0; i < poiCount; i++) {
            float x = cx + (poi[i * 3] - px) / scale;
            float y = cy - (poi[i * 3 + 1] - pz) / scale;
            if (Math.hypot(x - cx, y - cy) > radius - a.dp(8)) {
                continue;
            }
            batch.setColor(poiColor((int) poi[i * 3 + 2]));
            batch.draw(a.circle, x - a.dp(3.5f), y - a.dp(3.5f), a.dp(7f), a.dp(7f));
        }

        // GPS route: the same points as the 3D ribbon on the asphalt.
        if (world.gpsAvailable && world.gpsRoute != null) {
            batch.setColor(0.10f, 0.92f, 0.96f, 0.95f);
            float[] route = world.gpsRoute;
            for (int i = 0; i + 3 < route.length; i += 2) {
                clipped[0] = cx + (route[i] - px) / scale;
                clipped[1] = cy - (route[i + 1] - pz) / scale;
                clipped[2] = cx + (route[i + 2] - px) / scale;
                clipped[3] = cy - (route[i + 3] - pz) / scale;
                if (!clipToCircle(clipped, cx, cy, radius - dp)) {
                    continue;
                }
                float dx = clipped[2] - clipped[0];
                float dy = clipped[3] - clipped[1];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1f) {
                    continue;
                }
                float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
                batch.draw(a.whiteRegion, clipped[0], clipped[1] - 1.8f * dp, 0, 1.8f * dp,
                        len, 3.6f * dp, 1f, 1f, angle);
            }
        }

        // Mission marker.
        if (world.missions.markerActive) {
            float mx = (world.missions.markerX - px) / scale;
            float mz = -(world.missions.markerZ - pz) / scale;
            float len = (float) Math.sqrt(mx * mx + mz * mz);
            float lim = radius - a.dp(10);
            if (len > lim) {
                mx = mx / len * lim;
                mz = mz / len * lim;
            }
            batch.setColor(0.98f, 0.80f, 0.25f, 1f);
            batch.draw(a.circle, cx + mx - a.dp(7), cy + mz - a.dp(7), a.dp(14), a.dp(14));
        }

        // Player arrow.
        batch.setColor(1f, 1f, 1f, 1f);
        batch.draw(a.circle, cx - a.dp(7), cy - a.dp(7), a.dp(14), a.dp(14));
        batch.setColor(0.20f, 0.35f, 0.55f, 1f);
        batch.draw(a.circle, cx - a.dp(4), cy - a.dp(4), a.dp(8), a.dp(8));
        batch.setColor(0.98f, 0.82f, 0.30f, 1f);
        batch.draw(a.whiteRegion, cx, cy, 0, 1.5f * dp, a.dp(16), 3f * dp, 1f, 1f,
                (float) Math.toDegrees(world.player().heading) - 90f);

        // North marker and the current street name below the dial.
        a.small.setColor(0.90f, 0.94f, 1f, 1f);
        a.small.draw(batch, "N", cx - a.dp(5), cy + radius + a.dp(2));
        if (!streetName.isEmpty()) {
            layout.setText(a.small, streetName);
            float nameW = Math.min(layout.width, radius * 1.7f);
            a.small.setColor(0.86f, 0.92f, 0.98f, 0.95f);
            a.small.draw(batch, streetName, cx - nameW * 0.5f, cy - radius - a.dp(10), nameW,
                    com.badlogic.gdx.utils.Align.center, false);
        }
        batch.setColor(Color.WHITE);
    }

    private static Color poiColor(int kind) {
        switch (kind) {
            case 1: // HOME
                return POI_HOME;
            case 2: // SHOP
            case 3: // DEALER
            case 4: // TUNING
            case 9: // MISSION
            case 10: // LANDMARK
                return POI_SHOP;
            case 6: // POLICE
                return POI_POLICE;
            default:
                return POI_SERVICE;
        }
    }

    /** Clips a screen-space segment to the dial. Returns false when it misses it. */
    private static boolean clipToCircle(float[] p, float cx, float cy, float radius) {
        float ax = p[0] - cx, ay = p[1] - cy;
        float bx = p[2] - cx, by = p[3] - cy;
        float r2 = radius * radius;
        float a2 = ax * ax + ay * ay;
        float b2 = bx * bx + by * by;
        boolean inA = a2 <= r2;
        boolean inB = b2 <= r2;
        if (!inA && !inB) {
            return false;
        }
        float dx = bx - ax, dy = by - ay;
        float len2 = dx * dx + dy * dy;
        if (len2 < 1e-4f) {
            if (!inA) {
                return false;
            }
        } else {
            float bq = 2f * (ax * dx + ay * dy);
            float cq = a2 - r2;
            float disc = bq * bq - 4f * len2 * cq;
            if (disc >= 0f) {
                float sq = (float) Math.sqrt(disc);
                float t1 = (-bq - sq) / (2f * len2);
                float t2 = (-bq + sq) / (2f * len2);
                if (!inA && t1 > 0f && t1 < 1f) {
                    ax += dx * t1;
                    ay += dy * t1;
                } else if (inA && !inB && t2 > 0f && t2 < 1f) {
                    bx = ax + dx * t2;
                    by = ay + dy * t2;
                }
            }
        }
        p[0] = ax + cx;
        p[1] = ay + cy;
        p[2] = bx + cx;
        p[3] = by + cy;
        return true;
    }
}

package it.pta.render;

import com.badlogic.gdx.math.Vector3;

/**
 * Day/night cycle. Times are hours (0..24); the default cycle advances one in-game
 * hour every 2 real minutes, so a full day takes 48 minutes of play.
 */
public final class Sun {
    public final Vector3 direction = new Vector3(0.4f, 0.7f, 0.3f);
    public final Vector3 color = new Vector3(1f, 1f, 1f);
    public final Vector3 ambient = new Vector3(0.4f, 0.45f, 0.5f);
    public final Vector3 fogColor = new Vector3(0.7f, 0.75f, 0.8f);
    public final Vector3 zenith = new Vector3(0.25f, 0.45f, 0.75f);
    public final Vector3 horizon = new Vector3(0.72f, 0.78f, 0.82f);
    public float night; // 0 day .. 1 night
    public float elevation; // sun height -1..1
    public float hours = 9f;

    public void setHours(float h) {
        hours = ((h % 24f) + 24f) % 24f;
        update();
    }

    public void advance(float deltaSeconds, float hoursPerRealMinute) {
        hours += deltaSeconds * (hoursPerRealMinute / 60f);
        if (hours >= 24f) {
            hours -= 24f;
        }
        update();
    }

    private void update() {
        double theta = Math.PI * (hours - 6.0) / 12.0;
        float sy = (float) Math.sin(theta);
        float sx = (float) Math.cos(theta);
        elevation = sy;
        direction.set(sx * 0.92f, Math.max(0.06f, sy), 0.38f).nor();

        night = clamp((-elevation + 0.14f) * 3.2f, 0f, 1f);
        float dusk = clamp(1f - Math.abs(elevation) * 4.2f, 0f, 1f);

        // daylight palette
        Vector3 daySun = new Vector3(1.06f, 1.0f, 0.90f);
        Vector3 duskSun = new Vector3(1.15f, 0.62f, 0.34f);
        Vector3 nightSun = new Vector3(0.16f, 0.20f, 0.34f);
        color.set(daySun).lerp(duskSun, dusk).lerp(nightSun, night);

        Vector3 dayAmb = new Vector3(0.46f, 0.50f, 0.60f);
        Vector3 duskAmb = new Vector3(0.42f, 0.34f, 0.36f);
        Vector3 nightAmb = new Vector3(0.13f, 0.16f, 0.26f);
        ambient.set(dayAmb).lerp(duskAmb, dusk * 0.85f).lerp(nightAmb, night);

        Vector3 dayFog = new Vector3(0.74f, 0.80f, 0.86f);
        Vector3 duskFog = new Vector3(0.78f, 0.58f, 0.44f);
        Vector3 nightFog = new Vector3(0.07f, 0.09f, 0.15f);
        fogColor.set(dayFog).lerp(duskFog, dusk * 0.8f).lerp(nightFog, night);

        Vector3 dayZenith = new Vector3(0.24f, 0.46f, 0.78f);
        Vector3 duskZenith = new Vector3(0.16f, 0.22f, 0.48f);
        Vector3 nightZenith = new Vector3(0.02f, 0.03f, 0.07f);
        zenith.set(dayZenith).lerp(duskZenith, dusk).lerp(nightZenith, night);
        horizon.set(fogColor).add(0.04f, 0.05f, 0.05f);
    }

    private static float clamp(float v, float a, float b) {
        return v < a ? a : (v > b ? b : v);
    }

    public String clock() {
        int h = (int) hours;
        int m = (int) ((hours - h) * 60);
        return String.format("%02d:%02d", h, m);
    }
}

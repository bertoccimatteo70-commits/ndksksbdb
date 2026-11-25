package it.pta.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.math.Vector2;

import java.util.LinkedHashMap;
import java.util.Map;

/** Normalised, player-editable touch button layout (position, size, opacity). */
public final class ControlLayout {
    public final Map<String, Vector2> positions = new LinkedHashMap<>();
    public float scale = 1f;
    public float alpha = 0.82f;
    public boolean leftHanded;
    public boolean editing;

    private static final String[] ON_FOOT = {"joy", "run", "jump", "attack", "interact", "enter", "view", "swap", "pause"};
    private static final String[] DRIVING = {"throttle", "brake", "left", "right", "handbrake", "exit", "view", "pause"};

    public ControlLayout() {
        reset();
        load();
    }

    public void reset() {
        positions.clear();
        positions.put("joy", new Vector2(0.16f, 0.27f));
        positions.put("run", new Vector2(0.31f, 0.31f));
        positions.put("jump", new Vector2(0.80f, 0.35f));
        positions.put("attack", new Vector2(0.90f, 0.19f));
        positions.put("interact", new Vector2(0.67f, 0.23f));
        positions.put("enter", new Vector2(0.55f, 0.12f));
        // Kept off the top-right corner: it belongs to the minimap now. The two
        // secondary buttons sit under pause, on the left edge.
        positions.put("view", new Vector2(0.075f, 0.78f));
        positions.put("swap", new Vector2(0.075f, 0.64f));
        positions.put("pause", new Vector2(0.06f, 0.93f));
        positions.put("throttle", new Vector2(0.90f, 0.16f));
        positions.put("brake", new Vector2(0.75f, 0.16f));
        positions.put("left", new Vector2(0.10f, 0.24f));
        positions.put("right", new Vector2(0.26f, 0.15f));
        positions.put("handbrake", new Vector2(0.45f, 0.10f));
        positions.put("exit", new Vector2(0.62f, 0.12f));
        scale = 1f;
        alpha = 0.82f;
        leftHanded = false;
    }

    private static boolean near(Vector2 v, float x, float y) {
        return Math.abs(v.x - x) < 0.01f && Math.abs(v.y - y) < 0.01f;
    }

    public Vector2 pos(String id) {
        Vector2 v = positions.get(id);
        if (v == null) {
            v = new Vector2(0.5f, 0.5f);
            positions.put(id, v);
        }
        return v;
    }

    public static String[] onFootButtons() {
        return ON_FOOT;
    }

    public static String[] drivingButtons() {
        return DRIVING;
    }

    public void load() {
        try {
            Preferences p = Gdx.app.getPreferences("pta_controls");
            // Layout v2 moved CAMBIA/VISTA out from under the minimap. Only the
            // untouched old defaults are migrated, so a custom layout is preserved.
            if (p.getInteger("layoutVersion", 1) < 2) {
                Vector2 view = positions.get("view");
                Vector2 swap = positions.get("swap");
                if (view != null && near(view, 0.93f, 0.86f)) {
                    view.set(0.075f, 0.78f);
                }
                if (swap != null && near(swap, 0.79f, 0.90f)) {
                    swap.set(0.075f, 0.64f);
                }
                p.putInteger("layoutVersion", 2);
                p.flush();
            }
            for (String id : positions.keySet()) {
                String raw = p.getString(id, null);
                if (raw != null) {
                    String[] parts = raw.split(",");
                    if (parts.length == 2) {
                        positions.get(id).set(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]));
                    }
                }
            }
            scale = p.getFloat("scale", 1f);
            alpha = p.getFloat("alpha", 0.82f);
            leftHanded = p.getBoolean("leftHanded", false);
        } catch (Exception ignored) {
            // keep defaults when prefs are unavailable
        }
    }

    public void save() {
        try {
            Preferences p = Gdx.app.getPreferences("pta_controls");
            for (Map.Entry<String, Vector2> e : positions.entrySet()) {
                p.putString(e.getKey(), e.getValue().x + "," + e.getValue().y);
            }
            p.putFloat("scale", scale);
            p.putFloat("alpha", alpha);
            p.putBoolean("leftHanded", leftHanded);
            p.putInteger("layoutVersion", 2);
            p.flush();
        } catch (Exception ignored) {
            // ignore storage failures
        }
    }
}

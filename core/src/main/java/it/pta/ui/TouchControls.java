package it.pta.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import it.pta.game.InputState;
import it.pta.game.Settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Contextual touch HUD: virtual stick, camera drag, on-foot and driving buttons.
 * Fully rebindable: every button can be dragged, resized and made more transparent.
 */
public final class TouchControls extends Actor {
    private static final Map<String, String> LABELS = new HashMap<>();

    static {
        LABELS.put("run", "CORRI");
        LABELS.put("jump", "SALTA");
        LABELS.put("attack", "COLPO");
        LABELS.put("interact", "USA");
        LABELS.put("enter", "SALI");
        LABELS.put("view", "VISTA");
        LABELS.put("swap", "CAMBIA");
        LABELS.put("pause", "II");
        LABELS.put("throttle", "▲");
        LABELS.put("brake", "▼");
        LABELS.put("left", "◀");
        LABELS.put("right", "▶");
        LABELS.put("handbrake", "FRENO");
        LABELS.put("exit", "SCENDI");
        LABELS.put("joy", "STICK");
    }

    private final InputState input;
    private final ControlLayout layout;
    private final UiAssets assets;
    private final Settings settings;
    private final List<String> buttons = new ArrayList<>();
    private final Map<Integer, String> heldByPointer = new HashMap<>();
    private final Vector2 joyVec = new Vector2();
    private final Vector2 tmp = new Vector2();

    public boolean visible;
    public boolean driving;
    private int joyPointer = -1;
    private int camPointer = -1;
    private float lastCamX, lastCamY;
    private int editPointer = -1;
    private String dragged;
    private float dragOffX, dragOffY;
    private float joyRadius;
    private float buttonRadius;

    public TouchControls(InputState input, ControlLayout layout, UiAssets assets, Settings settings) {
        this.input = input;
        this.layout = layout;
        this.assets = assets;
        this.settings = settings;
        setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.enabled);
        addListener(new com.badlogic.gdx.scenes.scene2d.InputListener() {
            @Override
            public boolean touchDown(com.badlogic.gdx.scenes.scene2d.InputEvent event, float x, float y,
                                     int pointer, int button) {
                return handleDown(x, y, pointer);
            }

            @Override
            public void touchDragged(com.badlogic.gdx.scenes.scene2d.InputEvent event, float x, float y,
                                     int pointer) {
                handleDragged(x, y, pointer);
            }

            @Override
            public void touchUp(com.badlogic.gdx.scenes.scene2d.InputEvent event, float x, float y,
                                int pointer, int button) {
                handleUp(x, y, pointer);
            }
        });
        refreshButtons();
    }

    public void refreshButtons() {
        buttons.clear();
        for (String b : (driving ? ControlLayout.drivingButtons() : ControlLayout.onFootButtons())) {
            buttons.add(b);
        }
    }

    public void setMode(boolean driving) {
        if (this.driving != driving) {
            this.driving = driving;
            refreshButtons();
        }
    }

    public void setEditing(boolean editing) {
        layout.editing = editing;
        if (editing) {
            input.reset();
        }
    }

    public boolean isEditing() {
        return layout.editing;
    }

    private float buttonRadius() {
        return assets.dp(34f) * layout.scale * (settings.buttonScale > 0 ? settings.buttonScale : 1f);
    }

    private float joyRadiusFor() {
        return assets.dp(58f) * layout.scale * (settings.buttonScale > 0 ? settings.buttonScale : 1f);
    }

    public Vector2 buttonScreen(String id) {
        Vector2 p = layout.pos(id);
        tmp.set(p.x * getWidth(), p.y * getHeight());
        if (layout.leftHanded) {
            tmp.x = getWidth() - tmp.x;
        }
        return tmp;
    }

    private String hitButton(float x, float y) {
        buttonRadius = buttonRadius();
        for (String id : buttons) {
            Vector2 p = buttonScreen(id);
            float r = buttonRadius + assets.dp(8f);
            if (id.equals("enter") || id.equals("handbrake") || id.equals("exit")) {
                r = buttonRadius * 1.25f;
            }
            if (Vector2.dst(x, y, p.x, p.y) < r) {
                return id;
            }
        }
        return null;
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        if (!visible || getWidth() <= 0) {
            return;
        }
        float alpha = MathUtils.clamp(layout.alpha * (layout.editing ? 1f : 1f), 0.25f, 1f);
        buttonRadius = buttonRadius();
        joyRadius = joyRadiusFor();
        // joystick base and knob
        Vector2 joy = buttonScreen("joy");
        batch.setColor(0.06f, 0.09f, 0.13f, alpha * 0.55f);
        batch.draw(assets.circle, joy.x - joyRadius, joy.y - joyRadius, joyRadius * 2, joyRadius * 2);
        batch.setColor(0.65f, 0.78f, 0.92f, alpha * 0.35f);
        batch.draw(assets.circle, joy.x - joyRadius * 0.62f, joy.y - joyRadius * 0.62f,
                joyRadius * 1.24f, joyRadius * 1.24f);
        Vector2 knob = tmp.set(joy).add(joyVec.x * joyRadius * 0.55f, joyVec.y * joyRadius * 0.55f);
        batch.setColor(0.85f, 0.91f, 0.98f, alpha * 0.75f);
        batch.draw(assets.circle, knob.x - joyRadius * 0.34f, knob.y - joyRadius * 0.34f,
                joyRadius * 0.68f, joyRadius * 0.68f);

        for (String id : buttons) {
            Vector2 p = buttonScreen(id);
            float r = buttonRadius;
            boolean held = heldByPointer.containsValue(id);
            Color fill = held ? new Color(0.42f, 0.62f, 0.82f, alpha) : new Color(0.10f, 0.14f, 0.20f, alpha);
            if (id.equals("attack") || id.equals("throttle")) {
                fill = held ? new Color(0.85f, 0.55f, 0.22f, alpha) : new Color(0.20f, 0.15f, 0.10f, alpha);
            }
            batch.setColor(0.65f, 0.78f, 0.92f, alpha * 0.55f);
            batch.draw(assets.circle, p.x - r - assets.dp(2), p.y - r - assets.dp(2),
                    (r + assets.dp(2)) * 2, (r + assets.dp(2)) * 2);
            batch.setColor(fill);
            batch.draw(assets.circle, p.x - r, p.y - r, r * 2, r * 2);
            batch.setColor(Color.WHITE);
            String label = LABELS.containsKey(id) ? LABELS.get(id) : id.toUpperCase();
            if (layout.editing) {
                label = id.toUpperCase();
            }
            assets.small.setColor(0.95f, 0.97f, 1f, alpha);
            assets.small.getData().setScale(0.85f + 0.2f * layout.scale);
            com.badlogic.gdx.graphics.g2d.GlyphLayout gl = new com.badlogic.gdx.graphics.g2d.GlyphLayout();
            gl.setText(assets.small, label);
            assets.small.draw(batch, gl, p.x - gl.width * 0.5f, p.y + gl.height * 0.5f);
            assets.small.getData().setScale(1f);
        }
        if (layout.editing) {
            batch.setColor(0.03f, 0.05f, 0.08f, 0.55f);
            batch.draw(assets.white, 0, getHeight() - assets.dp(44), getWidth(), assets.dp(44));
            batch.setColor(Color.WHITE);
            assets.font.setColor(0.95f, 0.85f, 0.45f, 1f);
            assets.font.draw(batch, "MODIFICA CONTROLLI · trascina i pulsanti", assets.dp(20),
                    getHeight() - assets.dp(14));
        }
    }

    private boolean handleDown(float x, float y, int pointer) {
        if (!visible) {
            return false;
        }
        if (layout.editing) {
            String id = hitButton(x, y);
            if (id != null) {
                dragged = id;
                editPointer = pointer;
                Vector2 p = buttonScreen(id);
                dragOffX = p.x - x;
                dragOffY = p.y - y;
                return true;
            }
            return false;
        }
        if (layout.leftHanded) {
            x = getWidth() - x;
        }
        String id = hitButton(x, y);
        if (id != null) {
            heldByPointer.put(pointer, id);
            press(id);
            return true;
        }
        if (x < getWidth() * 0.43f && y < getHeight() * 0.62f) {
            joyPointer = pointer;
            joyVec.set(0, 0);
            return true;
        }
        camPointer = pointer;
        lastCamX = x;
        lastCamY = y;
        return true;
    }

    private void press(String id) {
        switch (id) {
            case "run":
                input.run = true;
                break;
            case "jump":
                input.jump = true;
                break;
            case "attack":
                input.attack = true;
                break;
            case "interact":
                input.interact = true;
                break;
            case "enter":
            case "exit":
                input.enterExit = true;
                break;
            case "view":
                input.switchView = true;
                break;
            case "swap":
                input.switchCharacter = true;
                break;
            case "pause":
                input.pause = true;
                break;
            case "throttle":
                input.throttle = true;
                break;
            case "brake":
                input.brake = true;
                break;
            case "left":
                input.steerLeft = true;
                break;
            case "right":
                input.steerRight = true;
                break;
            case "handbrake":
                input.handbrake = true;
                break;
            default:
                break;
        }
    }

    private void release(String id) {
        if (id == null) {
            return;
        }
        switch (id) {
            case "run":
                input.run = false;
                break;
            case "throttle":
                input.throttle = false;
                break;
            case "brake":
                input.brake = false;
                break;
            case "left":
                input.steerLeft = false;
                break;
            case "right":
                input.steerRight = false;
                break;
            case "handbrake":
                input.handbrake = false;
                break;
            default:
                break;
        }
    }

    private boolean handleDragged(float x, float y, int pointer) {
        if (!visible) {
            return false;
        }
        if (layout.editing) {
            if (pointer == editPointer && dragged != null) {
                float nx = (x + dragOffX) / Math.max(1f, getWidth());
                float ny = (y + dragOffY) / Math.max(1f, getHeight());
                if (layout.leftHanded) {
                    nx = 1f - nx;
                }
                layout.pos(dragged).set(MathUtils.clamp(nx, 0.04f, 0.96f), MathUtils.clamp(ny, 0.06f, 0.94f));
            }
            return true;
        }
        float lx = layout.leftHanded ? getWidth() - x : x;
        if (pointer == joyPointer) {
            Vector2 joy = buttonScreen("joy");
            float dx = lx - joy.x;
            float dy = y - joy.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            float max = joyRadius;
            if (len > max) {
                dx = dx / len * max;
                dy = dy / len * max;
                len = max;
            }
            float dead = max * 0.16f;
            if (len < dead) {
                joyVec.set(0, 0);
                input.moveX = 0;
                input.moveY = 0;
            } else {
                joyVec.set(dx / max, dy / max);
                input.moveX = joyVec.x;
                input.moveY = joyVec.y;
            }
            return true;
        }
        if (pointer == camPointer) {
            input.cameraDX += (x - lastCamX);
            input.cameraDY += (y - lastCamY);
            lastCamX = x;
            lastCamY = y;
            return true;
        }
        return false;
    }

    private void handleUp(float x, float y, int pointer) {
        if (pointer == joyPointer) {
            joyPointer = -1;
            joyVec.set(0, 0);
            input.moveX = 0;
            input.moveY = 0;
        }
        if (pointer == camPointer) {
            camPointer = -1;
        }
        if (pointer == editPointer) {
            editPointer = -1;
            dragged = null;
            layout.save();
        }
        String id = heldByPointer.remove(pointer);
        release(id);
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (layout.editing) {
            return;
        }
        if (!driving && joyPointer < 0) {
            input.moveX = 0;
            input.moveY = 0;
        }
    }
}

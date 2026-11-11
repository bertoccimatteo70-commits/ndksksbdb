package it.pta.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import it.pta.entity.Vehicle;
import it.pta.entity.VehicleType;
import it.pta.game.Content;
import it.pta.game.Economy;
import it.pta.game.InputState;
import it.pta.game.MissionSystem;
import it.pta.game.SaveManager;
import it.pta.game.Settings;
import it.pta.game.World;
import it.pta.world.MapData;

import java.util.ArrayList;
import java.util.List;

/** All menus, panels and the in-game HUD, built with Scene2D. */
public final class GameUi {
    public interface Callbacks {
        void newGame();

        void continueGame();

        void load(int slot);

        void save(int slot);

        void deleteSave(int slot);

        void resume();

        void toMenu();

        void quitGame();

        void settingsChanged();

        void startMission(String id);
    }

    public enum Panel {MENU, NONE, PAUSE, SETTINGS, MAP, SHOP, PROPERTY, MISSIONS, SAVES, CREDITS, OFFER, RESULT, LOADING, PERF}

    public final Stage stage = new Stage(new ScreenViewport());
    public final Hud hud;
    public final TouchControls controls;
    private final UiAssets a;
    private final Settings settings;
    private final SaveManager saves;
    private final InputState input;
    private final World world;
    private final Callbacks cb;
    private Panel panel = Panel.MENU;
    private boolean saveMode;
    private Content.Place currentPlace;
    private MissionSystem.Mission offer;
    private boolean resultWon;
    private Settings.Preset perfSuggestion;
    private String perfMessage = "";
    private String resultTitle = "";
    private String resultMessage = "";
    public String loadingText = "";

    public GameUi(UiAssets assets, Settings settings, SaveManager saves, InputState input,
                  World world, Callbacks callbacks) {
        this.a = assets;
        this.settings = settings;
        this.saves = saves;
        this.input = input;
        this.world = world;
        this.cb = callbacks;
        this.hud = new Hud(world, assets);
        this.controls = new TouchControls(input, world.layout, assets, settings);
        world.ui = this;
        rebuild();
    }

    public Panel panel() {
        return panel;
    }

    public boolean isGameplay() {
        return panel == Panel.NONE;
    }

    public void showMenu() {
        panel = Panel.MENU;
        hud.visible = false;
        controls.visible = false;
        controls.setEditing(false);
        rebuild();
    }

    public void showGameplay() {
        panel = Panel.NONE;
        hud.visible = true;
        controls.visible = true;
        controls.setEditing(world.layout.editing);
        world.audio.setTrack(world.police.wanted > 0 ? 2 : 1);
        rebuild();
    }

    public void showPause() {
        if (world.missions.dialogueActive) {
            world.missions.advanceDialogue();
            return;
        }
        panel = Panel.PAUSE;
        controls.visible = false;
        hud.visible = true;
        rebuild();
    }

    public void showSettings() {
        panel = Panel.SETTINGS;
        controls.visible = false;
        rebuild();
    }

    public void showMap() {
        panel = Panel.MAP;
        controls.visible = false;
        rebuild();
    }

    public void showMissions() {
        panel = Panel.MISSIONS;
        controls.visible = false;
        rebuild();
    }

    public void showSaves(boolean save) {
        saveMode = save;
        panel = Panel.SAVES;
        controls.visible = false;
        rebuild();
    }

    public void showShop(Content.Place place) {
        currentPlace = place;
        panel = Panel.SHOP;
        controls.visible = false;
        rebuild();
    }

    public void showProperty(Content.Place place) {
        currentPlace = place;
        panel = Panel.PROPERTY;
        controls.visible = false;
        rebuild();
    }

    public void showCredits() {
        panel = Panel.CREDITS;
        controls.visible = false;
        rebuild();
    }

    public void showOffer(MissionSystem.Mission m) {
        offer = m;
        panel = Panel.OFFER;
        controls.visible = false;
        rebuild();
    }

    public void showResult(boolean won, String title, String message) {
        resultWon = won;
        resultTitle = title;
        resultMessage = message;
        panel = Panel.RESULT;
        controls.visible = false;
        rebuild();
    }

    public void showPerformance(Settings.Preset suggestion, String message) {
        perfSuggestion = suggestion;
        perfMessage = message;
        panel = Panel.PERF;
        controls.visible = false;
        rebuild();
    }

    public void showLoading(String text) {
        loadingText = text;
        panel = Panel.LOADING;
        controls.visible = false;
        hud.visible = false;
        rebuild();
    }

    public void showMissionResult(boolean won, String title, String message) {
        if (won) {
            world.audio.missionComplete();
        } else {
            world.audio.fail();
        }
        showResult(won, title, message);
    }

    public void rebuild() {
        stage.clear();
        layoutActors();
        controls.setMode(world.player().inVehicle());
        stage.addActor(hud);
        stage.addActor(controls);
        if (panel == Panel.NONE) {
            return;
        }
        Table dim = new Table();
        dim.setFillParent(true);
        dim.setBackground(a.skin.newDrawable("white", new Color(0.015f, 0.025f, 0.045f, 0.72f)));
        stage.addActor(dim);
        Table root = new Table();
        root.setFillParent(true);
        root.pad(a.dp(24));
        stage.addActor(root);
        switch (panel) {
            case MENU:
                buildMenu(root);
                break;
            case PAUSE:
                buildPause(root);
                break;
            case SETTINGS:
                buildSettings(root);
                break;
            case MAP:
                buildMap(root);
                break;
            case MISSIONS:
                buildMissions(root);
                break;
            case SAVES:
                buildSaves(root);
                break;
            case SHOP:
                buildShop(root);
                break;
            case PROPERTY:
                buildProperty(root);
                break;
            case CREDITS:
                buildCredits(root);
                break;
            case OFFER:
                buildOffer(root);
                break;
            case RESULT:
                buildResult(root);
                break;
            case PERF:
                buildPerf(root);
                break;
            case LOADING:
                buildLoading(root);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------- menu

    private void buildMenu(Table root) {
        Table left = new Table();
        left.defaults().pad(a.dp(6)).left();
        if (a.logo != null && a.logo != a.white) {
            Image logo = new Image(a.logo);
            left.add(logo).size(a.dp(180), a.dp(180)).left().row();
        }
        Label title = new Label("PTA", a.skin, "huge");
        left.add(title).left().row();
        Label sub = new Label("PISTOIA TOWN ADVENTURES", a.skin, "title");
        left.add(sub).left().padBottom(a.dp(10)).row();
        Label tag = new Label("Open world 3D nella provincia di Pistoia", a.skin, "small");
        left.add(tag).left().padBottom(a.dp(16)).row();

        boolean hasSave = saves.newest() != null;
        left.add(button("NUOVA PARTITA", () -> {
            world.audio.uiClick();
            cb.newGame();
        })).width(a.dp(320)).height(a.dp(52)).padTop(a.dp(6)).row();
        TextButton cont = button("CONTINUA", () -> {
            world.audio.uiClick();
            cb.continueGame();
        });
        cont.setDisabled(!hasSave);
        left.add(cont).width(a.dp(320)).height(a.dp(48)).padTop(a.dp(6)).row();
        left.add(button("CARICA PARTITA", () -> {
            world.audio.uiClick();
            showSaves(false);
        })).width(a.dp(320)).height(a.dp(48)).padTop(a.dp(6)).row();
        left.add(button("IMPOSTAZIONI", () -> {
            world.audio.uiClick();
            showSettings();
        })).width(a.dp(320)).height(a.dp(48)).padTop(a.dp(6)).row();
        left.add(button("CREDITI", () -> {
            world.audio.uiClick();
            showCredits();
        })).width(a.dp(320)).height(a.dp(48)).padTop(a.dp(6)).row();
        left.add(button("ESCI", () -> {
            world.audio.uiClick();
            cb.quitGame();
        })).width(a.dp(320)).height(a.dp(48)).padTop(a.dp(6)).row();

        Table right = new Table();
        right.defaults().right();
        Label save = new Label(hasSave ? "Ultimo salvataggio:\n" + saves.newest().summary() : "Nessun salvataggio",
                a.skin, "small");
        save.setAlignment(Align.right);
        right.add(save).right().row();
        Label info = new Label("Mappa reale OpenStreetMap · ODbL\nDue protagonisti · 12 missioni\nVeicoli, polizia, economia, case",
                a.skin, "small");
        info.setAlignment(Align.right);
        right.add(info).padTop(a.dp(18)).right();

        root.top();
        root.add(left).expandX().left();
        root.add(right).expandX().right();
    }

    private void buildPause(Table root) {
        Table box = panelBox("PAUSA");
        TextButton resume = button("RIPRENDI", () -> {
            world.audio.uiClick();
            cb.resume();
        });
        box.add(resume).width(a.dp(330)).height(a.dp(50)).pad(a.dp(4)).row();
        box.add(button("MISSIONI", () -> {
            world.audio.uiClick();
            showMissions();
        })).width(a.dp(330)).height(a.dp(46)).pad(a.dp(4)).row();
        box.add(button("MAPPA", () -> {
            world.audio.uiClick();
            showMap();
        })).width(a.dp(330)).height(a.dp(46)).pad(a.dp(4)).row();
        box.add(button("SALVA PARTITA", () -> {
            world.audio.uiClick();
            showSaves(true);
        })).width(a.dp(330)).height(a.dp(46)).pad(a.dp(4)).row();
        box.add(button("IMPOSTAZIONI", () -> {
            world.audio.uiClick();
            showSettings();
        })).width(a.dp(330)).height(a.dp(46)).pad(a.dp(4)).row();
        box.add(button("TORNA AL MENU", () -> {
            world.audio.uiClick();
            cb.toMenu();
        })).width(a.dp(330)).height(a.dp(46)).pad(a.dp(4)).row();
        root.center();
        root.add(box);
    }

    private Table panelBox(String title) {
        Table box = new Table();
        box.setBackground(a.skin.getDrawable("panel9"));
        box.pad(a.dp(22));
        Label t = new Label(title, a.skin, "title");
        box.add(t).padBottom(a.dp(14)).row();
        return box;
    }

    // --------------------------------------------------------------- settings

    private void buildSettings(Table root) {
        Table box = panelBox("IMPOSTAZIONI");
        Table content = new Table();
        content.defaults().pad(a.dp(3)).left();

        Label presetLabel = new Label("Preset grafico: " + settings.preset.label, a.skin, "accent");
        box.add(presetLabel).left().row();
        Table presets = new Table();
        for (Settings.Preset p : Settings.Preset.values()) {
            String name = p.label + (p == Settings.Preset.ULTRA ? " 60" : "");
            presets.add(button(name, () -> {
                settings.applyPreset(p);
                settings.save();
                world.audio.uiClick();
                cb.settingsChanged();
                showSettings();
            })).width(a.dp(118)).height(a.dp(42)).pad(a.dp(3));
        }
        content.add(presets).colspan(2).row();
        Label presetInfo = new Label(settings.qualitySummary(), a.skin, "small");
        content.add(presetInfo).colspan(2).padBottom(a.dp(6)).row();

        content.add(new Label("Risoluzione interna", a.skin, "small")).width(a.dp(190));
        content.add(optionRow(new String[]{"480p", "720p", "900p", "1080p"}, new int[]{480, 720, 900, 1080},
                new Runnable[]{() -> settings.renderHeight = 480, () -> settings.renderHeight = 720,
                        () -> settings.renderHeight = 900, () -> settings.renderHeight = 1080},
                settings.renderHeight)).row();

        content.add(new Label("Limite FPS", a.skin, "small")).width(a.dp(190));
        content.add(optionRow(new String[]{"30", "60", "Libero"}, new int[]{30, 60, 0},
                new Runnable[]{() -> settings.fpsCap = 30, () -> settings.fpsCap = 60, () -> settings.fpsCap = 0},
                settings.fpsCap)).row();

        content.add(new Label("Ombre", a.skin, "small")).width(a.dp(190));
        content.add(optionRow(new String[]{"Off", "Basse", "Alte"}, new int[]{0, 1, 2},
                new Runnable[]{() -> settings.shadowQuality = 0, () -> settings.shadowQuality = 1,
                        () -> settings.shadowQuality = 2}, settings.shadowQuality)).row();

        content.add(new Label("Qualità texture", a.skin, "small")).width(a.dp(190));
        content.add(optionRow(new String[]{"Bassa", "Media", "Alta"}, new int[]{0, 1, 2},
                new Runnable[]{() -> settings.textureQuality = 0, () -> settings.textureQuality = 1,
                        () -> settings.textureQuality = 2}, settings.textureQuality)).row();

        content.add(new Label("Distanza visuale", a.skin, "small")).width(a.dp(190));
        content.add(slider(500f, 1900f, settings.viewDistance, v -> settings.viewDistance = v, "m")).row();
        content.add(new Label("Traffico", a.skin, "small")).width(a.dp(190));
        content.add(slider(0.2f, 1f, settings.trafficDensity, v -> settings.trafficDensity = v, "")).row();
        content.add(new Label("Effetti", a.skin, "small")).width(a.dp(190));
        content.add(slider(0f, 1f, settings.effects, v -> settings.effects = v, "")).row();

        content.add(new Label("Antialiasing FXAA", a.skin, "small")).width(a.dp(190));
        content.add(toggle(settings.fxaa, v -> settings.fxaa = v)).row();
        content.add(new Label("Mostra FPS", a.skin, "small")).width(a.dp(190));
        content.add(toggle(settings.showFps, v -> settings.showFps = v)).row();

        content.add(new Label("Musica", a.skin, "small")).width(a.dp(190));
        content.add(slider(0f, 1f, settings.musicVolume, v -> settings.musicVolume = v, "")).row();
        content.add(new Label("Effetti sonori", a.skin, "small")).width(a.dp(190));
        content.add(slider(0f, 1f, settings.sfxVolume, v -> settings.sfxVolume = v, "")).row();
        content.add(new Label("Ambiente", a.skin, "small")).width(a.dp(190));
        content.add(slider(0f, 1f, settings.ambientVolume, v -> settings.ambientVolume = v, "")).row();
        content.add(new Label("Dialoghi", a.skin, "small")).width(a.dp(190));
        content.add(slider(0f, 1f, settings.dialogueVolume, v -> settings.dialogueVolume = v, "")).row();

        content.add(new Label("Dimensione pulsanti", a.skin, "small")).width(a.dp(190));
        content.add(slider(0.7f, 1.6f, world.layout.scale, v -> {
            world.layout.scale = v;
            world.layout.save();
        }, "x")).row();
        content.add(new Label("Trasparenza pulsanti", a.skin, "small")).width(a.dp(190));
        content.add(slider(0.25f, 1f, world.layout.alpha, v -> {
            world.layout.alpha = v;
            world.layout.save();
        }, "")).row();
        content.add(new Label("Mancino", a.skin, "small")).width(a.dp(190));
        content.add(toggle(world.layout.leftHanded, v -> {
            world.layout.leftHanded = v;
            world.layout.save();
        })).row();

        Table controlButtons = new Table();
        controlButtons.add(button(world.layout.editing ? "MODIFICA ATTIVA" : "MODIFICA LAYOUT", () -> {
            world.layout.editing = !world.layout.editing;
            world.audio.uiClick();
            if (world.layout.editing) {
                showGameplay();
            } else {
                showSettings();
            }
        })).height(a.dp(42)).pad(a.dp(3));
        controlButtons.add(button("RIPRISTINA", () -> {
            world.layout.reset();
            world.layout.save();
            world.audio.uiClick();
            showSettings();
        })).height(a.dp(42)).pad(a.dp(3));
        content.add(controlButtons).colspan(2).padTop(a.dp(6)).row();

        ScrollPane scroll = new ScrollPane(content, a.skin);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);
        box.add(scroll).height(a.dp(330)).width(a.dp(600)).row();
        box.add(button("INDIETRO", () -> {
            settings.save();
            world.audio.setVolumes(settings.musicVolume, settings.sfxVolume,
                    settings.ambientVolume, settings.dialogueVolume);
            cb.settingsChanged();
            world.audio.uiClick();
            if (world.missions.active != null || world.playTime > 0) {
                showPause();
            } else {
                showMenu();
            }
        })).width(a.dp(240)).height(a.dp(46)).padTop(a.dp(10)).row();
        root.center();
        root.add(box);
    }

    private interface FloatConsumer {
        void accept(float v);
    }

    private interface BoolConsumer {
        void accept(boolean v);
    }

    private interface IntConsumer {
        void accept(int v);
    }

    private Table slider(float min, float max, float value, FloatConsumer setter, String suffix) {
        Table t = new Table();
        Slider s = new Slider(min, max, (max - min) / 40f, false, a.skin);
        s.setValue(value);
        Label l = new Label(fmt(value) + suffix, a.skin, "small");
        s.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                setter.accept(s.getValue());
                l.setText(fmt(s.getValue()) + suffix);
            }
        });
        t.add(s).width(a.dp(220));
        t.add(l).width(a.dp(70)).padLeft(a.dp(6));
        return t;
    }

    private String fmt(float v) {
        return String.format("%.1f", v);
    }

    private TextButton toggle(boolean value, BoolConsumer setter) {
        TextButton b = new TextButton(value ? "SÌ" : "NO", a.skin);
        b.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                boolean nv = !b.getText().toString().equals("SÌ");
                setter.accept(nv);
                b.setText(nv ? "SÌ" : "NO");
                world.audio.uiClick();
            }
        });
        return b;
    }

    private Table optionRow(String[] labels, int[] values, Runnable[] actions, int current) {
        Table t = new Table();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextButton b = new TextButton(labels[i], a.skin, values[i] == current ? "default" : "flat");
            b.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    actions[index].run();
                    world.audio.uiClick();
                    showSettings();
                }
            });
            t.add(b).height(a.dp(38)).pad(a.dp(2)).width(a.dp(84));
        }
        return t;
    }

    // -------------------------------------------------------------------- map

    private void buildMap(Table root) {
        // Full-screen map: the map actor fills the whole panel area, controls float
        // over it. It only reads the 2D spatial index, never the 3D chunk meshes.
        root.clear();
        com.badlogic.gdx.scenes.scene2d.ui.Stack stack =
                new com.badlogic.gdx.scenes.scene2d.ui.Stack();
        MapView view = new MapView();
        stack.add(view);

        Table overlay = new Table();
        overlay.top();
        Table header = new Table();
        header.add(new Label("MAPPA DELLA PROVINCIA", a.skin, "accent")).expandX().left().pad(a.dp(12));
        header.add(button("CHIUDI", () -> {
            world.audio.uiClick();
            showPause();
        })).size(a.dp(120), a.dp(40)).pad(a.dp(8));
        overlay.add(header).growX().top().row();

        Table legend = new Table();
        legend.add(swatch(new Color(0.74f, 0.79f, 0.86f, 1f))).size(a.dp(14)).padRight(a.dp(4));
        legend.add(new Label("strade", a.skin, "small")).padRight(a.dp(12));
        legend.add(swatch(new Color(0.46f, 0.42f, 0.38f, 1f))).size(a.dp(14)).padRight(a.dp(4));
        legend.add(new Label("edifici", a.skin, "small")).padRight(a.dp(12));
        legend.add(swatch(new Color(0.24f, 0.38f, 0.28f, 1f))).size(a.dp(14)).padRight(a.dp(4));
        legend.add(new Label("aree", a.skin, "small")).padRight(a.dp(12));
        legend.add(swatch(new Color(0.10f, 0.95f, 1f, 1f))).size(a.dp(14)).padRight(a.dp(4));
        legend.add(new Label("GPS", a.skin, "small")).padRight(a.dp(12));
        legend.add(swatch(new Color(1f, 0.62f, 0.15f, 1f))).size(a.dp(14)).padRight(a.dp(4));
        legend.add(new Label("tu", a.skin, "small"));
        overlay.add(legend).growX().left().padLeft(a.dp(12)).row();

        overlay.add().expand().row();
        Table bottom = new Table();
        bottom.add(new Label("Pizzica per zoomare, trascina per spostarti", a.skin, "small"))
                .expandX().left().pad(a.dp(8)).row();
        Table buttons = new Table();
        buttons.add(button("CENTRA SU DI ME", () -> {
            world.audio.uiClick();
            view.recenter();
        })).size(a.dp(200), a.dp(44)).pad(a.dp(4));
        buttons.add(button("TUTTA LA CITTÀ", () -> {
            world.audio.uiClick();
            view.fitToCity();
        })).size(a.dp(200), a.dp(44)).pad(a.dp(4));
        bottom.add(buttons).growX().left().row();
        overlay.add(bottom).growX().bottom().pad(a.dp(6));
        stack.add(overlay);
        root.add(stack).grow();
    }

    private com.badlogic.gdx.scenes.scene2d.ui.Image swatch(Color color) {
        return new Image(a.skin.newDrawable("white", color));
    }

    /**
     * Full-screen province map. The view keeps a world-space centre and a pixels-per-
     * metre scale: zoom is a pinch or a button, pan is a drag, and both buttons recompute
     * the visible extract from the 2D index. Nothing here touches 3D chunk meshes, so
     * opening the map cannot pull the city geometry into RAM.
     */
    private final class MapView extends Actor {
        private final List<float[]> roads = new ArrayList<>();
        private final List<float[]> areaOutlines = new ArrayList<>();
        private final List<float[]> buildingRects = new ArrayList<>();
        private final List<float[]> pois = new ArrayList<>();
        private final float[][] poiColors = {
                {0.95f, 0.45f, 0.35f}, {0.95f, 0.75f, 0.30f}, {0.45f, 0.85f, 0.55f},
                {0.40f, 0.75f, 0.95f}, {0.72f, 0.55f, 0.95f}, {0.90f, 0.90f, 0.90f}
        };
        private float scale = 0f;
        private float fitScale = 1f;
        private float centerX, centerZ;
        private boolean ready;
        private float cacheCX = Float.NaN, cacheCZ, cacheScale;
        private float lastPinch = -1f;
        private float dragLastX, dragLastY;
        private boolean dragging;

        MapView() {
            setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.enabled);
            addListener(new com.badlogic.gdx.scenes.scene2d.InputListener() {
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                    dragging = true;
                    dragLastX = x;
                    dragLastY = y;
                    return true;
                }

                @Override
                public void touchDragged(InputEvent event, float x, float y, int pointer) {
                    if (!dragging || Gdx.input.isTouched(1) || scale <= 0f) {
                        return;
                    }
                    centerX -= (x - dragLastX) / scale;
                    centerZ += (y - dragLastY) / scale;
                    dragLastX = x;
                    dragLastY = y;
                    clampCenter();
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                    dragging = false;
                }

                @Override
                public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY) {
                    zoomBy(amountY > 0f ? 1f / 1.25f : 1.25f);
                    return true;
                }
            });
        }

        @Override
        public void act(float delta) {
            super.act(delta);
            if (Gdx.input.isTouched(0) && Gdx.input.isTouched(1)) {
                float d = (float) Math.hypot(Gdx.input.getX(1) - Gdx.input.getX(0),
                        Gdx.input.getY(1) - Gdx.input.getY(0));
                if (lastPinch > 0f && d > 1f) {
                    zoomBy(d / lastPinch);
                }
                lastPinch = d;
            } else {
                lastPinch = -1f;
            }
        }

        /** Fit to bounds: the whole available province in the available area. */
        void fitToCity() {
            float w = getWidth(), h = getHeight();
            if (w <= 1f || h <= 1f) {
                return;
            }
            float mapW = Math.max(1f, world.map.maxX - world.map.minX);
            float mapH = Math.max(1f, world.map.maxZ - world.map.minZ);
            fitScale = Math.min(w / mapW, h / mapH) * 0.94f;
            scale = fitScale;
            centerX = (world.map.minX + world.map.maxX) * 0.5f;
            centerZ = (world.map.minZ + world.map.maxZ) * 0.5f;
            rebuildCache();
        }

        void recenter() {
            if (fitScale <= 0f) {
                fitToCity();
            }
            centerX = world.player().x;
            centerZ = world.player().z;
            if (scale < fitScale * 2.5f) {
                scale = fitScale * 4f;
            }
            clampCenter();
            rebuildCache();
        }

        void zoomBy(float factor) {
            if (fitScale <= 0f) {
                fitToCity();
            }
            scale = Math.max(fitScale, Math.min(fitScale * 45f, scale * factor));
            clampCenter();
            rebuildCache();
        }

        private void clampCenter() {
            centerX = MathUtils.clamp(centerX, world.map.minX, world.map.maxX);
            centerZ = MathUtils.clamp(centerZ, world.map.minZ, world.map.maxZ);
        }

        private float sx(float worldX) {
            return getX() + getWidth() * 0.5f + (worldX - centerX) * scale;
        }

        private float sy(float worldZ) {
            return getY() + getHeight() * 0.5f - (worldZ - centerZ) * scale;
        }

        private void rebuildCache() {
            float w = getWidth(), h = getHeight();
            if (w <= 1f || h <= 1f || scale <= 0f) {
                return;
            }
            cacheCX = centerX;
            cacheCZ = centerZ;
            cacheScale = scale;
            float margin = 1.18f;
            float halfW = w * 0.5f / scale * margin;
            float halfH = h * 0.5f / scale * margin;
            final float minX = centerX - halfW;
            final float maxX = centerX + halfW;
            final float minZ = centerZ - halfH;
            final float maxZ = centerZ + halfH;
            roads.clear();
            final int limit = scale > fitScale * 6f ? 12000 : 6000;
            world.map.forEachRoadSegment(minX, minZ, maxX, maxZ,
                    (x0, z0, x1, z1, drivable) -> {
                        roads.add(new float[]{x0, z0, x1, z1, drivable ? 1f : 0f});
                        return roads.size() < limit;
                    }, limit);
            areaOutlines.clear();
            buildingRects.clear();
            pois.clear();
            int c0 = world.map.cx(minX), c1 = world.map.cx(maxX);
            int r0 = world.map.cz(minZ), r1 = world.map.cz(maxZ);
            boolean wantBuildings = scale > fitScale * 2.2f;
            boolean wantPois = scale > fitScale * 1.4f;
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    if (areaOutlines.size() < 220) {
                        for (int idx : world.map.areasIn(c, r)) {
                            MapData.Area area = world.map.area(idx);
                            if (area.radius < 22f) {
                                continue;
                            }
                            areaOutlines.add(area.pts);
                            if (areaOutlines.size() >= 220) {
                                break;
                            }
                        }
                    }
                    if (wantBuildings && buildingRects.size() < 4000) {
                        for (int idx : world.map.buildingsIn(c, r)) {
                            MapData.Building b = world.map.building(idx);
                            if (b.maxX < minX || b.minX > maxX || b.maxZ < minZ || b.minZ > maxZ) {
                                continue;
                            }
                            buildingRects.add(new float[]{b.minX, b.minZ, b.maxX - b.minX,
                                    b.maxZ - b.minZ, b.type == MapData.Building.HISTORIC ? 1f : 0f});
                            if (buildingRects.size() >= 4000) {
                                break;
                            }
                        }
                    }
                    if (wantPois && pois.size() < 500) {
                        for (int idx : world.map.poisIn(c, r)) {
                            MapData.Poi p = world.map.poi(idx);
                            pois.add(new float[]{p.x, p.z, p.cat});
                            if (pois.size() >= 500) {
                                break;
                            }
                        }
                    }
                }
            }
        }

        private void line(Batch batch, float x0, float y0, float x1, float y1, float thick) {
            float dx = x1 - x0, dy = y1 - y0;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 0.3f) {
                return;
            }
            batch.draw(a.whiteRegion, x0, y0 - thick * 0.5f, 0, thick * 0.5f, len, thick,
                    1f, 1f, (float) Math.toDegrees(Math.atan2(dy, dx)));
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float w = getWidth(), h = getHeight();
            if (w <= 1f || h <= 1f) {
                return;
            }
            if (!ready) {
                fitToCity();
                ready = true;
            }
            if (Float.isNaN(cacheCX) || Math.abs(scale - cacheScale) > 1e-7f
                    || Math.hypot(centerX - cacheCX, centerZ - cacheCZ) * scale
                    > Math.min(w, h) * 0.22f) {
                rebuildCache();
            }
            float dp = Math.max(0.6f, a.density);
            batch.setColor(0.020f, 0.040f, 0.060f, 1f);
            batch.draw(a.white, getX(), getY(), w, h);
            // Province bounds, so the frame of the map is visible at every zoom.
            batch.setColor(0.075f, 0.115f, 0.155f, 1f);
            float bx = sx(world.map.minX);
            float by = sy(world.map.maxZ);
            batch.draw(a.white, bx, by, (world.map.maxX - world.map.minX) * scale,
                    (world.map.maxZ - world.map.minZ) * scale);

            for (float[] pts : areaOutlines) {
                batch.setColor(0.22f, 0.34f, 0.26f, 0.55f);
                for (int i = 0; i + 3 < pts.length; i += 2) {
                    line(batch, sx(pts[i]), sy(pts[i + 1]), sx(pts[i + 2]), sy(pts[i + 3]),
                            0.8f * dp);
                }
            }
            for (float[] r : roads) {
                boolean drivable = r[4] > 0.5f;
                float thick = (drivable ? Math.max(1.3f, Math.min(4.5f, scale * 8f))
                        : Math.max(0.7f, Math.min(2f, scale * 3f))) * dp;
                batch.setColor(drivable ? 0.76f : 0.44f, drivable ? 0.81f : 0.49f,
                        drivable ? 0.88f : 0.56f, drivable ? 0.95f : 0.68f);
                line(batch, sx(r[0]), sy(r[1]), sx(r[2]), sy(r[3]), thick);
            }
            for (float[] b : buildingRects) {
                float x = sx(b[0]), y = sy(b[1]);
                if (x < getX() - 20f || x > getX() + w + 20f || y < getY() - 20f
                        || y > getY() + h + 20f) {
                    continue;
                }
                float bw = Math.max(1.2f, b[2] * scale);
                float bh = Math.max(1.2f, b[3] * scale);
                batch.setColor(b[4] > 0.5f ? 0.55f : 0.45f, b[4] > 0.5f ? 0.48f : 0.42f,
                        b[4] > 0.5f ? 0.40f : 0.39f, 0.92f);
                batch.draw(a.white, x, y - bh, bw, bh);
            }
            for (float[] p : pois) {
                float[] col = poiColors[(int) Math.abs(p[2]) % poiColors.length];
                batch.setColor(col[0], col[1], col[2], 0.95f);
                float x = sx(p[0]) - 2.4f * dp;
                float y = sy(p[1]) - 2.4f * dp;
                batch.draw(a.circle, x, y, 4.8f * dp, 4.8f * dp);
            }
            // The exact route of the 3D ribbon, drawn from the same array.
            float[] route = world.gpsRoute;
            if (route != null && route.length >= 4) {
                batch.setColor(0.02f, 0.10f, 0.14f, 0.9f);
                for (int i = 0; i + 3 < route.length; i += 2) {
                    line(batch, sx(route[i]), sy(route[i + 1]), sx(route[i + 2]),
                            sy(route[i + 3]), 5.4f * dp);
                }
                batch.setColor(0.10f, 0.95f, 1f, 1f);
                for (int i = 0; i + 3 < route.length; i += 2) {
                    line(batch, sx(route[i]), sy(route[i + 1]), sx(route[i + 2]),
                            sy(route[i + 3]), 2.8f * dp);
                }
            }
            if (world.missions.markerActive) {
                float dx = sx(world.missions.markerX);
                float dy = sy(world.missions.markerZ);
                batch.setColor(0.98f, 0.80f, 0.25f, 1f);
                batch.draw(a.circle, dx - 7f * dp, dy - 7f * dp, 14f * dp, 14f * dp);
                batch.setColor(0.10f, 0.08f, 0.02f, 1f);
                batch.draw(a.circle, dx - 3f * dp, dy - 3f * dp, 6f * dp, 6f * dp);
            }
            for (Content.Place p : Content.all()) {
                if (p.kind != Content.Kind.LANDMARK) {
                    continue;
                }
                float x = sx(p.x), y = sy(p.z);
                if (x < getX() || x > getX() + w || y < getY() || y > getY() + h) {
                    continue;
                }
                batch.setColor(0.95f, 0.78f, 0.30f, 1f);
                batch.draw(a.circle, x - 3f * dp, y - 3f * dp, 6f * dp, 6f * dp);
                a.small.setColor(0.90f, 0.94f, 0.98f, 0.95f);
                a.small.draw(batch, p.label, x + 6f * dp, y + 4f * dp);
            }
            // Player: a triangle rotated by the real heading, not a line.
            float px = sx(world.player().x);
            float py = sy(world.player().z);
            float size = 22f * dp;
            batch.setColor(1f, 0.62f, 0.15f, 0.28f);
            batch.draw(a.circle, px - 14f * dp, py - 14f * dp, 28f * dp, 28f * dp);
            batch.setColor(1f, 0.66f, 0.18f, 1f);
            batch.draw(a.triangleRegion, px - size * 0.5f, py - size * 0.5f, size * 0.5f,
                    size * 0.5f, size, size, 1f, 1f,
                    -(float) Math.toDegrees(world.player().heading));
            batch.setColor(Color.WHITE);
            if (world.gpsAvailable) {
                a.small.setColor(0.75f, 0.95f, 1f, 1f);
                String turn = world.gpsNextTurnSide > 0 ? "destra"
                        : world.gpsNextTurnSide < 0 ? "sinistra" : "dritto";
                a.small.draw(batch, "Percorso " + (int) world.gpsDistance + " m · " + turn
                        + " tra " + (int) world.gpsNextTurnDistance + " m",
                        getX() + 14f * dp, getY() + 96f * dp);
            }
        }
    }

    // --------------------------------------------------------------- missions

    private void buildMissions(Table root) {
        Table box = panelBox("MISSIONI");
        Table list = new Table();
        list.defaults().pad(a.dp(3)).left();
        if (world.missions.active != null) {
            list.add(new Label("In corso: " + world.missions.active.title, a.skin, "accent"))
                    .colspan(3).left().row();
            list.add(button("ABBANDONA", () -> {
                world.missions.abort();
                world.audio.uiClick();
                showMissions();
            })).height(a.dp(38)).row();
        }
        for (MissionSystem.Mission m : world.missions.missions) {
            boolean done = m.side ? world.missions.completedSide.contains(m.id)
                    : world.missions.completed.contains(m.id);
            boolean locked = !m.requires.isEmpty() && !world.missions.completed.contains(m.requires);
            Label title = new Label((m.side ? "◆ " : "● ") + m.title, a.skin,
                    done ? "small" : locked ? "small" : "default");
            list.add(title).width(a.dp(280));
            Label state = new Label(done ? "Completata" : locked ? "Bloccata" : "€ " + Economy.format(m.reward),
                    a.skin, "small");
            list.add(state).width(a.dp(130));
            TextButton start = new TextButton(done ? "—" : "AVVIA", a.skin);
            start.setDisabled(done || locked);
            start.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    if (!start.isDisabled()) {
                        world.audio.uiClick();
                        cb.startMission(m.id);
                    }
                }
            });
            list.add(start).width(a.dp(100)).height(a.dp(36)).row();
        }
        ScrollPane scroll = new ScrollPane(list, a.skin);
        scroll.setFadeScrollBars(false);
        box.add(scroll).width(a.dp(640)).height(a.dp(320)).row();
        box.add(button("CHIUDI", () -> {
            world.audio.uiClick();
            showPause();
        })).width(a.dp(220)).height(a.dp(44)).padTop(a.dp(8)).row();
        root.center();
        root.add(box);
    }

    // ------------------------------------------------------------------ saves

    private void buildSaves(Table root) {
        Table box = panelBox(saveMode ? "SALVA PARTITA" : "CARICA PARTITA");
        for (int slot = 0; slot <= SaveManager.SLOTS; slot++) {
            final int slotIndex = slot;
            SaveManager.SaveData d = saves.load(slot);
            Table row = new Table();
            String name = slot == 0 ? "AUTOSAVE" : "SLOT " + slot;
            row.add(new Label(name, a.skin, "accent")).width(a.dp(130)).left();
            Label info = new Label(d == null ? "vuoto" : d.summary(), a.skin, "small");
            row.add(info).width(a.dp(330)).left();
            if (saveMode && slot > 0) {
                row.add(button("SALVA", () -> {
                    cb.save(slotIndex);
                    world.audio.uiClick();
                    showSaves(true);
                })).width(a.dp(100)).height(a.dp(38));
            } else if (!saveMode) {
                TextButton load = button("CARICA", () -> {
                    cb.load(slotIndex);
                    world.audio.uiClick();
                });
                load.setDisabled(d == null);
                row.add(load).width(a.dp(100)).height(a.dp(38));
            }
            if (d != null && slot > 0) {
                row.add(button("ELIMINA", () -> {
                    cb.deleteSave(slotIndex);
                    world.audio.uiClick();
                    showSaves(saveMode);
                })).width(a.dp(110)).height(a.dp(38)).padLeft(a.dp(4));
            }
            box.add(row).pad(a.dp(4)).row();
        }
        box.add(button("INDIETRO", () -> {
            world.audio.uiClick();
            if (world.playTime > 0 && !saveMode) {
                showPause();
            } else {
                showMenu();
            }
        })).width(a.dp(220)).height(a.dp(44)).padTop(a.dp(10)).row();
        root.center();
        root.add(box);
    }

    // ------------------------------------------------------------------- shop

    private void buildShop(Table root) {
        Content.Place p = currentPlace;
        Table box = panelBox(p == null ? "NEGOZIO" : p.label);
        Table list = new Table();
        list.defaults().pad(a.dp(3)).left();
        if (p != null && p.kind == Content.Kind.DEALER) {
            for (VehicleType t : VehicleType.values()) {
                if (!t.buyable()) {
                    continue;
                }
                list.add(new Label(t.label + " · " + t.categoryName(), a.skin)).width(a.dp(280)).left();
                list.add(new Label("€ " + Economy.format(t.price), a.skin, "small")).width(a.dp(120));
                TextButton buy = new TextButton("ACQUISTA", a.skin);
                buy.setDisabled(world.economy.cash < t.price);
                buy.addListener(new ClickListener() {
                    @Override
                    public void clicked(InputEvent event, float x, float y) {
                        if (world.economy.buyVehicle(t, 0, "garage", world.playTime)) {
                            Vehicle v = world.vehicles.spawn(t, Content.px("garage") + 8f,
                                    Content.pz("garage") + 8f, 0.4f);
                            v.isPlayerOwned = true;
                            v.parked = true;
                            world.ownedVehicles.add(v);
                            world.audio.cash();
                            world.notify("Acquistato: " + t.label);
                            showShop(p);
                        } else {
                            world.notify("Soldi insufficienti");
                        }
                    }
                });
                list.add(buy).width(a.dp(120)).height(a.dp(36)).row();
            }
        } else if (p != null && p.kind == Content.Kind.TUNING) {
            for (Vehicle v : world.ownedVehicles) {
                list.add(new Label(v.type.label + " · " + Vehicle.plateFor(v.hashCode()), a.skin))
                        .width(a.dp(220)).left();
                Table paints = new Table();
                for (int i = 0; i < Vehicle.PAINTS.length; i++) {
                    final int idx = i;
                    TextButton sw = new TextButton("", a.skin);
                    sw.getLabel().setColor(new Color(Vehicle.PAINTS[i][0], Vehicle.PAINTS[i][1],
                            Vehicle.PAINTS[i][2], 1f));
                    sw.addListener(new ClickListener() {
                        @Override
                        public void clicked(InputEvent event, float x, float y) {
                            if (world.economy.spend(350, "Verniciatura", world.playTime)) {
                                v.setPaint(idx);
                                world.audio.cash();
                                world.notify("Verniciatura applicata");
                            } else {
                                world.notify("Servono €350");
                            }
                        }
                    });
                    paints.add(sw).size(a.dp(34)).pad(a.dp(2));
                }
                list.add(paints).width(a.dp(240));
                list.add(button("RIPARA €" + (int) (v.damage * 6), () -> {
                    int cost = Math.max(40, (int) (v.damage * 6));
                    if (world.economy.spend(cost, "Riparazione", world.playTime)) {
                        v.repair();
                        world.audio.cash();
                        world.notify("Veicolo riparato");
                    } else {
                        world.notify("Soldi insufficienti");
                    }
                })).width(a.dp(130)).height(a.dp(34)).row();
                Table rims = new Table();
                rims.add(new Label("Cerchioni:", a.skin, "small")).padRight(a.dp(4));
                for (int i = 0; i < Vehicle.RIMS.length; i++) {
                    final int rim = i;
                    rims.add(button("C" + (i + 1), () -> {
                        if (world.economy.spend(200, "Cerchioni", world.playTime)) {
                            v.rimIndex = rim;
                            world.audio.cash();
                            world.notify("Cerchioni montati");
                        } else {
                            world.notify("Servono €200");
                        }
                    })).width(a.dp(52)).height(a.dp(32)).pad(a.dp(2));
                }
                list.add(rims).colspan(3).left().row();
            }
            if (world.ownedVehicles.isEmpty()) {
                list.add(new Label("Nessun veicolo di proprietà.", a.skin, "small")).row();
            }
        } else if (p != null && p.kind == Content.Kind.BUSINESS) {
            boolean owned = world.economy.owns(p.id);
            list.add(new Label("Attività: " + p.label, a.skin)).row();
            list.add(new Label("Prezzo € " + Economy.format(p.price) + " · incasso giornaliero € "
                    + Economy.format(p.income), a.skin, "small")).row();
            if (owned) {
                list.add(new Label("Di tua proprietà. Incasso disponibile: € "
                        + Economy.format(world.economy.dailyIncome()), a.skin, "small")).row();
                list.add(button("RISCUOTI INCASSI", () -> {
                    world.economy.collectDailyIncome(world.playTime);
                    world.audio.cash();
                    world.notify("Incassi riscossi");
                    showShop(p);
                })).height(a.dp(40)).row();
            } else {
                list.add(button("ACQUISTA ATTIVITÀ", () -> {
                    if (world.economy.buyProperty(p, world.playTime)) {
                        world.audio.cash();
                        world.notify("Attività acquistata: " + p.label);
                        showShop(p);
                    } else {
                        world.notify("Soldi insufficienti");
                    }
                })).height(a.dp(40)).row();
            }
        } else {
            list.add(new Label("Articoli", a.skin, "accent")).row();
            list.add(button("Panino €8 · +30 salute", () -> {
                if (world.economy.spend(8, "Panino", world.playTime)) {
                    world.player().heal(30);
                    world.audio.cash();
                    world.notify("Salute ripristinata");
                }
            })).width(a.dp(330)).height(a.dp(40)).row();
            list.add(button("Giubbotto €450 · +50 armatura", () -> {
                if (world.economy.spend(450, "Giubbotto", world.playTime)) {
                    world.player().armor = Math.min(100, world.player().armor + 50);
                    world.audio.cash();
                    world.notify("Armatura acquistata");
                }
            })).width(a.dp(330)).height(a.dp(40)).row();
            list.add(button("Kit riparazione €120", () -> {
                if (world.economy.spend(120, "Kit riparazione", world.playTime)) {
                    if (world.player().vehicle != null) {
                        world.player().vehicle.repair();
                    }
                    world.audio.cash();
                    world.notify("Kit acquistato");
                }
            })).width(a.dp(330)).height(a.dp(40)).row();
        }
        ScrollPane scroll = new ScrollPane(list, a.skin);
        scroll.setFadeScrollBars(false);
        box.add(scroll).width(a.dp(640)).height(a.dp(300)).row();
        Label cash = new Label("Contanti: € " + Economy.format(world.economy.cash), a.skin, "small");
        box.add(cash).padTop(a.dp(6)).row();
        box.add(button("CHIUDI", () -> {
            world.audio.uiClick();
            showGameplay();
        })).width(a.dp(220)).height(a.dp(44)).padTop(a.dp(6)).row();
        root.center();
        root.add(box);
    }

    private void buildProperty(Table root) {
        Content.Place p = currentPlace;
        Table box = panelBox(p == null ? "PROPRIETÀ" : p.label);
        if (p != null) {
            Label desc = new Label(p.kind == Content.Kind.HOME
                    ? "Abitazione acquistabile con interno visitabile e punto di salvataggio."
                    : "Attività commerciale con incasso giornaliero.", a.skin, "small");
            desc.setWrap(true);
            box.add(desc).width(a.dp(520)).row();
            box.add(new Label("Prezzo: € " + Economy.format(p.price), a.skin, "accent"))
                    .padTop(a.dp(8)).row();
            if (p.income > 0) {
                box.add(new Label("Incasso: € " + Economy.format(p.income) + " al giorno", a.skin, "small")).row();
            }
            box.add(button("ACQUISTA", () -> {
                if (world.economy.buyProperty(p, world.playTime)) {
                    world.audio.cash();
                    world.notify("Acquistato: " + p.label);
                    showGameplay();
                } else {
                    world.notify("Soldi insufficienti");
                }
            })).width(a.dp(220)).height(a.dp(46)).padTop(a.dp(10)).row();
        }
        box.add(button("CHIUDI", () -> {
            world.audio.uiClick();
            showGameplay();
        })).width(a.dp(220)).height(a.dp(40)).padTop(a.dp(6)).row();
        root.center();
        root.add(box);
    }

    private void buildCredits(Table root) {
        Table box = panelBox("CREDITI");
        String text = "PTA — Pistoia Town Adventures\n"
                + "Gioco open world 3D originale, sviluppato per Android.\n\n"
                + "Mappa: dati © OpenStreetMap contributors, licenza ODbL.\n"
                + "Quote: OpenStreetMap Foundation. https://www.openstreetmap.org/copyright\n"
                + "Altimetria: Mapzen / AWS Terrain Tiles (public domain).\n\n"
                + "Motore: libGDX 1.12.1, rendering OpenGL ES 2.0 proprietario.\n"
                + "Grafica, personaggi, veicoli, musica ed effetti: generati proceduralmente\n"
                + "per questo progetto. Nessun asset di terze parti o marchio registrato.\n\n"
                + "Personaggi: Marco Bellandi, Elisa Vannucci.\n"
                + "Ambientazione: provincia di Pistoia, Toscana.\n\n"
                + "Versione 1.0.5 — campagna in 5 capitoli, 12 missioni principali + 2 secondarie.";
        Label label = new Label(text, a.skin, "small");
        label.setWrap(true);
        label.setAlignment(Align.center);
        box.add(label).width(a.dp(620)).pad(a.dp(8)).row();
        box.add(button("CHIUDI", () -> {
            world.audio.uiClick();
            showMenu();
        })).width(a.dp(220)).height(a.dp(44)).padTop(a.dp(8)).row();
        root.center();
        root.add(box);
    }

    private void buildOffer(Table root) {
        Table box = panelBox(offer == null ? "MISSIONE" : offer.title);
        if (offer != null) {
            Label desc = new Label(offer.side ? "Missione secondaria" : "Capitolo " + offer.chapter,
                    a.skin, "small");
            box.add(desc).row();
            String first = offer.intro.isEmpty() ? "" : offer.intro.get(0).text;
            Label quote = new Label("\"" + first + "\"", a.skin, "small");
            quote.setWrap(true);
            quote.setAlignment(Align.center);
            box.add(quote).width(a.dp(520)).pad(a.dp(8)).row();
            box.add(new Label("Ricompensa: € " + Economy.format(offer.reward), a.skin, "accent"))
                    .padBottom(a.dp(8)).row();
            Table buttons = new Table();
            buttons.add(button("ACCETTA", () -> {
                world.audio.uiClick();
                cb.startMission(offer.id);
            })).width(a.dp(200)).height(a.dp(46)).pad(a.dp(4));
            buttons.add(button("PIÙ TARDI", () -> {
                world.audio.uiClick();
                showGameplay();
            })).width(a.dp(200)).height(a.dp(46)).pad(a.dp(4));
            box.add(buttons).row();
        }
        root.center();
        root.add(box);
    }

    private void buildResult(Table root) {
        Table box = panelBox(resultWon ? "MISSIONE COMPLETATA" : "MISSIONE FALLITA");
        Label msg = new Label(resultTitle + "\n\n" + resultMessage, a.skin, "default");
        msg.setWrap(true);
        msg.setAlignment(Align.center);
        box.add(msg).width(a.dp(520)).pad(a.dp(10)).row();
        box.add(button(resultWon ? "CONTINUA" : "RIPROVA", () -> {
            world.audio.uiClick();
            if (!resultWon && world.missions.active != null && world.missions.failed) {
                world.missions.restart();
            }
            showGameplay();
        })).width(a.dp(240)).height(a.dp(46)).row();
        root.center();
        root.add(box);
    }

    private void buildPerf(Table root) {
        Table box = panelBox("PRESTAZIONI");
        Label msg = new Label(perfMessage, a.skin, "small");
        msg.setWrap(true);
        msg.setAlignment(Align.center);
        box.add(msg).width(a.dp(520)).pad(a.dp(8)).row();
        if (perfSuggestion != null) {
            box.add(button("USA PRESET " + perfSuggestion.label, () -> {
                settings.applyPreset(perfSuggestion);
                settings.save();
                cb.settingsChanged();
                world.audio.uiClick();
                showGameplay();
            })).width(a.dp(300)).height(a.dp(46)).padTop(a.dp(8)).row();
        }
        box.add(button("CONTINUA COMUNQUE", () -> {
            world.audio.uiClick();
            showGameplay();
        })).width(a.dp(300)).height(a.dp(42)).padTop(a.dp(6)).row();
        root.center();
        root.add(box);
    }

    private void buildLoading(Table root) {
        Table box = new Table();
        box.add(new Label("PTA", a.skin, "huge")).row();
        box.add(new Label(loadingText, a.skin, "default")).padTop(a.dp(10)).row();
        root.center();
        root.add(box);
    }

    private TextButton button(String text, Runnable action) {
        TextButton b = new TextButton(text, a.skin);
        b.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                action.run();
            }
        });
        return b;
    }

    public void act(float delta) {
        stage.act(delta);
    }

    public void draw() {
        stage.draw();
    }

    public void resize(int w, int h) {
        stage.getViewport().update(w, h, true);
        layoutActors();
    }

    private void layoutActors() {
        float w = stage.getViewport().getWorldWidth();
        float h = stage.getViewport().getWorldHeight();
        if (w <= 0 || h <= 0) {
            w = Gdx.graphics.getWidth();
            h = Gdx.graphics.getHeight();
        }
        hud.setBounds(0, 0, w, h);
        controls.setBounds(0, 0, w, h);
    }
}

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
        Table box = panelBox("MAPPA DELLA PROVINCIA");
        MapView view = new MapView();
        box.add(view).size(a.dp(620), a.dp(320)).row();
        Label legend = new Label("Pistoia · Agliana · Montale · Quarrata · Casalguidi · Serravalle · Montecatini · Pescia",
                a.skin, "small");
        box.add(legend).padTop(a.dp(6)).row();
        Table places = new Table();
        int count = 0;
        for (Content.Place p : Content.all()) {
            if (p.kind == Content.Kind.LANDMARK || p.kind == Content.Kind.HOME
                    || p.kind == Content.Kind.DEALER || p.kind == Content.Kind.BUSINESS) {
                Label l = new Label((world.economy.owns(p.id) ? "✓ " : "") + p.label, a.skin, "small");
                places.add(l).left().pad(a.dp(2)).width(a.dp(190));
                count++;
                if (count % 3 == 0) {
                    places.row();
                }
            }
        }
        box.add(places).padTop(a.dp(4)).row();
        box.add(button("CHIUDI", () -> {
            world.audio.uiClick();
            showPause();
        })).width(a.dp(220)).height(a.dp(44)).padTop(a.dp(8)).row();
        root.center();
        root.add(box);
    }

    private final class MapView extends Actor {
        private final List<float[]> roads = new ArrayList<>();

        MapView() {
            float px = world.player().x;
            float pz = world.player().z;
            world.map.forEachRoadSegment(px - 3000, pz - 3000, px + 3000, pz + 3000,
                    (x0, z0, x1, z1, drivable) -> {
                        roads.add(new float[]{x0, z0, x1, z1});
                        return roads.size() < 900;
                    }, 900);
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            batch.setColor(0.035f, 0.055f, 0.075f, 0.96f);
            batch.draw(a.white, getX(), getY(), getWidth(), getHeight());
            float scale = Math.min(getWidth(), getHeight()) / 5600f;
            float cx = getX() + getWidth() * 0.5f;
            float cy = getY() + getHeight() * 0.5f;
            float px = world.player().x;
            float pz = world.player().z;
            for (float[] r : roads) {
                float x0 = cx + (r[0] - px) * scale;
                float y0 = cy - (r[1] - pz) * scale;
                float x1 = cx + (r[2] - px) * scale;
                float y1 = cy - (r[3] - pz) * scale;
                float dx = x1 - x0, dy = y1 - y0;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 0.5f) {
                    continue;
                }
                batch.setColor(0.70f, 0.76f, 0.84f, 0.55f);
                batch.draw(a.whiteRegion, x0, y0 - 1.2f, 0, 1.2f, len, 2.4f, 1f, 1f,
                        (float) Math.toDegrees(Math.atan2(dy, dx)));
            }
            for (Content.Place p : Content.all()) {
                if (p.kind != Content.Kind.LANDMARK && p.kind != Content.Kind.HOME
                        && p.kind != Content.Kind.DEALER && p.kind != Content.Kind.BUSINESS) {
                    continue;
                }
                float x = cx + (p.x - px) * scale;
                float y = cy - (p.z - pz) * scale;
                if (x < getX() || x > getX() + getWidth() || y < getY() || y > getY() + getHeight()) {
                    continue;
                }
                batch.setColor(p.kind == Content.Kind.LANDMARK ? new Color(0.95f, 0.78f, 0.30f, 1f)
                        : new Color(0.55f, 0.80f, 0.95f, 1f));
                batch.draw(a.circle, x - 4, y - 4, 8, 8);
                a.small.setColor(0.90f, 0.94f, 0.98f, 1f);
                a.small.draw(batch, p.label, x + 6, y + 4);
            }
            batch.setColor(1f, 0.35f, 0.30f, 1f);
            float x = cx + (world.player().x - px) * scale;
            float y = cy - (world.player().z - pz) * scale;
            batch.draw(a.circle, x - 5, y - 5, 10, 10);
            batch.setColor(Color.WHITE);
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

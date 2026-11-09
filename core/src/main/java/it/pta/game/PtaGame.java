package it.pta.game;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import it.pta.audio.AudioEngine;
import it.pta.entity.CameraRig;
import it.pta.entity.Player;
import it.pta.ui.GameUi;
import it.pta.ui.IntroCinematic;
import it.pta.ui.UiAssets;
import it.pta.world.MapData;

/**
 * Entry point and game loop: menu flythrough, cinematic intro, gameplay orchestration,
 * saves, settings, performance monitoring and Android lifecycle handling.
 */
public final class PtaGame extends ApplicationAdapter implements World.Listener, GameUi.Callbacks {
    private enum Mode {INTRO, MENU, PLAY}

    private MapData map;
    private it.pta.render.WorldRenderer renderer;
    private Settings settings;
    private AudioEngine audio;
    private InputState input;
    private SaveManager saves;
    private World world;
    private UiAssets assets;
    private GameUi ui;
    private IntroCinematic intro;
    private CameraRig rig;
    private SpriteBatch batch;
    private Mode mode = Mode.MENU;
    private float menuAngle;
    private float offerTimer;
    private float perfTime;
    private int perfFrames;
    private boolean perfWarned;
    private boolean campaignFinished;

    @Override
    public void create() {
        Gdx.app.setLogLevel(Application.LOG_INFO);
        settings = Settings.load();
        byte[] mapBytes = Gdx.files.internal("map/pta_map.bin").readBytes();
        map = MapData.load(mapBytes);
        String missionsJson = Gdx.files.internal("data/missions.json").readString("UTF-8");
        String landmarksJson = Gdx.files.internal("map/landmarks.json").readString("UTF-8");
        renderer = new it.pta.render.WorldRenderer(map, settings.renderHeight);
        assets = new UiAssets();
        audio = new AudioEngine();
        input = new InputState();
        saves = new SaveManager(Gdx.files.local("saves/"));
        world = new World(renderer, settings, audio, input, missionsJson, landmarksJson);
        world.setListener(this);
        rig = new CameraRig((float) Gdx.graphics.getWidth() / Math.max(1, Gdx.graphics.getHeight()));
        rig.setAspect((float) Gdx.graphics.getWidth() / Math.max(1, Gdx.graphics.getHeight()));
        ui = new GameUi(assets, settings, saves, input, world, this);
        intro = new IntroCinematic(assets, map);
        batch = new SpriteBatch();
        applySettings();
        Gdx.input.setCatchKey(Input.Keys.BACK, true);
        Gdx.input.setInputProcessor(null);
        mode = Mode.INTRO;
        audio.setTrack(AudioEngine.MENU);
        audio.setIndoors(false);
        menuAngle = 0.7f;
        renderer.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    @Override
    public void render() {
        float delta = Math.min(Gdx.graphics.getDeltaTime(), 0.05f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        if (mode == Mode.INTRO) {
            intro.update(delta, rig.camera);
            if (intro.finished || Gdx.input.justTouched()) {
                enterMenu();
            }
        } else if (mode == Mode.MENU) {
            updateMenuCamera(delta);
            ui.act(delta);
        } else {
            if (ui.isGameplay() && !world.layout.editing) {
                updateGameplay(delta);
            } else {
                if (input.cameraDX != 0 || input.cameraDY != 0) {
                    rig.rotate(input.cameraDX * settings.cameraSensitivity,
                            input.cameraDY * settings.cameraSensitivity);
                    input.cameraDX = 0;
                    input.cameraDY = 0;
                }
                input.endFrame();
                updateCamera(delta);
            }
            ui.act(delta);
        }

        renderScene(delta);

        if (mode == Mode.INTRO) {
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            batch.getProjectionMatrix().setToOrtho2D(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            batch.begin();
            intro.drawOverlay(batch, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            batch.end();
        } else {
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthMask(false);
            ui.draw();
        }
        monitorPerformance(delta);
    }

    private void renderScene(float delta) {
        renderer.beginFrame(rig.camera, delta);
        renderer.drawWorld(rig.camera);
        if (mode == Mode.PLAY) {
            world.render(rig);
        }
        renderer.finishFrame(rig.camera);
    }

    private void enterMenu() {
        mode = Mode.MENU;
        intro.skip();
        Gdx.input.setInputProcessor(ui.stage);
        ui.showMenu();
        audio.setTrack(AudioEngine.MENU);
        menuAngle = 0.7f;
    }

    private void updateMenuCamera(float delta) {
        menuAngle += delta * 0.055f;
        Content.Place d = Content.get("duomo");
        if (d == null) {
            return;
        }
        float radius = 185f;
        float ground = map.heightAt(d.x, d.z);
        rig.camera.position.set(d.x + MathUtils.cos(menuAngle) * radius, ground + 72f,
                d.z + MathUtils.sin(menuAngle) * radius);
        rig.camera.lookAt(d.x, ground + 24f, d.z);
        rig.camera.up.set(Vector3.Y);
        rig.camera.update();
    }

    private void updateGameplay(float delta) {
        pollHardware();
        if (input.pause) {
            input.pause = false;
            if (ui.isGameplay()) {
                ui.showPause();
            } else if (ui.panel() == GameUi.Panel.PAUSE) {
                ui.showGameplay();
            }
            return;
        }
        if (input.map) {
            input.map = false;
            ui.showMap();
            return;
        }
        if (input.switchView) {
            input.switchView = false;
            rig.toggleFirstPerson();
        }
        if (input.cameraDX != 0 || input.cameraDY != 0) {
            rig.rotate(input.cameraDX, input.cameraDY);
        }
        world.update(delta, rig.yaw);
        input.endFrame();
        updateCamera(delta);

        if (offerTimer > 0) {
            offerTimer -= delta;
            if (offerTimer <= 0 && ui.isGameplay() && world.missions.active == null) {
                MissionSystem.Mission next = world.missions.nextMainMission();
                if (next != null) {
                    ui.showOffer(next);
                } else if (!campaignFinished) {
                    campaignFinished = true;
                    ui.showResult(true, "Campagna completata",
                            "Hai concluso la storia di Marco ed Elisa. La provincia resta aperta: "
                                    + "esplora, guida e accetta le missioni secondarie dal menu.");
                }
            }
        }
        if (world.layout.editing && ui.isGameplay()) {
            // keep controls visible while repositioning, but freeze the world
        }
    }

    private void updateCamera(float delta) {
        Player p = world.player();
        boolean inVehicle = p.inVehicle();
        boolean indoor = world.insideInterior();
        float vy = inVehicle ? p.vehicle.y : p.y;
        rig.distance = indoor ? 2.4f : 5.2f;
        float ground = indoor ? p.y : map.heightAt(p.x, p.z);
        rig.update(p.x, vy, p.z, ground, inVehicle,
                inVehicle ? p.vehicle.type.length : 0f, indoor ? null : map, delta);
    }

    private void monitorPerformance(float delta) {
        if (mode != Mode.PLAY) {
            return;
        }
        perfTime += delta;
        perfFrames++;
        if (perfTime < 6f) {
            return;
        }
        float fps = perfFrames / perfTime;
        int target = settings.fpsCap <= 0 ? 60 : settings.fpsCap;
        if (fps < target * 0.62f && settings.preset != Settings.Preset.BASE && !perfWarned
                && ui.isGameplay()) {
            perfWarned = true;
            Settings.Preset lower = Settings.Preset.values()[Math.max(0, settings.preset.ordinal() - 1)];
            ui.showPerformance(lower, "Il dispositivo non sta sostenendo il preset "
                    + settings.preset.label + " (" + (int) fps + " FPS su " + target
                    + " attesi). Consigliato il preset " + lower.label
                    + ": risoluzione " + lower.renderHeight + "p e distanza ridotta.");
        }
        perfTime = 0;
        perfFrames = 0;
    }

    private void pollHardware() {
        float mx = 0;
        float my = 0;
        if (Gdx.input.isKeyPressed(Input.Keys.W) || Gdx.input.isKeyPressed(Input.Keys.UP)) {
            my += 1;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.S) || Gdx.input.isKeyPressed(Input.Keys.DOWN)) {
            my -= 1;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.A) || Gdx.input.isKeyPressed(Input.Keys.LEFT)) {
            mx -= 1;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT)) {
            mx += 1;
        }
        if (mx != 0 || my != 0) {
            input.moveX = mx;
            input.moveY = my;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)) {
            input.run = true;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            input.jump = true;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.R)) {
            input.attack = true;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.E)) {
            input.interact = true;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.F)) {
            input.enterExit = true;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.V)) {
            input.switchView = true;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.C)) {
            input.switchCharacter = true;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) {
            input.map = true;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) || Gdx.input.isKeyJustPressed(Input.Keys.BACK)) {
            input.pause = true;
        }
        // external controller support
        for (com.badlogic.gdx.controllers.Controller c :
                com.badlogic.gdx.controllers.Controllers.getControllers()) {
            if (!c.isConnected()) {
                continue;
            }
            com.badlogic.gdx.controllers.ControllerMapping map = c.getMapping();
            float ax = map.axisLeftX >= 0 ? c.getAxis(map.axisLeftX) : 0f;
            float ay = map.axisLeftY >= 0 ? c.getAxis(map.axisLeftY) : 0f;
            if (Math.abs(ax) > 0.22f || Math.abs(ay) > 0.22f) {
                input.moveX = ax;
                input.moveY = -ay;
            }
            if (map.buttonR2 >= 0 && c.getButton(map.buttonR2)) {
                input.throttle = true;
            }
            if (map.buttonL2 >= 0 && c.getButton(map.buttonL2)) {
                input.brake = true;
            }
            if (map.buttonL1 >= 0 && c.getButton(map.buttonL1)) {
                input.run = true;
            }
            if (map.buttonA >= 0 && c.getButton(map.buttonA)) {
                input.jump = true;
            }
            if (map.buttonX >= 0 && c.getButton(map.buttonX)) {
                input.attack = true;
            }
            if (map.buttonY >= 0 && c.getButton(map.buttonY)) {
                input.interact = true;
            }
            if (map.buttonB >= 0 && c.getButton(map.buttonB)) {
                input.enterExit = true;
            }
            if (map.buttonStart >= 0 && c.getButton(map.buttonStart)) {
                input.pause = true;
            }
            if (map.buttonDpadLeft >= 0 && c.getButton(map.buttonDpadLeft)) {
                input.steerLeft = true;
            }
            if (map.buttonDpadRight >= 0 && c.getButton(map.buttonDpadRight)) {
                input.steerRight = true;
            }
        }
    }

    public void applySettings() {
        renderer.configure(settings.renderHeight, settings.viewDistance, settings.fxaa);
        world.traffic.maxCars = Math.max(4, Math.round(16f * settings.trafficDensity));
        world.traffic.maxPeds = Math.max(6, Math.round(28f * settings.trafficDensity));
        world.layout.scale = MathUtils.clamp(world.layout.scale, 0.7f, 1.6f);
        audio.setVolumes(settings.musicVolume, settings.sfxVolume,
                settings.ambientVolume, settings.dialogueVolume);
        rig.sensitivity = settings.cameraSensitivity;
        Gdx.graphics.setForegroundFPS(settings.fpsCap <= 0 ? 0 : settings.fpsCap);
    }

    // ------------------------------------------------------------- UI callbacks

    @Override
    public void newGame() {
        world.resetProgress();
        mode = Mode.PLAY;
        campaignFinished = false;
        offerTimer = 0;
        rig.setFirstPerson(settings.firstPersonDefault);
        ui.showGameplay();
        audio.setTrack(AudioEngine.EXPLORE);
        MissionSystem.Mission first = world.missions.nextMainMission();
        if (first != null) {
            world.startMission(first);
        }
        updateCamera(0.016f);
    }

    @Override
    public void continueGame() {
        int slot = saves.newestSlot();
        if (slot >= 0) {
            load(slot);
        } else {
            newGame();
        }
    }

    @Override
    public void load(int slot) {
        SaveManager.SaveData data = saves.load(slot);
        if (data == null) {
            world.notify("Salvataggio non disponibile");
            return;
        }
        world.readSave(data);
        mode = Mode.PLAY;
        ui.showGameplay();
        rig.setFirstPerson(settings.firstPersonDefault);
        rig.yaw = 0;
        input.reset();
        updateCamera(0.016f);
        audio.setTrack(AudioEngine.EXPLORE);
        world.notify("Partita caricata");
    }

    @Override
    public void save(int slot) {
        SaveManager.SaveData data = new SaveManager.SaveData();
        world.writeSave(data);
        data.introSeen = true;
        if (saves.save(slot, data)) {
            world.notify(slot == 0 ? "Salvataggio automatico" : "Partita salvata nello slot " + slot);
        } else {
            world.notify("Salvataggio non riuscito");
        }
    }

    @Override
    public void deleteSave(int slot) {
        saves.delete(slot);
    }

    @Override
    public void resume() {
        if (mode == Mode.PLAY) {
            ui.showGameplay();
        }
    }

    @Override
    public void toMenu() {
        if (mode == Mode.PLAY) {
            save(0);
        }
        mode = Mode.MENU;
        campaignFinished = false;
        ui.showMenu();
        audio.setTrack(AudioEngine.MENU);
        menuAngle = 0.7f;
    }

    @Override
    public void quitGame() {
        if (mode == Mode.PLAY) {
            save(0);
        }
        Gdx.app.exit();
    }

    @Override
    public void settingsChanged() {
        applySettings();
    }

    @Override
    public void startMission(String id) {
        MissionSystem.Mission m = world.missions.mission(id);
        if (m == null) {
            return;
        }
        mode = Mode.PLAY;
        offerTimer = 0;
        world.startMission(m);
        ui.showGameplay();
        updateCamera(0.016f);
    }

    // ---------------------------------------------------------- world callbacks

    @Override
    public void onNotify(String text) {
    }

    @Override
    public void onOpenShop(Content.Place place) {
        ui.showShop(place);
    }

    @Override
    public void onOpenProperty(Content.Place place) {
        ui.showProperty(place);
    }

    @Override
    public void onRequestSave() {
        save(0);
    }

    @Override
    public void onBusted() {
        ui.showResult(false, "Arrestato",
                "La polizia ti ha fermato. Hai pagato la cauzione e hai perso il livello di ricercato.");
    }

    @Override
    public void onWasted() {
        ui.showResult(false, "Fuori combattimento",
                "Sei stato portato in ospedale. Hai perso parte dei contanti, ma puoi riprovare.");
    }

    @Override
    public void onCampaignProgress() {
        offerTimer = 2.4f;
    }

    @Override
    public void onMissionFailed(MissionSystem.Mission mission, String reason) {
        if (mission != null) {
            ui.showResult(false, mission.title, reason);
        }
    }

    // ------------------------------------------------------------------ Android

    @Override
    public void resize(int width, int height) {
        if (renderer == null) {
            return;
        }
        renderer.resize(width, height);
        if (rig != null) {
            rig.setAspect((float) width / Math.max(1, height));
        }
        if (ui != null) {
            ui.resize(width, height);
        }
        if (batch != null) {
            batch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        }
    }

    @Override
    public void pause() {
        if (mode == Mode.PLAY) {
            save(0);
        }
    }

    @Override
    public void dispose() {
        if (world != null) {
            world.dispose();
        }
        if (ui != null) {
            ui.stage.dispose();
        }
        if (assets != null) {
            assets.dispose();
        }
        if (audio != null) {
            audio.dispose();
        }
        if (renderer != null) {
            renderer.dispose();
        }
        if (batch != null) {
            batch.dispose();
        }
    }
}

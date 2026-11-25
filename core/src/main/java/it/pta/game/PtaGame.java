package it.pta.game;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Align;
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
    private int debugFrames;
    private boolean perfWarned;
    private boolean campaignFinished;
    private Throwable fatal;
    private SpriteBatch fatalBatch;
    private BitmapFont fatalFont;
    private String fatalMessage = "";
    private final MemoryBudget memory = new MemoryBudget();
    private boolean loading;
    private boolean loadingNewGame;
    private float loadingTime;
    private float degradeCooldown;
    private float memoryLogTimer;
    private boolean smokeTest;
    private float smokeTime;
    private int oomRecoveries;

    /** Enables the automated CI walkthrough (see AndroidLauncher). */
    public void setSmokeTest(boolean value) {
        smokeTest = value;
    }

    @Override
    public void create() {
        Gdx.app.setLogLevel(Application.LOG_INFO);
        try {
            initGame();
            Gdx.app.log("PTA", "startup complete");
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void initGame() {
        Gdx.app.log("PTA", "startup: settings");
        settings = Settings.load();
        Gdx.app.log("PTA", memory.phase("after settings"));
        Gdx.app.log("PTA", "startup: loading map");
        com.badlogic.gdx.files.FileHandle mapAsset = Gdx.files.internal("map/pta_map.bin");
        com.badlogic.gdx.files.FileHandle mapLocal = Gdx.files.local("world/pta_map.bin");
        if (!mapLocal.exists() || mapLocal.length() != mapAsset.length()) {
            mapLocal.parent().mkdirs();
            mapAsset.copyTo(mapLocal);
        }
        map = MapData.open(mapLocal.file());
        Gdx.app.log("PTA", "startup: map loaded buildings=" + map.buildingCount
                + " roads=" + map.roadCount + " areas=" + map.areaCount
                + " pois=" + map.poiCount);
        Gdx.app.log("PTA", memory.phase("after map index"));
        String missionsJson = Gdx.files.internal("data/missions.json").readString("UTF-8");
        String landmarksJson = Gdx.files.internal("map/landmarks.json").readString("UTF-8");
        Gdx.app.log("PTA", "startup: renderer");
        renderer = new it.pta.render.WorldRenderer(map, settings.renderHeight);
        renderer.chunkManager().setMemoryBudget(memory.chunkBudgetBytes());
        Gdx.app.log("PTA", memory.phase("after renderer"));
        Gdx.app.log("PTA", "startup: ui assets");
        assets = new UiAssets();
        Gdx.app.log("PTA", memory.phase("after ui assets"));
        Gdx.app.log("PTA", "startup: audio");
        audio = new AudioEngine();
        input = new InputState();
        saves = new SaveManager(Gdx.files.local("saves/"));
        Gdx.app.log("PTA", "startup: world");
        world = new World(renderer, settings, audio, input, missionsJson, landmarksJson);
        world.setListener(this);
        Gdx.app.log("PTA", memory.phase("after world"));
        rig = new CameraRig((float) Gdx.graphics.getWidth() / Math.max(1, Gdx.graphics.getHeight()));
        rig.setAspect((float) Gdx.graphics.getWidth() / Math.max(1, Gdx.graphics.getHeight()));
        Gdx.app.log("PTA", "startup: menu");
        ui = new GameUi(assets, settings, saves, input, world, this);
        intro = new IntroCinematic(assets, map);
        batch = new SpriteBatch();
        applySettings();
        Gdx.app.log("PTA", memory.phase("after menu"));
        Gdx.app.log("PTA", "startup: intro");
        Gdx.input.setCatchKey(Input.Keys.BACK, true);
        Gdx.input.setInputProcessor(null);
        mode = Mode.INTRO;
        audio.setTrack(AudioEngine.MENU);
        audio.setIndoors(false);
        menuAngle = 0.7f;
        renderer.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        if (smokeTest) {
            Gdx.app.log("PTA", "smoke: starting an automated new game");
            newGame();
        }
    }

    @Override
    public void render() {
        if (fatal != null) {
            drawFatal();
            return;
        }
        try {
            renderFrame();
        } catch (OutOfMemoryError oom) {
            recoverFromOutOfMemory(oom);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * Last-resort handler: instead of dying on a small allocation, release every piece
     * of streamed geometry the streamer can rebuild and drop to the cheapest preset.
     * If the heap is still exhausted after a few attempts the fatal screen is shown,
     * so the failure is visible instead of an endless recovery loop.
     */
    private void recoverFromOutOfMemory(OutOfMemoryError oom) {
        oomRecoveries++;
        Gdx.app.error("PTA", "OutOfMemoryError: releasing streamed geometry (attempt "
                + oomRecoveries + ") " + memory.summary(), oom);
        if (oomRecoveries > 3 || renderer == null || rig == null) {
            fail(oom);
            return;
        }
        renderer.chunkManager().trim(rig.camera.position, 9);
        map.releaseAllChunks();
        if (settings.preset != Settings.Preset.BASE) {
            settings.applyPreset(Settings.Preset.BASE);
            applySettings();
        }
        System.gc();
    }

    private void renderFrame() {
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

        // The loading screen is driven from the render loop: the world keeps streaming
        // and uploading chunks every frame, so the bar follows real work instead of
        // blocking the GL thread on a synchronous preload.
        if (mode == Mode.PLAY && loading) {
            advanceLoading(delta);
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
        updateMemory(delta);
    }

    private void renderScene(float delta) {
        // Streams the city around the current camera. This must run on the GL thread:
        // chunk workers only build data, while uploadFinished() creates the meshes here.
        // The camera direction feeds the predictive, direction-biased chunk requests.
        renderer.chunkManager().setFocus(rig.camera.direction.x, rig.camera.direction.z);
        renderer.chunkManager().update(rig.camera.position, delta);
        renderer.beginFrame(rig.camera, delta);
        renderer.drawWorld(rig.camera);
        if (mode == Mode.PLAY) {
            world.render(rig);
        }
        renderer.finishFrame(rig.camera);
        if (mode == Mode.PLAY && debugFrames < 6) {
            debugFrames++;
            Gdx.app.log("PTA", "render: triangles=" + renderer.lastTriangles
                    + " drawnChunks=" + renderer.lastDrawnChunks
                    + " loadedChunks=" + renderer.chunkManager().loadedChunks()
                    + " " + renderer.chunkManager().stats()
                    + " sun=" + renderer.sun.hours
                    + " camera=" + rig.camera.position
                    + " cameraBlocked=" + map.blocked(rig.camera.position.x, rig.camera.position.z, 0.4f));
        }
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
        if (smokeTest) {
            driveSmoke(delta);
        }
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

    /**
     * CI walkthrough: runs a curve at walking/running speed so the streamer loads and
     * evicts zones, LOD switches and NPC collision keep running for minutes. Nothing
     * here is compiled into a normal session (the flag comes from the intent extra).
     */
    private void driveSmoke(float delta) {
        smokeTime += delta;
        input.moveY = 1f;
        input.run = smokeTime < 90f;
        rig.yaw += delta * (0.09f + 0.05f * MathUtils.sin(smokeTime * 0.35f));
        // One progress line per minute: a long session must be auditable from logcat,
        // including the heap after every minute of real streaming.
        if ((int) (smokeTime / 60f) > (int) ((smokeTime - delta) / 60f)) {
            Player p = world.player();
            Gdx.app.log("PTA", "smoke: t=" + (int) smokeTime + "s player=" + p.x + "," + p.z
                    + " " + renderer.chunkManager().stats() + " " + memory.summary());
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

    /**
     * Memory watchdog. When the heap approaches the budget the game first drops the
     * farthest chunks (and their CPU geometry), then lowers the graphics preset: the
     * playing field stays identical, only what is resident shrinks.
     */
    private void updateMemory(float delta) {
        MemoryBudget.Pressure pressure = memory.sample(delta);
        memory.chunkVertexMb = renderer.chunkManager().vertexMemoryBytes() / 1048576f;
        memoryLogTimer += delta;
        if (memoryLogTimer > 20f && mode == Mode.PLAY) {
            memoryLogTimer = 0f;
            Gdx.app.log("PTA", "runtime: " + memory.summary() + " "
                    + renderer.chunkManager().stats() + " fps=" + Gdx.graphics.getFramesPerSecond()
                    + " preset=" + settings.preset.label);
        }
        // The menu and the opening cinematic stream the province too, so the trim has
        // to run there as well: the old build only reacted during gameplay, which is
        // exactly why the heap could fill up before the player ever pressed Play.
        if (pressure == MemoryBudget.Pressure.OK) {
            return;
        }
        if (degradeCooldown > 0f) {
            degradeCooldown -= delta;
            return;
        }
        degradeCooldown = 6f;
        memory.countDegradation();
        int keep = pressure == MemoryBudget.Pressure.CRITICAL ? 25 : 60;
        int removed = renderer.chunkManager().trim(rig.camera.position, keep);
        if (pressure == MemoryBudget.Pressure.CRITICAL && mode == Mode.PLAY
                && settings.preset != Settings.Preset.BASE) {
            Settings.Preset lower = Settings.Preset.values()[
                    Math.max(0, settings.preset.ordinal() - 1)];
            settings.applyPreset(lower);
            settings.save();
            applySettings();
            ui.showPerformance(lower, "Memoria quasi esaurita: qualità ridotta a "
                    + lower.label + " (risoluzione " + lower.renderHeight
                    + "p) per mantenere il gioco stabile.");
        }
        Gdx.app.log("PTA", "memory pressure " + pressure + ": trimmed " + removed
                + " chunks, " + memory.summary());
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
        rig.yaw = world.spawnHeading;
        rig.pitch = 0.24f;
        audio.setTrack(AudioEngine.EXPLORE);
        updateCamera(0.016f);
        beginLoading(true, "Nuova partita · preparazione della città");
    }

    private void beginLoading(boolean newGame, String text) {
        loading = true;
        loadingNewGame = newGame;
        loadingTime = 0f;
        ui.loadingProgress = 0f;
        ui.loadingDetail = "";
        ui.showLoading(text);
        input.reset();
        Gdx.app.log("PTA", "loading start: " + text + " position=" + world.player().x
                + "," + world.player().z);
    }

    private void advanceLoading(float delta) {
        loadingTime += delta;
        float progress = renderer.chunkManager().progressAround(rig.camera.position, 260f);
        int near = Math.max(1, renderer.chunkManager().chunksAround(rig.camera.position, 260f));
        int ready = Math.round(near * progress);
        ui.loadingProgress = progress;
        ui.loadingDetail = ready + " / " + near + " zone pronte · "
                + renderer.chunkManager().loadedChunks() + " in memoria";
        // A slow device must not sit on the loading screen forever: after 25 s the game
        // enters anyway and the streaming keeps filling in behind the camera.
        if ((progress >= 0.999f && loadingTime > 0.4f) || loadingTime > 25f) {
            finishLoading(progress);
        }
    }

    private void finishLoading(float progress) {
        loading = false;
        ui.showGameplay();
        if (loadingNewGame) {
            MissionSystem.Mission first = world.missions.nextMainMission();
            if (first != null) {
                world.startMission(first);
            }
        }
        loadingNewGame = false;
        updateCamera(0.016f);
        Player p = world.player();
        Gdx.app.log("PTA", "loading done at " + (int) (progress * 100f) + "%: player="
                + p.x + "," + p.y + "," + p.z
                + " ground=" + map.heightAt(p.x, p.z)
                + " blocked=" + map.blocked(p.x, p.z, 0.55f)
                + " " + renderer.chunkManager().stats()
                + " " + memory.summary());
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
        rig.setFirstPerson(settings.firstPersonDefault);
        rig.yaw = 0;
        input.reset();
        updateCamera(0.016f);
        audio.setTrack(AudioEngine.EXPLORE);
        world.notify("Partita caricata");
        beginLoading(false, "Caricamento partita · streaming della città");
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
        if (mode == Mode.PLAY && !loading) {
            ui.showGameplay();
        }
    }

    @Override
    public void toMenu() {
        if (mode == Mode.PLAY) {
            save(0);
        }
        loading = false;
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

    private void fail(Throwable t) {
        if (fatal == null) {
            fatal = t;
            fatalMessage = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            Gdx.app.error("PTA", "startup/render failure", t);
        }
    }

    /** Non-black, readable fallback instead of leaving the player on a black surface. */
    private void drawFatal() {
        Gdx.gl.glClearColor(0.06f, 0.02f, 0.03f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        try {
            if (fatalFont == null) {
                fatalFont = new BitmapFont();
                fatalBatch = new SpriteBatch();
            }
            int w = Math.max(320, Gdx.graphics.getWidth());
            int h = Math.max(240, Gdx.graphics.getHeight());
            fatalBatch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
            fatalBatch.begin();
            fatalFont.setColor(new Color(1f, 0.82f, 0.76f, 1f));
            fatalFont.draw(fatalBatch, "PTA - ERRORE DI AVVIO\n\n" + fatalMessage
                    + "\n\nIl gioco non puo' partire su questo dispositivo.\n"
                    + "Apri il Logcat e cerca il tag PTA per il dettaglio.",
                    32, h - 48, w - 64, Align.left, true);
            fatalBatch.end();
        } catch (Throwable ignored) {
            // Even the fallback can fail on a broken GL context; the dark red clear
            // still makes the failure visible instead of a black screen.
        }
    }

    @Override
    public void dispose() {
        if (fatalBatch != null) {
            fatalBatch.dispose();
        }
        if (fatalFont != null) {
            fatalFont.dispose();
        }
        if (world != null) {
            world.dispose();
        }
        Gdx.app.log("PTA", "final memory: " + memory.summary());
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

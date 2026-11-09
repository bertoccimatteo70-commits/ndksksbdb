package it.pta.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import it.pta.audio.AudioEngine;
import it.pta.entity.CameraRig;
import it.pta.entity.Humanoid;
import it.pta.entity.Player;
import it.pta.entity.PoliceSystem;
import it.pta.entity.TrafficSystem;
import it.pta.entity.Vehicle;
import it.pta.entity.VehicleLibrary;
import it.pta.entity.VehicleType;
import it.pta.render.Atlas;
import it.pta.render.MeshBuilder;
import it.pta.render.Meshes;
import it.pta.render.WorldRenderer;
import it.pta.ui.ControlLayout;
import it.pta.ui.GameUi;
import it.pta.world.MapData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The playable world: two protagonists, streamed Pistoia, traffic, police, campaign,
 * economy, owned vehicles and walkable interiors.
 */
public final class World implements PoliceSystem.PoliceHost {
    public interface Listener {
        void onNotify(String text);

        void onOpenShop(Content.Place place);

        void onOpenProperty(Content.Place place);

        void onRequestSave();

        void onBusted();

        void onWasted();

        void onCampaignProgress();

        void onMissionFailed(MissionSystem.Mission mission, String reason);
    }

    public final MapData map;
    public final WorldRenderer renderer;
    public final Settings settings;
    public final AudioEngine audio;
    public final InputState input;
    public final VehicleLibrary vehicles;
    public final TrafficSystem traffic;
    public final PoliceSystem police;
    public final MissionSystem missions;
    public final Economy economy = new Economy();
    public final ControlLayout layout = new ControlLayout();
    public GameUi ui;
    public final Player[] players = new Player[2];
    public int active;
    public float playTime;
    public float wetness;
    public String notification = "";
    public float notificationTimer;
    public String prompt = "";
    public boolean busy;
    public Listener listener;

    private final Humanoid marco, elisa, policeModel;
    private final Humanoid[] pedModels = new Humanoid[4];
    private final Map<String, Target> targets = new HashMap<>();
    public final List<Vehicle> ownedVehicles = new ArrayList<>();
    private final List<Vehicle> tempVehicles = new ArrayList<>();
    private final Matrix4 xform = new Matrix4();
    private final Matrix4 meshXf = new Matrix4();
    private final com.badlogic.gdx.utils.FloatArray reticle = new com.badlogic.gdx.utils.FloatArray();

    private float autosaveTimer;
    private float footstepTimer;
    private float weatherTimer = 75f;
    private float rainTarget;
    private boolean handledBusted;
    private Mesh homeMesh;
    private Mesh garageMesh;
    private Interior interior;
    private float interiorHotX, interiorHotZ;
    private String interiorHotKind = "door";
    private float interiorCooldown;
    private float currentCamYaw;

    private static final class Target {
        Vehicle vehicle;
        final List<float[]> route = new ArrayList<>();
        int index;
        float speed;
        float stuck;
        float lifetime;
    }

    public static final class Interior {
        public final Content.Place place;
        public final float cx, cz, floorY;
        public final boolean garage;
        public final Mesh mesh;
        public final float halfX = 5.5f, halfZ = 4.5f;

        Interior(Content.Place place, float cx, float cz, float floorY, boolean garage, Mesh mesh) {
            this.place = place;
            this.cx = cx;
            this.cz = cz;
            this.floorY = floorY;
            this.garage = garage;
            this.mesh = mesh;
        }
    }

    public World(WorldRenderer renderer, Settings settings, AudioEngine audio, InputState input,
                 String missionsJson, String landmarksJson) {
        this.map = renderer.map();
        this.renderer = renderer;
        this.settings = settings;
        this.audio = audio;
        this.input = input;
        this.vehicles = new VehicleLibrary(map);
        this.traffic = new TrafficSystem(map, vehicles);
        this.police = new PoliceSystem(map, vehicles);
        this.missions = new MissionSystem(this, map);

        Content.init(map, landmarksJson);
        missions.load(missionsJson);

        marco = new Humanoid("Marco Bellandi",
                new float[]{0.86f, 0.68f, 0.55f}, new float[]{0.18f, 0.13f, 0.10f},
                new float[]{0.24f, 0.34f, 0.25f}, new float[]{0.20f, 0.21f, 0.24f},
                new float[]{0.10f, 0.10f, 0.10f}, false, false);
        elisa = new Humanoid("Elisa Vannucci",
                new float[]{0.90f, 0.74f, 0.62f}, new float[]{0.42f, 0.20f, 0.12f},
                new float[]{0.46f, 0.16f, 0.20f}, new float[]{0.20f, 0.24f, 0.38f},
                new float[]{0.12f, 0.10f, 0.10f}, true, false);
        policeModel = new Humanoid("Agente",
                new float[]{0.82f, 0.66f, 0.54f}, new float[]{0.12f, 0.10f, 0.09f},
                new float[]{0.13f, 0.20f, 0.42f}, new float[]{0.12f, 0.14f, 0.22f},
                new float[]{0.06f, 0.06f, 0.07f}, false, true);
        pedModels[0] = new Humanoid("Cittadino",
                new float[]{0.84f, 0.68f, 0.55f}, new float[]{0.22f, 0.16f, 0.12f},
                new float[]{0.55f, 0.52f, 0.46f}, new float[]{0.22f, 0.26f, 0.34f},
                new float[]{0.14f, 0.12f, 0.10f}, false, false);
        pedModels[1] = new Humanoid("Cittadina",
                new float[]{0.88f, 0.72f, 0.60f}, new float[]{0.30f, 0.20f, 0.12f},
                new float[]{0.36f, 0.42f, 0.52f}, new float[]{0.18f, 0.18f, 0.22f},
                new float[]{0.30f, 0.26f, 0.22f}, true, false);
        pedModels[2] = new Humanoid("Studente",
                new float[]{0.80f, 0.64f, 0.50f}, new float[]{0.10f, 0.09f, 0.08f},
                new float[]{0.62f, 0.30f, 0.22f}, new float[]{0.24f, 0.30f, 0.40f},
                new float[]{0.20f, 0.20f, 0.22f}, false, false);
        pedModels[3] = new Humanoid("Artigiano",
                new float[]{0.76f, 0.60f, 0.47f}, new float[]{0.35f, 0.33f, 0.30f},
                new float[]{0.30f, 0.38f, 0.40f}, new float[]{0.26f, 0.24f, 0.20f},
                new float[]{0.16f, 0.14f, 0.12f}, false, true);

        players[0] = new Player(0, marco);
        players[1] = new Player(1, elisa);
        spawnAtStart();

        // default owned vehicle (Marco's old hatchback) at the workshop
        Content.Place g = Content.get("garage");
        if (g != null) {
            Vehicle v = vehicles.spawn(VehicleType.HATCHBACK, g.x + 6f, g.z + 4f, 0.4f);
            v.isPlayerOwned = true;
            v.parked = true;
            v.setPaint(2);
            ownedVehicles.add(v);
        }
        buildInteriors();
        renderer.sun.setHours(9f);
    }

    private void spawnAtStart() {
        Content.Place garage = Content.get("garage");
        float gx = garage == null ? 0 : garage.x;
        float gz = garage == null ? 0 : garage.z;
        MapData.RoadHit gHit = map.nearestRoad(gx, gz, true, 160f);
        if (gHit != null) {
            gx = gHit.x + 2.5f;
            gz = gHit.z + 2.5f;
        }
        players[0].setPosition(gx, gz, map);
        Content.Place elisaHome = Content.get("casa_elisa");
        float ex = elisaHome == null ? gx : elisaHome.x;
        float ez = elisaHome == null ? gz : elisaHome.z;
        MapData.RoadHit eHit = map.nearestRoad(ex, ez, true, 160f);
        if (eHit != null) {
            ex = eHit.x - 2.5f;
            ez = eHit.z + 2.5f;
        }
        players[1].setPosition(ex, ez, map);
        players[1].heading = 0.2f;
    }

    public Player player() {
        return players[active];
    }

    public Humanoid humanoidFor(int i) {
        return i == 0 ? marco : elisa;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    // ------------------------------------------------------------------- update

    public void update(float dt, float camYaw) {
        currentCamYaw = camYaw;
        playTime += dt;
        Player p = player();
        if (input.switchCharacter && !p.inVehicle() && !p.indoor) {
            switchCharacter();
        }
        if (input.pause) {
            // handled by game
        }
        updateWeather(dt, p);

        if (p.indoor) {
            updateIndoor(dt, p);
        } else if (p.vehicle != null) {
            updateDriving(dt, p);
        } else {
            updateWalking(dt, p, camYaw);
        }

        handleEnterExit(p);
        handleInteraction(p);
        updateTargets(dt);
        traffic.update(dt, p, renderer.sun.night);
        police.update(dt, p, this);
        missions.update(dt);

        if (p.vehicle != null) {
            Vehicle v = p.vehicle;
            audio.setEngine(true, Math.min(1f, Math.abs(v.speed) / 18f + 0.15f),
                    Math.min(1f, Math.abs(v.speed) / v.type.topSpeed));
            audio.setSiren(false);
        } else {
            audio.setEngine(false, 0, 0);
            audio.setSiren(police.wanted > 0 && police.squadCars.size() > 0);
        }
        updateMusic(p);
        updateFootsteps(dt, p);
        checkBustedWasted(dt, p);

        autosaveTimer += dt;
        if (autosaveTimer > 75f) {
            autosaveTimer = 0;
            if (listener != null) {
                listener.onRequestSave();
            }
        }
        if (notificationTimer > 0) {
            notificationTimer -= dt;
            if (notificationTimer <= 0) {
                notification = "";
            }
        }
        if (interiorCooldown > 0) {
            interiorCooldown -= dt;
        }
    }

    private void updateWalking(float dt, Player p, float camYaw) {
        p.update(dt, map, input.moveX, input.moveY, camYaw, input.run, input.jump, input.attack);
        if (input.attack && p.isPunching()) {
            audio.punch();
            boolean hitCop = false;
            for (var o : police.officers) {
                if (Math.hypot(o.x - p.x, o.z - p.z) < 2.0f) {
                    hitCop = true;
                }
            }
            if (hitCop) {
                police.crime(1);
            }
        }
        if (traffic.lastPedHit > 1f && input.attack) {
            police.crime(1);
            traffic.lastPedHit = 0;
        }
    }

    private void updateDriving(float dt, Player p) {
        Vehicle v = p.vehicle;
        float throttle = input.throttleAxis();
        float steer = input.steering();
        v.update(dt, map, throttle, steer, input.handbrake, true, wetness);
        if (v.lastImpact > 3f) {
            audio.crash(v.lastImpact);
        }
        p.x = v.x;
        p.z = v.z;
        p.y = v.y;
        p.heading = v.heading;
        if (input.attack) {
            audio.horn();
        }
        // collisions with ambient traffic
        for (Vehicle other : traffic.cars()) {
            float d = (float) Math.hypot(other.x - v.x, other.z - v.z);
            float minD = (v.type.length + other.type.length) * 0.32f;
            if (d < minD) {
                float impact = Math.abs(v.speed - other.speed);
                if (impact > 3f) {
                    audio.crash(impact);
                    v.speed *= 0.55f;
                    v.damage = Math.min(100f, v.damage + impact * 1.6f);
                    other.speed *= 0.4f;
                }
            }
        }
    }

    private void updateIndoor(float dt, Player p) {
        p.update(dt, map, input.moveX, input.moveY, currentCamYaw, false, false, false);
        p.indoor = true;
        p.y = interior == null ? p.y : interior.floorY;
        p.grounded = true;
    }

    private void updateFootsteps(float dt, Player p) {
        if (p.inVehicle() || p.speed < 1.2f) {
            return;
        }
        footstepTimer -= dt;
        if (footstepTimer <= 0) {
            footstepTimer = p.speed > 4f ? 0.28f : 0.42f;
            audio.footstep();
        }
    }

    private void updateWeather(float dt, Player p) {
        weatherTimer -= dt;
        if (weatherTimer <= 0) {
            weatherTimer = 160f + (float) Math.random() * 200f;
            rainTarget = Math.random() < 0.34f ? 0.55f + (float) Math.random() * 0.45f : 0f;
        }
        wetness += (rainTarget * 0.55f - wetness) * Math.min(1f, dt * 0.25f);
        renderer.setWeather(rainTarget, 0.35f + rainTarget * 0.4f);
        audio.setRain(rainTarget);
    }

    private void updateMusic(Player p) {
        int track;
        if (police.wanted >= 2) {
            track = AudioEngine.CHASE;
        } else if (missions.active != null) {
            track = AudioEngine.MISSION;
        } else {
            float h = renderer.sun.hours;
            if (h < 6.5f || h > 21.5f) {
                track = AudioEngine.NIGHT;
            } else {
                track = AudioEngine.EXPLORE;
            }
        }
        audio.setTrack(track);
    }

    private void checkBustedWasted(float dt, Player p) {
        if (police.arrested) {
            police.arrested = false;
            handledBusted = true;
            bust(p, true);
            return;
        }
        if (p.health <= 0.01f) {
            bust(p, false);
        }
    }

    private void bust(Player p, boolean arrested) {
        if (busy) {
            return;
        }
        busy = true;
        exitVehicleIfAny(p, true);
        int loss = Math.max(50, (int) (economy.cash * (arrested ? 0.12f : 0.06f)));
        economy.spend(loss, arrested ? "Multa e cauzione" : "Spese ospedale", playTime);
        police.reset();
        p.health = arrested ? 75 : 100;
        Content.Place q = Content.get(arrested ? "questura" : "garage");
        if (q != null) {
            p.setPosition(q.x + 3f, q.z + 3f, map);
        }
        audio.setSiren(false);
        if (listener != null) {
            if (arrested) {
                listener.onBusted();
            } else {
                listener.onWasted();
            }
        }
        busy = false;
    }

    private void switchCharacter() {
        Player p = player();
        p.stop();
        active = 1 - active;
        Player np = player();
        np.vehicle = null;
        np.stop();
        notification = np.character == 0 ? "Ora giochi come Marco" : "Ora giochi come Elisa";
        notificationTimer = 3.5f;
        audio.dialogueBlip();
    }

    public void startMission(MissionSystem.Mission m) {
        if (m == null) {
            return;
        }
        tempVehicles.clear();
        if (!m.spawnVehicle.isEmpty()) {
            Content.Place sp = Content.get(m.spawnPlace);
            if (sp != null) {
                VehicleType type;
                try {
                    type = VehicleType.valueOf(m.spawnVehicle);
                } catch (Exception e) {
                    type = VehicleType.SEDAN;
                }
                Vehicle v = vehicles.spawn(type, sp.x + 5f, sp.z + 5f, 0.3f);
                tempVehicles.add(v);
            }
        }
        if ("marco".equals(m.character) && active != 0 && !player().inVehicle()) {
            active = 0;
        } else if ("elisa".equals(m.character) && active != 1 && !player().inVehicle()) {
            active = 1;
        }
        missions.start(m);
    }

    // -------------------------------------------------------------- interaction

    private void handleEnterExit(Player p) {
        if (!input.enterExit || p.indoor) {
            return;
        }
        if (p.vehicle != null) {
            exitVehicle(p, false);
        } else {
            Vehicle v = nearestVehicle(p.x, p.z, 5.0f);
            if (v != null) {
                enterVehicle(p, v);
            }
        }
        input.enterExit = false;
    }

    public Vehicle nearestVehicle(float x, float z, float radius) {
        Vehicle best = null;
        float bestD = radius;
        List<Vehicle> all = new ArrayList<>();
        all.addAll(traffic.cars());
        all.addAll(ownedVehicles);
        all.addAll(tempVehicles);
        for (Target t : targets.values()) {
            all.add(t.vehicle);
        }
        all.addAll(police.squadCars);
        for (Vehicle v : all) {
            float d = (float) Math.hypot(v.x - x, v.z - z);
            if (d < bestD && !v.brokenDown()) {
                bestD = d;
                best = v;
            }
        }
        return best;
    }

    public void enterVehicle(Player p, Vehicle v) {
        p.vehicle = v;
        p.stop();
        p.x = v.x;
        p.z = v.z;
        p.y = v.y;
        if (v.isPolice && !v.isPlayerOwned) {
            police.crime(2);
        } else if (!v.isPlayerOwned) {
            police.crime(1);
        }
        audio.setEngine(true, 0.2f, 0f);
        notification = v.type.label + " · " + (v.fuel < v.fuelCapacity * 0.2f ? "carburante scarso" : "carburante " + (int) v.fuel + " L");
        notificationTimer = 3f;
    }

    public void exitVehicle(Player p, boolean silent) {
        Vehicle v = p.vehicle;
        if (v == null) {
            return;
        }
        p.vehicle = null;
        float side = 1f;
        float ox = MathUtils.cos(v.heading) * (v.type.width * 0.7f) * side;
        float oz = -MathUtils.sin(v.heading) * (v.type.width * 0.7f) * side;
        float px = v.x + ox;
        float pz = v.z + oz;
        if (map.blocked(px, pz, 0.5f)) {
            px = v.x - ox;
            pz = v.z - oz;
        }
        p.setPosition(px, pz, map);
        p.heading = v.heading;
        v.speed = 0;
        audio.setEngine(false, 0, 0);
        if (!silent) {
            notification = "Sei sceso dal veicolo";
            notificationTimer = 2.2f;
        }
    }

    private void exitVehicleIfAny(Player p, boolean silent) {
        if (p.vehicle != null) {
            exitVehicle(p, silent);
        }
    }

    private void handleInteraction(Player p) {
        prompt = "";
        if (p.indoor) {
            updateIndoorPrompt(p);
            if (input.interact && interiorCooldown <= 0) {
                input.interact = false;
                if (Math.hypot(p.x - interiorHotX, p.z - interiorHotZ) < 2.6f) {
                    if ("bed".equals(interiorHotKind)) {
                        if (listener != null) {
                            listener.onRequestSave();
                        }
                    } else if ("lift".equals(interiorHotKind)) {
                        spawnOwnedVehicleOutside();
                        leaveInterior();
                    }
                } else if (isNearDoor(p)) {
                    leaveInterior();
                }
            }
            return;
        }
        if (p.vehicle != null) {
            Content.Place fuel = nearestPlace(p.x, p.z, 15f, Content.Kind.FUEL);
            if (fuel != null) {
                Vehicle v = p.vehicle;
                prompt = "Rifornisci (" + (int) Math.ceil(v.fuelCapacity - v.fuel) + " L · €" +
                        ((int) Math.ceil((v.fuelCapacity - v.fuel) * 1.85f)) + ")";
                if (input.interact) {
                    input.interact = false;
                    int litres = (int) Math.ceil(v.fuelCapacity - v.fuel);
                    int price = (int) Math.ceil(litres * 1.85f);
                    if (litres <= 0) {
                        notification = "Serbatoio già pieno";
                    } else if (economy.spend(price, "Carburante", playTime)) {
                        v.refuel();
                        audio.refuel();
                        audio.cash();
                        notification = "Rifornimento completato · -€" + price;
                    } else {
                        notification = "Non hai abbastanza soldi";
                    }
                    notificationTimer = 3f;
                }
            }
            return;
        }
        Content.Place near = nearestPlace(p.x, p.z, 7.5f, null);
        if (near == null) {
            return;
        }
        switch (near.kind) {
            case HOME:
            case GARAGE:
                if (economy.owns(near.id) || "garage".equals(near.id) || "casa_elisa".equals(near.id)) {
                    prompt = "Entra in " + near.label;
                    if (input.interact) {
                        input.interact = false;
                        enterProperty(near);
                    }
                } else {
                    prompt = near.label + " · €" + Economy.format(near.price);
                    if (input.interact) {
                        input.interact = false;
                        if (listener != null) {
                            listener.onOpenProperty(near);
                        }
                    }
                }
                break;
            case DEALER:
            case TUNING:
            case SHOP:
            case BUSINESS:
                prompt = "Apri " + near.label;
                if (input.interact) {
                    input.interact = false;
                    if (listener != null) {
                        listener.onOpenShop(near);
                    }
                }
                break;
            case FUEL:
                prompt = "Stazione di servizio";
                break;
            default:
                break;
        }
    }

    private Content.Place nearestPlace(float x, float z, float radius, Content.Kind kind) {
        Content.Place best = null;
        float bestD = radius;
        for (Content.Place pl : Content.all()) {
            if (kind != null && pl.kind != kind) {
                continue;
            }
            float d = (float) Math.hypot(pl.x - x, pl.z - z);
            if (d < bestD) {
                bestD = d;
                best = pl;
            }
        }
        return best;
    }

    private boolean isNearDoor(Player p) {
        if (interior == null) {
            return false;
        }
        return Math.hypot(p.x - interior.cx, p.z - (interior.cz + interior.halfZ - 0.7f)) < 2.2f;
    }

    private void updateIndoorPrompt(Player p) {
        prompt = "Interagisci: esci";
        if (Math.hypot(p.x - interiorHotX, p.z - interiorHotZ) < 2.6f) {
            if ("bed".equals(interiorHotKind)) {
                prompt = "Dormi e salva la partita";
            } else if ("lift".equals(interiorHotKind)) {
                prompt = "Prendi un veicolo dal garage";
            }
        }
    }

    // ---------------------------------------------------------------- interiors

    private void buildInteriors() {
        MeshBuilder home = new MeshBuilder();
        room(home, false);
        homeMesh = Meshes.from(home);
        MeshBuilder garage = new MeshBuilder();
        room(garage, true);
        garageMesh = Meshes.from(garage);
    }

    /** Builds a local-space room: floor, walls, ceiling and furniture. */
    private void room(MeshBuilder mb, boolean garage) {
        float hx = 5.5f, hz = 4.5f, h = 3.0f;
        float floorTone = garage ? 0.42f : 0.58f;
        // floor
        mb.flatQuad(-hx, -hz, hx, hz, 0.02f, floorTone, floorTone * 0.92f, floorTone * 0.82f,
                garage ? Atlas.CONCRETE : Atlas.CONCRETE, 0.5f, 0.5f);
        // ceiling
        mb.flatQuad(-hx, -hz, hx, hz, h, 0.72f, 0.71f, 0.68f, Atlas.CONCRETE, 0.4f, 0.4f);
        float wr = garage ? 0.66f : 0.80f, wg = garage ? 0.66f : 0.75f, wb = garage ? 0.64f : 0.66f;
        // walls (door gap in the +Z south wall)
        mb.wallQuad(-hx, 0, -hz, hx, 0, -hz, h, 0, 0, -1, wr, wg, wb, Atlas.CONCRETE, 0.35f);
        mb.wallQuad(-hx, 0, -hz, -hx, 0, hz, h, -1, 0, 0, wr, wg, wb, Atlas.CONCRETE, 0.35f);
        mb.wallQuad(hx, 0, hz, hx, 0, -hz, h, 1, 0, 0, wr, wg, wb, Atlas.CONCRETE, 0.35f);
        // south wall with a 1.6 m doorway
        mb.wallQuad(-hx, 0, hz, -0.8f, 0, hz, h, 0, 0, 1, wr, wg, wb, Atlas.CONCRETE, 0.35f);
        mb.wallQuad(0.8f, 0, hz, hx, 0, hz, h, 0, 0, 1, wr, wg, wb, Atlas.CONCRETE, 0.35f);
        // lintel above the door
        mb.wallQuad(-0.8f, 2.2f, hz, 0.8f, 2.2f, hz, h - 2.2f, 0, 0, 1,
                wr, wg, wb, Atlas.CONCRETE, 0.35f);
        if (garage) {
            // lift platform, tool bench, shelves
            mb.box(-2.2f, 0.15f, -1.0f, 1.9f, 0.14f, 2.6f, 0.36f, 0.37f, 0.38f, Atlas.METAL, 0.4f);
            mb.box(-4.6f, 0.75f, -3.2f, 0.9f, 0.55f, 1.0f, 0.40f, 0.32f, 0.24f, Atlas.METAL, 0.5f);
            mb.box(4.4f, 1.15f, -2.8f, 0.4f, 1.15f, 1.5f, 0.28f, 0.30f, 0.32f, Atlas.METAL, 0.4f);
            mb.box(4.4f, 0.6f, 0.5f, 0.4f, 0.6f, 0.9f, 0.26f, 0.26f, 0.28f, Atlas.METAL, 0.5f);
            // rolling door detail
            mb.flatQuad(-hx + 0.2f, -hz + 0.02f, hx - 0.2f, -hz + 0.05f, 2.55f,
                    0.55f, 0.56f, 0.58f, Atlas.METAL, 0.6f, 0.6f);
        } else {
            // bed
            mb.box(-3.6f, 0.28f, -2.6f, 1.1f, 0.28f, 1.9f, 0.52f, 0.40f, 0.34f, Atlas.PAINT, 0.5f);
            mb.box(-3.6f, 0.62f, -3.5f, 1.0f, 0.10f, 0.5f, 0.82f, 0.80f, 0.74f, Atlas.PAINT, 0.6f);
            mb.box(-3.6f, 0.62f, -1.4f, 1.05f, 0.12f, 0.9f, 0.40f, 0.30f, 0.30f, Atlas.PAINT, 0.5f);
            // sofa + table
            mb.box(2.8f, 0.34f, 1.6f, 1.3f, 0.34f, 0.8f, 0.30f, 0.34f, 0.40f, Atlas.PAINT, 0.5f);
            mb.box(2.8f, 0.70f, 2.8f, 1.3f, 0.38f, 0.18f, 0.28f, 0.32f, 0.38f, Atlas.PAINT, 0.5f);
            mb.box(0.2f, 0.42f, 0.4f, 0.9f, 0.06f, 0.6f, 0.45f, 0.34f, 0.24f, Atlas.PAINT, 0.6f);
            mb.box(0.2f, 0.21f, 0.4f, 0.12f, 0.21f, 0.12f, 0.32f, 0.24f, 0.18f, Atlas.METAL, 0.8f);
            // kitchenette
            mb.box(4.5f, 0.45f, -2.4f, 0.7f, 0.45f, 1.9f, 0.62f, 0.60f, 0.55f, Atlas.PAINT, 0.5f);
            mb.box(4.5f, 0.95f, -3.4f, 0.6f, 0.06f, 0.35f, 0.70f, 0.70f, 0.68f, Atlas.METAL, 0.6f);
            // wardrobe
            mb.box(3.6f, 1.0f, -4.2f, 1.2f, 1.0f, 0.35f, 0.38f, 0.28f, 0.22f, Atlas.PAINT, 0.5f);
            // rug
            mb.flatQuad(-1.2f, -1.0f, 1.6f, 2.0f, 0.035f, 0.48f, 0.22f, 0.20f, Atlas.PAINT, 0.4f, 0.4f);
        }
    }

    public boolean enterProperty(Content.Place place) {
        if (place == null || interiorCooldown > 0) {
            return false;
        }
        boolean garage = place.kind == Content.Kind.GARAGE;
        Mesh mesh = garage ? garageMesh : homeMesh;
        if (mesh == null) {
            return false;
        }
        interior = new Interior(place, place.x, place.z, map.heightAt(place.x, place.z) + 0.05f,
                garage, mesh);
        Player p = player();
        exitVehicleIfAny(p, true);
        p.indoor = true;
        p.roomMinX = place.x - interior.halfX;
        p.roomMaxX = place.x + interior.halfX;
        p.roomMinZ = place.z - interior.halfZ;
        p.roomMaxZ = place.z + interior.halfZ;
        p.roomFloorY = interior.floorY;
        p.x = place.x;
        p.z = place.z + interior.halfZ - 2.2f;
        p.y = interior.floorY;
        p.heading = 0f;
        p.stop();
        interiorHotKind = garage ? "lift" : "bed";
        if (garage) {
            interiorHotX = place.x - 2.2f;
            interiorHotZ = place.z - 1.0f;
        } else {
            interiorHotX = place.x - 3.6f;
            interiorHotZ = place.z - 2.6f;
        }
        interiorCooldown = 0.6f;
        audio.setIndoors(true);
        audio.setTrack(AudioEngine.NIGHT);
        notification = garage ? "Garage · interagisci sul ponte per prendere un veicolo"
                : "Casa · interagisci sul letto per salvare";
        notificationTimer = 4f;
        return true;
    }

    public void leaveInterior() {
        if (interior == null) {
            return;
        }
        Player p = player();
        float ox = interior.cx;
        float oz = interior.cz + interior.halfZ + 2.2f;
        p.indoor = false;
        p.setPosition(ox, oz, map);
        p.heading = (float) Math.PI;
        interior = null;
        interiorCooldown = 0.6f;
        audio.setIndoors(false);
    }

    public boolean insideInterior() {
        return interior != null;
    }

    public Interior interior() {
        return interior;
    }

    private void spawnOwnedVehicleOutside() {
        if (ownedVehicles.isEmpty()) {
            notification = "Non possiedi veicoli";
            return;
        }
        Content.Place g = Content.get("garage");
        if (g == null) {
            return;
        }
        Vehicle v = ownedVehicles.get(0);
        v.x = g.x + 6f;
        v.z = g.z + 6f;
        v.y = map.heightAt(v.x, v.z);
        v.refuel();
        v.repair();
        notification = "Veicolo pronto fuori dall'officina";
        notificationTimer = 3f;
    }

    // ----------------------------------------------------------- mission targets

    public void spawnMissionVehicle(String tag, VehicleType type, float x, float z,
                                    List<String> route, float speed) {
        removeMissionVehicle(tag);
        float[] road = snapToRoad(x, z);
        float heading = 0f;
        Vehicle v = vehicles.spawn(type, road[0], road[1], heading);
        Target t = new Target();
        t.vehicle = v;
        t.speed = speed;
        for (String place : route) {
            Content.Place p = Content.get(place);
            if (p != null) {
                t.route.add(new float[]{p.x, p.z});
            }
        }
        if (t.route.isEmpty()) {
            t.route.add(new float[]{road[0] + 100, road[1]});
        }
        targets.put(tag, t);
        v.isPolice = false;
    }

    private float[] snapToRoad(float x, float z) {
        MapData.RoadHit hit = map.nearestRoad(x, z, true, 120f);
        if (hit != null) {
            return new float[]{hit.x, hit.z};
        }
        return new float[]{x, z};
    }

    public Vehicle missionTarget(String tag) {
        Target t = targets.get(tag);
        return t == null ? null : t.vehicle;
    }

    public void removeMissionVehicle(String tag) {
        targets.remove(tag);
    }

    public void removeAllMissionVehicles() {
        targets.clear();
    }

    private void updateTargets(float dt) {
        Iterator<Map.Entry<String, Target>> it = targets.entrySet().iterator();
        while (it.hasNext()) {
            Target t = it.next().getValue();
            t.lifetime += dt;
            Vehicle v = t.vehicle;
            if (v == player().vehicle) {
                continue;
            }
            if (t.index >= t.route.size()) {
                t.index = 0;
            }
            float[] wp = t.route.get(t.index);
            float dx = wp[0] - v.x;
            float dz = wp[1] - v.z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            if (dist < 16f && t.route.size() > 1) {
                t.index = (t.index + 1) % t.route.size();
                wp = t.route.get(t.index);
                dx = wp[0] - v.x;
                dz = wp[1] - v.z;
                dist = (float) Math.sqrt(dx * dx + dz * dz);
            }
            float desired = (float) Math.atan2(dx, -dz);
            float diff = wrap(desired - v.heading);
            v.heading += MathUtils.clamp(diff, -2.2f * dt, 2.2f * dt);
            float targetSpeed = dist < 25f ? Math.min(t.speed, 7f) : t.speed;
            v.speed += (targetSpeed - v.speed) * Math.min(1f, dt * 1.4f);
            float fx = MathUtils.sin(v.heading);
            float fz = -MathUtils.cos(v.heading);
            float nx = v.x + fx * v.speed * dt;
            float nz = v.z + fz * v.speed * dt;
            if (!map.blocked(nx, nz, 1.1f)) {
                v.x = nx;
                v.z = nz;
                t.stuck = Math.max(0, t.stuck - dt);
            } else {
                v.heading += 2.2f * dt;
                v.speed *= 0.4f;
                t.stuck += dt;
                if (t.stuck > 4f) {
                    v.x += (float) Math.sin(v.heading + Math.PI) * 2.5f;
                    v.z -= (float) Math.cos(v.heading + Math.PI) * 2.5f;
                    t.stuck = 0;
                }
            }
            v.y = map.heightAt(v.x, v.z);
            v.wheelSpin += v.speed / Math.max(0.2f, v.type.wheelRadius) * dt;
        }
    }

    private static float wrap(float a) {
        while (a > Math.PI) {
            a -= Math.PI * 2;
        }
        while (a < -Math.PI) {
            a += Math.PI * 2;
        }
        return a;
    }

    // --------------------------------------------------------------------- draw

    public void render(CameraRig rig) {
        ShaderProgram shader = renderer.worldShader();
        renderer.beginEntities(rig.camera);
        Player p = player();
        reticle.clear();

        if (interior != null) {
            meshXf.idt().translate(interior.cx, interior.floorY, interior.cz);
            shader.setUniformf("u_tint", 1f, 1f, 1f, 1f);
            shader.setUniformMatrix("u_model", meshXf);
            interior.mesh.render(shader, GL20.GL_TRIANGLES);
            renderer.addGlow(interior.cx - 2.5f, interior.floorY + 2.6f, interior.cz - 1.5f,
                    3.2f, 1.0f, 0.92f, 0.72f);
            renderer.addGlow(interior.cx + 2.8f, interior.floorY + 2.6f, interior.cz + 1.6f,
                    2.6f, 1.0f, 0.90f, 0.70f);
            p.draw(shader, renderer);
            renderer.endEntities();
            return;
        }

        if (p.vehicle == null) {
            p.draw(shader, renderer);
        } else {
            p.vehicle.draw(shader, renderer, true);
            p.vehicle.collectLights(renderer, renderer.sun.night);
        }
        for (Vehicle v : tempVehicles) {
            if (v != p.vehicle) {
                v.draw(shader, renderer, true);
            }
        }
        for (Vehicle v : ownedVehicles) {
            if (v == p.vehicle) {
                continue;
            }
            float d = rig.camera.position.dst(v.x, v.y, v.z);
            if (d < 260f) {
                v.draw(shader, renderer, d < 90f);
            }
        }
        traffic.drawCars(shader, renderer, rig.camera.position, renderer.sun.night);
        traffic.drawPeds(shader, renderer, rig.camera.position, pedModels);
        police.draw(shader, renderer, rig.camera.position, policeModel);
        for (Target t : targets.values()) {
            t.vehicle.draw(shader, renderer, true);
            t.vehicle.collectLights(renderer, renderer.sun.night);
        }
        // mission marker + pickups
        if (missions.markerActive) {
            float gy = map.heightAt(missions.markerX, missions.markerZ);
            renderer.addBeacon(missions.markerX, missions.markerZ, gy, 1.0f, 0.82f, 0.28f);
        }
        for (MissionSystem.Pickup pk : missions.pickups) {
            if (!pk.taken) {
                renderer.addGlow(pk.x, map.heightAt(pk.x, pk.z) + 1.1f, pk.z, 0.9f, 1f, 0.9f, 0.4f);
            }
        }
        // safehouse / shop markers when close
        for (Content.Place pl : Content.all()) {
            float d = (float) Math.hypot(pl.x - p.x, pl.z - p.z);
            if (d > 90f) {
                continue;
            }
            if (pl.kind == Content.Kind.HOME || pl.kind == Content.Kind.GARAGE
                    || pl.kind == Content.Kind.DEALER || pl.kind == Content.Kind.TUNING
                    || pl.kind == Content.Kind.SHOP || pl.kind == Content.Kind.BUSINESS) {
                renderer.addBeacon(pl.x, pl.z, map.heightAt(pl.x, pl.z), 0.35f, 0.75f, 1.0f);
            }
        }
        renderer.endEntities();
    }

    // ------------------------------------------------------------- police events

    @Override
    public void onWantedReduced(int wanted) {
        if (wanted == 0) {
            notification = "Hai perso la polizia";
            notificationTimer = 3f;
        } else {
            notification = "Ricercato: " + wanted + " stelle";
            notificationTimer = 3f;
        }
    }

    public void notify(String text) {
        notification = text;
        notificationTimer = 4f;
        if (listener != null) {
            listener.onNotify(text);
        }
    }

    // ------------------------------------------------------- mission callbacks

    public void onMissionStarted(MissionSystem.Mission m) {
        audio.setTrack(AudioEngine.MISSION);
        notify("Missione avviata: " + m.title);
    }

    public void onObjectiveChanged(MissionSystem.Objective o, boolean marker, float x, float z, String label) {
        if (o != null) {
            notify(o.text);
        }
    }

    public void onMissionCompleted(MissionSystem.Mission m) {
        audio.missionComplete();
        if ("m11".equals(m.id)) {
            notify("Campagna completata! Esplorazione libera sbloccata.");
        }
    }

    public void onMissionFailed(MissionSystem.Mission m, String reason) {
        notify("Missione fallita: " + reason);
        audio.fail();
        if (listener != null) {
            listener.onMissionFailed(m, reason);
        }
    }

    public void onCampaignProgress() {
        tempVehicles.clear();
        if (listener != null) {
            listener.onCampaignProgress();
        }
    }

    // ------------------------------------------------------------------- saving

    public void writeSave(SaveManager.SaveData d) {
        Player p = player();
        d.playTime = playTime;
        d.character = active;
        d.x = p.x;
        d.z = p.z;
        d.health = p.health;
        d.armor = p.armor;
        d.cash = economy.cash;
        d.bank = economy.bank;
        d.wanted = police.wanted;
        d.timeOfDay = renderer.sun.hours;
        d.completedMissions = missions.completed.toArray(new String[0]);
        d.completedSide = missions.completedSide.toArray(new String[0]);
        d.properties = economy.properties.toArray(new String[0]);
        d.activeMission = missions.active == null ? null : missions.active.id;
        d.objectiveIndex = missions.objectiveIndex;
        d.totalEarned = economy.totalEarned;
        d.totalSpent = economy.totalSpent;
        Economy.OwnedVehicle[] ov = new Economy.OwnedVehicle[economy.vehicles.size()];
        for (int i = 0; i < ov.length; i++) {
            Economy.OwnedVehicle src = economy.vehicles.get(i);
            Economy.OwnedVehicle copy = new Economy.OwnedVehicle(src.type(), src.plate,
                    src.paintIndex, src.garageId);
            copy.condition = src.condition;
            copy.rimIndex = src.rimIndex;
            ov[i] = copy;
        }
        d.vehicles = ov;
    }

    public void readSave(SaveManager.SaveData d) {
        playTime = d.playTime;
        active = MathUtils.clamp(d.character, 0, 1);
        Player p = player();
        interior = null;
        audio.setIndoors(false);
        for (Player pl : players) {
            pl.indoor = false;
            pl.vehicle = null;
        }
        p.setPosition(d.x, d.z, map);
        p.health = d.health;
        p.armor = d.armor;
        economy.cash = d.cash;
        economy.bank = d.bank;
        economy.totalEarned = d.totalEarned;
        economy.totalSpent = d.totalSpent;
        economy.properties.clear();
        economy.vehicles.clear();
        if (d.properties != null) {
            for (String s : d.properties) {
                economy.properties.add(s);
                Content.Place pl = Content.get(s);
                if (pl != null && pl.income > 0) {
                    economy.businessIncome.put(s, pl.income);
                }
            }
        }
        if (d.vehicles != null) {
            for (Economy.OwnedVehicle v : d.vehicles) {
                if (v != null) {
                    economy.vehicles.add(v);
                }
            }
        }
        missions.completed.clear();
        if (d.completedMissions != null) {
            for (String s : d.completedMissions) {
                missions.completed.add(s);
            }
        }
        missions.completedSide.clear();
        if (d.completedSide != null) {
            for (String s : d.completedSide) {
                missions.completedSide.add(s);
            }
        }
        missions.active = null;
        missions.markerActive = false;
        police.reset();
        renderer.sun.setHours(d.timeOfDay > 0 ? d.timeOfDay : 9f);
        ownedVehicles.clear();
        Content.Place g = Content.get("garage");
        int i = 0;
        for (Economy.OwnedVehicle ov : economy.vehicles) {
            if (g != null) {
                Vehicle v = vehicles.spawn(ov.type(), g.x + 6f + i * 2.5f, g.z + 6f, 0.3f);
                v.isPlayerOwned = true;
                v.parked = true;
                v.setPaint(ov.paintIndex);
                v.rimIndex = ov.rimIndex;
                ownedVehicles.add(v);
                i++;
            }
        }
        if (ownedVehicles.isEmpty() && g != null) {
            Vehicle v = vehicles.spawn(VehicleType.HATCHBACK, g.x + 6f, g.z + 4f, 0.4f);
            v.isPlayerOwned = true;
            ownedVehicles.add(v);
        }
        if (d.activeMission != null) {
            MissionSystem.Mission m = missions.mission(d.activeMission);
            if (m != null) {
                missions.resume(m, d.objectiveIndex);
            }
        }
        notify("Partita caricata");
    }

    public void resetProgress() {
        economy.cash = 2500;
        economy.bank = 0;
        economy.properties.clear();
        economy.businessIncome.clear();
        economy.vehicles.clear();
        economy.ledger.clear();
        economy.totalEarned = 0;
        economy.totalSpent = 0;
        missions.completed.clear();
        missions.completedSide.clear();
        missions.active = null;
        missions.markerActive = false;
        missions.dialogueActive = false;
        missions.outroPending = false;
        missions.failed = false;
        missions.dialogue.clear();
        active = 0;
        for (Player pl : players) {
            pl.health = 100;
            pl.armor = 0;
            pl.stamina = 100;
        }
        police.reset();
        traffic.clear();
        targets.clear();
        tempVehicles.clear();
        interior = null;
        for (Player pl : players) {
            pl.indoor = false;
            pl.vehicle = null;
        }
        playTime = 0;
        renderer.sun.setHours(9f);
        spawnAtStart();
        Content.Place g = Content.get("garage");
        ownedVehicles.clear();
        if (g != null) {
            Vehicle v = vehicles.spawn(VehicleType.HATCHBACK, g.x + 6f, g.z + 4f, 0.4f);
            v.isPlayerOwned = true;
            ownedVehicles.add(v);
        }
    }

    public void dispose() {
        traffic.clear();
        marco.dispose();
        elisa.dispose();
        policeModel.dispose();
        for (Humanoid h : pedModels) {
            h.dispose();
        }
        vehicles.dispose();
        if (homeMesh != null) {
            homeMesh.dispose();
        }
        if (garageMesh != null) {
            garageMesh.dispose();
        }
    }
}

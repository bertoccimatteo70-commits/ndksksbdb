package it.pta.game;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import it.pta.entity.Vehicle;
import it.pta.world.MapData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runtime for the narrative campaign and side jobs. Objectives, markers, timers,
 * chase targets, races and fail conditions are evaluated every frame.
 */
public final class MissionSystem {
    public static final class Line {
        public final String speaker;
        public final String text;
        public final float duration;

        public Line(String speaker, String text, float duration) {
            this.speaker = speaker;
            this.text = text;
            this.duration = duration;
        }
    }

    public static final class Objective {
        public String type = "goto";
        public String text = "";
        public String place = "";
        public String vehicle = "";
        public String target = "";
        public float radius = 10f;
        public float time;
        public int count = 1;
        public int stars;
        public int laps = 1;
        public List<String> route = new ArrayList<>();
        public boolean done;
    }

    public static final class Mission {
        public String id, title, character = "both", requires = "";
        public int chapter, reward;
        public float timeLimit;
        public int failWanted;
        public boolean side;
        public List<Line> intro = new ArrayList<>();
        public List<Line> outro = new ArrayList<>();
        public List<Objective> objectives = new ArrayList<>();
        public String spawnVehicle = "";
        public String spawnPlace = "";
        public String targetTag = "";
        public String targetType = "";
        public String targetPlace = "";
        public List<String> targetRoute = new ArrayList<>();
        public float targetSpeed = 16f;
    }

    public static final class Pickup {
        public float x, z;
        public boolean taken;
        public String tag;
    }

    private final World world;
    private final MapData map;
    public final List<Mission> missions = new ArrayList<>();
    public final Set<String> completed = new HashSet<>();
    public final Set<String> completedSide = new HashSet<>();

    public Mission active;
    public int objectiveIndex;
    public float missionTime;
    public float objectiveTimer;
    public float checkpointTimer;
    public int raceLapsDone;
    public int raceIndex;
    public List<float[]> raceCheckpoints = new ArrayList<>();
    public final List<Pickup> pickups = new ArrayList<>();
    public float markerX, markerZ;
    public boolean markerActive;
    public String markerLabel = "";
    public boolean failed;
    public String failReason = "";
    public boolean waitingToStart;

    // dialogue state
    public final List<Line> dialogue = new ArrayList<>();
    public int dialogueIndex;
    public float dialogueTimer;
    public Runnable afterDialogue;
    public boolean dialogueActive;
    public boolean outroPending;

    public MissionSystem(World world, MapData map) {
        this.world = world;
        this.map = map;
    }

    public void load(String json) {
        missions.clear();
        JsonValue root = new JsonReader().parse(json);
        JsonValue arr = root.get("missions");
        for (JsonValue m = arr.child; m != null; m = m.next) {
            Mission mi = new Mission();
            mi.id = m.getString("id");
            mi.title = m.getString("title");
            mi.character = m.getString("character", "both");
            mi.requires = m.getString("requires", "");
            mi.chapter = m.getInt("chapter", 1);
            mi.reward = m.getInt("reward", 0);
            mi.timeLimit = m.getFloat("timeLimit", 0);
            mi.failWanted = m.getInt("failWanted", 0);
            mi.side = m.getBoolean("side", false);
            mi.spawnVehicle = m.getString("spawnVehicle", "");
            mi.spawnPlace = m.getString("spawnPlace", "");
            readLines(m.get("intro"), mi.intro);
            readLines(m.get("outro"), mi.outro);
            JsonValue objs = m.get("objectives");
            if (objs != null) {
                for (JsonValue o = objs.child; o != null; o = o.next) {
                    Objective ob = new Objective();
                    ob.type = o.getString("type", "goto");
                    ob.text = o.getString("text", "");
                    ob.place = o.getString("place", "");
                    ob.vehicle = o.getString("vehicle", "");
                    ob.target = o.getString("target", "");
                    ob.radius = o.getFloat("radius", 10);
                    ob.time = o.getFloat("time", 0);
                    ob.count = o.getInt("count", 1);
                    ob.stars = o.getInt("stars", 0);
                    ob.laps = o.getInt("laps", 1);
                    JsonValue route = o.get("route");
                    if (route != null) {
                        for (JsonValue r = route.child; r != null; r = r.next) {
                            ob.route.add(r.asString());
                        }
                    }
                    mi.objectives.add(ob);
                }
            }
            JsonValue st = m.get("spawnTarget");
            if (st != null) {
                mi.targetTag = st.getString("tag", "target");
                mi.targetType = st.getString("type", "SEDAN");
                mi.targetPlace = st.getString("place", "");
                mi.targetSpeed = st.getFloat("speed", 16);
                JsonValue route = st.get("route");
                if (route != null) {
                    for (JsonValue r = route.child; r != null; r = r.next) {
                        mi.targetRoute.add(r.asString());
                    }
                }
            }
            missions.add(mi);
        }
    }

    private void readLines(JsonValue arr, List<Line> out) {
        if (arr == null) {
            return;
        }
        for (JsonValue l = arr.child; l != null; l = l.next) {
            out.add(new Line(l.getString("s", ""), l.getString("t", ""), l.getFloat("dur", 4.2f)));
        }
    }

    public Mission mission(String id) {
        for (Mission m : missions) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public boolean isCompleted(String id) {
        return completed.contains(id);
    }

    public Mission nextMainMission() {
        for (Mission m : missions) {
            if (!m.side && !completed.contains(m.id) && (m.requires.isEmpty() || completed.contains(m.requires))) {
                return m;
            }
        }
        return null;
    }

    public List<Mission> availableSideMissions() {
        List<Mission> out = new ArrayList<>();
        for (Mission m : missions) {
            if (m.side && !completedSide.contains(m.id)
                    && (m.requires.isEmpty() || completed.contains(m.requires))) {
                out.add(m);
            }
        }
        return out;
    }

    /** Resumes a saved mission from a given objective index. */
    public void resume(Mission m, int objectiveIndex) {
        if (m == null) {
            return;
        }
        this.active = m;
        this.objectiveIndex = MathUtils.clamp(objectiveIndex, 0, m.objectives.size() - 1);
        missionTime = 0;
        objectiveTimer = 0;
        failed = false;
        failReason = "";
        outroPending = false;
        markerActive = false;
        pickups.clear();
        for (int i = 0; i < m.objectives.size(); i++) {
            m.objectives.get(i).done = i < this.objectiveIndex;
        }
        world.onMissionStarted(m);
        beginObjective();
    }

    public void start(Mission m) {
        if (m == null) {
            return;
        }
        active = m;
        objectiveIndex = 0;
        missionTime = 0;
        objectiveTimer = 0;
        checkpointTimer = 0;
        raceIndex = 0;
        raceLapsDone = 0;
        failed = false;
        failReason = "";
        outroPending = false;
        markerActive = false;
        pickups.clear();
        for (Objective o : m.objectives) {
            o.done = false;
        }
        world.onMissionStarted(m);
        if (!m.intro.isEmpty()) {
            showDialogue(m.intro, this::beginObjective);
        } else {
            beginObjective();
        }
    }

    private void beginObjective() {
        if (active == null) {
            return;
        }
        Objective ob = currentObjective();
        if (ob == null) {
            return;
        }
        objectiveTimer = 0;
        if ("race".equals(ob.type)) {
            raceCheckpoints.clear();
            for (String place : ob.route) {
                Content.Place p = Content.get(place);
                if (p != null) {
                    raceCheckpoints.add(new float[]{p.x, p.z});
                }
            }
            raceIndex = 0;
            markerX = raceCheckpoints.isEmpty() ? 0 : raceCheckpoints.get(0)[0];
            markerZ = raceCheckpoints.isEmpty() ? 0 : raceCheckpoints.get(0)[1];
            markerActive = !raceCheckpoints.isEmpty();
            markerLabel = raceCheckpoints.isEmpty() ? "" : "Checkpoint " + (raceIndex + 1);
        } else if ("collect".equals(ob.type) || "photos".equals(ob.type)) {
            spawnPickups(ob);
            markerActive = true;
            markerLabel = ob.text;
        } else if ("escape".equals(ob.type)) {
            if (ob.stars > 0) {
                world.police.crime(ob.stars);
            }
            markerActive = false;
        } else if ("survive".equals(ob.type)) {
            if (ob.stars > 0) {
                world.police.crime(ob.stars);
            }
            markerActive = false;
        } else if (!ob.target.isEmpty()) {
            Vehicle target = world.missionTarget(ob.target);
            if (target == null && active.targetTag.equals(ob.target)) {
                spawnMissionTarget();
                target = world.missionTarget(ob.target);
            }
            if (target != null) {
                markerX = target.x;
                markerZ = target.z;
            } else {
                Content.Place p = Content.get(ob.place);
                if (p != null) {
                    markerX = p.x;
                    markerZ = p.z;
                }
            }
            markerActive = true;
            markerLabel = ob.text;
        } else {
            Content.Place p = Content.get(ob.place);
            if (p == null && !active.spawnPlace.isEmpty()) {
                p = Content.get(active.spawnPlace);
            }
            if (p != null) {
                markerX = p.x;
                markerZ = p.z;
                markerActive = true;
                markerLabel = ob.text;
            }
        }
        if (!active.targetTag.isEmpty() && world.missionTarget(active.targetTag) == null
                && !active.targetRoute.isEmpty() && !"chase".equals(ob.type) && !"tail".equals(ob.type)) {
            spawnMissionTarget();
        }
        world.onObjectiveChanged(ob, markerActive, markerX, markerZ, markerLabel);
    }

    private void spawnPickups(Objective ob) {
        pickups.clear();
        Content.Place p = Content.get(ob.place);
        if (p == null) {
            return;
        }
        for (int i = 0; i < Math.max(1, ob.count); i++) {
            float ang = (float) (i * Math.PI * 2 / Math.max(1, ob.count));
            Pickup pk = new Pickup();
            pk.x = p.x + MathUtils.cos(ang) * (7f + i * 2f);
            pk.z = p.z + MathUtils.sin(ang) * (7f + i * 2f);
            pk.tag = ob.type + i;
            pickups.add(pk);
        }
    }

    private void spawnMissionTarget() {
        if (active == null || active.targetTag.isEmpty()) {
            return;
        }
        Content.Place start = Content.get(active.targetPlace);
        if (start == null) {
            start = Content.get(active.objectives.isEmpty() ? "duomo" : active.objectives.get(0).place);
        }
        if (start == null) {
            return;
        }
        it.pta.entity.VehicleType type = it.pta.entity.VehicleType.SEDAN;
        try {
            type = it.pta.entity.VehicleType.valueOf(active.targetType);
        } catch (Exception ignored) {
            // keep sedan
        }
        world.spawnMissionVehicle(active.targetTag, type, start.x, start.z, active.targetRoute,
                active.targetSpeed);
    }

    public Objective currentObjective() {
        if (active == null || objectiveIndex >= active.objectives.size()) {
            return null;
        }
        return active.objectives.get(objectiveIndex);
    }

    public void update(float dt) {
        updateDialogue(dt);
        if (active == null || outroPending) {
            if (outroPending) {
                updateDialogue(dt);
            }
            return;
        }
        missionTime += dt;
        objectiveTimer += dt;
        Objective ob = currentObjective();
        if (ob == null) {
            return;
        }
        if (active.timeLimit > 0 && missionTime > active.timeLimit && !"race".equals(ob.type)) {
            fail("Tempo scaduto");
            return;
        }
        if (active.failWanted > 0 && world.police.wanted >= active.failWanted) {
            fail("Ricercato troppo alto: missione fallita");
            return;
        }
        Vehicle target = ob.target.isEmpty() ? null : world.missionTarget(ob.target);
        if (target != null) {
            markerX = target.x;
            markerZ = target.z;
        }
        float px = world.player().x;
        float pz = world.player().z;
        float dist = (float) Math.hypot(markerX - px, markerZ - pz);

        switch (ob.type) {
            case "goto":
                if (dist < ob.radius) {
                    advance();
                }
                break;
            case "enter_vehicle": {
                Vehicle v = world.player().vehicle;
                if (v != null && matchesVehicle(v, ob.vehicle)) {
                    advance();
                }
                break;
            }
            case "drive_to":
                if (world.player().vehicle != null && dist < ob.radius) {
                    advance();
                }
                break;
            case "talk":
                if (dist < ob.radius) {
                    advance();
                }
                break;
            case "collect":
            case "photos": {
                int taken = 0;
                for (Pickup p : pickups) {
                    if (p.taken) {
                        taken++;
                        continue;
                    }
                    if (Math.hypot(p.x - px, p.z - pz) < 3.2f) {
                        p.taken = true;
                        taken++;
                        world.notify("Prova raccolta (" + taken + "/" + pickups.size() + ")");
                        world.audio.pickup();
                    }
                }
                if (taken >= pickups.size()) {
                    advance();
                }
                break;
            }
            case "escape":
                if (world.police.wanted == 0 && objectiveTimer > 4f) {
                    advance();
                }
                break;
            case "survive":
                if (objectiveTimer >= ob.time && world.police.wanted < 4) {
                    advance();
                }
                break;
            case "chase": {
                if (target == null) {
                    advance();
                    break;
                }
                if (dist < Math.max(ob.radius, 11f)) {
                    world.removeMissionVehicle(ob.target);
                    world.notify("Obiettivo raggiunto: " + ob.text);
                    advance();
                } else if (dist > 340f) {
                    fail("Il bersaglio è fuggito");
                }
                break;
            }
            case "tail": {
                if (target == null) {
                    advance();
                    break;
                }
                boolean tooClose = dist < 7f;
                boolean tooFar = dist > 120f;
                if (tooClose) {
                    objectiveTimer = Math.max(0, objectiveTimer - dt * 2f);
                }
                if (tooFar) {
                    fail("Hai perso di vista il bersaglio");
                    break;
                }
                if (objectiveTimer >= ob.time && !tooClose && !tooFar) {
                    advance();
                }
                break;
            }
            case "race": {
                if (raceCheckpoints.isEmpty()) {
                    advance();
                    break;
                }
                checkpointTimer += dt;
                float[] cp = raceCheckpoints.get(raceIndex);
                if (Math.hypot(cp[0] - px, cp[1] - pz) < ob.radius) {
                    raceIndex++;
                    if (raceIndex >= raceCheckpoints.size()) {
                        raceLapsDone++;
                        raceIndex = 0;
                        if (raceLapsDone >= ob.laps) {
                            advance();
                            break;
                        }
                    }
                    float[] next = raceCheckpoints.get(raceIndex);
                    markerX = next[0];
                    markerZ = next[1];
                    markerLabel = "Checkpoint " + (raceIndex + 1) + "/" + raceCheckpoints.size();
                    world.audio.checkpoint();
                    world.notify("Checkpoint " + (raceIndex) + "/" + raceCheckpoints.size());
                }
                break;
            }
            default:
                break;
        }
    }

    private boolean matchesVehicle(Vehicle v, String wanted) {
        if (wanted == null || wanted.isEmpty() || "ANY".equals(wanted)) {
            return true;
        }
        if ("CAR".equals(wanted)) {
            return v.type.kind == it.pta.entity.VehicleType.Kind.CAR
                    || v.type.kind == it.pta.entity.VehicleType.Kind.SPORT;
        }
        return v.type.name().equals(wanted);
    }

    private void advance() {
        Objective ob = currentObjective();
        if (ob != null) {
            ob.done = true;
        }
        objectiveIndex++;
        if (objectiveIndex >= active.objectives.size()) {
            completeMission();
        } else {
            world.audio.objective();
            beginObjective();
            world.onObjectiveChanged(currentObjective(), markerActive, markerX, markerZ, markerLabel);
        }
    }

    private void completeMission() {
        if (active == null) {
            return;
        }
        markerActive = false;
        world.removeAllMissionVehicles();
        final Mission done = active;
        if (!done.side) {
            completed.add(done.id);
        } else {
            completedSide.add(done.id);
        }
        world.economy.earn(done.reward, "Missione: " + done.title, world.playTime);
        world.audio.missionComplete();
        world.notify("Missione completata: " + done.title + "  +€" + Economy.format(done.reward));
        world.onMissionCompleted(done);
        active = null;
        if (!done.outro.isEmpty()) {
            outroPending = true;
            showDialogue(done.outro, () -> {
                outroPending = false;
                world.onCampaignProgress();
            });
        } else {
            world.onCampaignProgress();
        }
    }

    public void fail(String reason) {
        if (active == null) {
            return;
        }
        failed = true;
        failReason = reason;
        markerActive = false;
        world.removeAllMissionVehicles();
        world.onMissionFailed(active, reason);
    }

    public void abort() {
        if (active != null) {
            active = null;
            markerActive = false;
            pickups.clear();
            world.removeAllMissionVehicles();
            world.onCampaignProgress();
        }
    }

    public void restart() {
        if (failed) {
            Mission m = active;
            failed = false;
            if (m != null) {
                start(m);
            }
        }
    }

    public void showDialogue(List<Line> lines, Runnable onDone) {
        dialogue.clear();
        dialogue.addAll(lines);
        dialogueIndex = 0;
        dialogueTimer = 0;
        afterDialogue = onDone;
        dialogueActive = !lines.isEmpty();
    }

    private void updateDialogue(float dt) {
        if (!dialogueActive || dialogue.isEmpty()) {
            return;
        }
        dialogueTimer += dt;
        Line line = dialogue.get(dialogueIndex);
        if (dialogueTimer > line.duration) {
            advanceDialogue();
        }
    }

    public Line currentLine() {
        if (!dialogueActive || dialogue.isEmpty()) {
            return null;
        }
        return dialogue.get(Math.min(dialogueIndex, dialogue.size() - 1));
    }

    public void advanceDialogue() {
        if (!dialogueActive) {
            return;
        }
        dialogueIndex++;
        dialogueTimer = 0;
        world.audio.dialogueBlip();
        if (dialogueIndex >= dialogue.size()) {
            dialogueActive = false;
            Runnable after = afterDialogue;
            afterDialogue = null;
            if (after != null) {
                after.run();
            }
        }
    }

    public float missionTimer() {
        return missionTime;
    }

    public float distanceToMarker(World w) {
        if (!markerActive) {
            return 0;
        }
        return (float) Math.hypot(markerX - w.player().x, markerZ - w.player().z);
    }
}

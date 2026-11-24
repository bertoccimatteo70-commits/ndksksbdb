package it.pta.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * Three manual save slots plus autosave, stored as local JSON (fully offline).
 *
 * <p>Writes are atomic: the JSON goes to a temporary file first and is renamed over the
 * slot, so a crash or a full disk cannot leave a half-written save. The previous good
 * file is kept as {@code .bak} and used when the main file is unreadable, and every
 * loaded value is clamped: a corrupted or hand-edited file can no longer place the
 * player outside the province or produce NaN coordinates.</p>
 */
public final class SaveManager {
    public static final int SLOTS = 3;
    /** Bumped when the fields change; unknown newer versions are still read field by field. */
    public static final int VERSION = 2;

    public static final class SaveData {
        public int version = VERSION;
        public long timestamp;
        public float playTime;
        public int character;
        public boolean introSeen;
        public float x, z;
        public float health = 100;
        public float armor;
        public int cash = 2500;
        public int bank;
        public int wanted;
        public float timeOfDay = 9f;
        public String activeMission;
        public int objectiveIndex;
        public String[] completedMissions = new String[0];
        public String[] completedSide;
        public String[] properties = new String[0];
        public Economy.OwnedVehicle[] vehicles = new Economy.OwnedVehicle[0];
        public String[] clothes = new String[0];
        public int totalEarned;
        public int totalSpent;

        public String summary() {
            int minutes = (int) (playTime / 60f);
            String mission = activeMission == null ? "Libera esplorazione" : activeMission;
            return String.format("€ %s · %dh%02d · %s", Economy.format(cash + bank),
                    minutes / 60, minutes % 60, mission);
        }
    }

    private final FileHandle dir;
    private final Json json = new Json();
    /** Diagnostics for the HUD/log: unreadable files and successful recoveries. */
    public int corruptFiles;
    public int recoveries;

    public SaveManager(FileHandle dir) {
        this.dir = dir;
        json.setOutputType(JsonWriter.OutputType.json);
        json.setUsePrototypes(false);
    }

    public FileHandle fileFor(int slot) {
        return dir.child(slot == 0 ? "autosave.json" : "slot" + slot + ".json");
    }

    private FileHandle backupFor(int slot) {
        return dir.child(fileFor(slot).name() + ".bak");
    }

    public boolean exists(int slot) {
        return fileFor(slot).exists() || backupFor(slot).exists();
    }

    public SaveData newest() {
        SaveData best = null;
        for (int i = 0; i <= SLOTS; i++) {
            SaveData d = load(i);
            if (d != null && (best == null || d.timestamp > best.timestamp)) {
                best = d;
            }
        }
        return best;
    }

    public int newestSlot() {
        int bestSlot = -1;
        long best = -1;
        for (int i = 0; i <= SLOTS; i++) {
            SaveData d = load(i);
            if (d != null && d.timestamp > best) {
                best = d.timestamp;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    public boolean save(int slot, SaveData data) {
        if (data == null) {
            return false;
        }
        try {
            dir.mkdirs();
            data.version = VERSION;
            data.timestamp = System.currentTimeMillis();
            sanitize(data);
            FileHandle target = fileFor(slot);
            FileHandle temp = dir.child(target.name() + ".tmp");
            // 1. complete JSON into a temporary file
            temp.writeString(json.prettyPrint(data), false, "UTF-8");
            // 2. keep the last good file as a fallback
            if (target.exists()) {
                try {
                    target.copyTo(backupFor(slot));
                } catch (Exception ignored) {
                    // a missing backup is not a reason to lose the new save
                }
            }
            // 3. atomic replace
            if (target.exists() && !target.delete()) {
                throw new IllegalStateException("cannot replace " + target.name());
            }
            temp.moveTo(target);
            return true;
        } catch (Exception e) {
            logError("save failed in slot " + slot, e);
            return false;
        }
    }

    /** Gdx.app is absent in the JVM tests: logging must never be the failure. */
    private static void log(String message) {
        try {
            if (Gdx.app != null) {
                Gdx.app.log("PTA", message);
            }
        } catch (Throwable ignored) {
            // no application context
        }
    }

    private static void logError(String message, Throwable t) {
        try {
            if (Gdx.app != null) {
                Gdx.app.error("PTA", message, t);
            }
        } catch (Throwable ignored) {
            // no application context
        }
    }

    public SaveData load(int slot) {
        SaveData data = read(fileFor(slot));
        if (data != null) {
            return data;
        }
        FileHandle backup = backupFor(slot);
        data = read(backup);
        if (data != null) {
            recoveries++;
            log("save slot " + slot + " recovered from backup");
        }
        return data;
    }

    private SaveData read(FileHandle f) {
        if (f == null || !f.exists()) {
            return null;
        }
        try {
            String text = f.readString("UTF-8");
            if (text == null || text.trim().isEmpty()) {
                corruptFiles++;
                return null;
            }
            SaveData d = json.fromJson(SaveData.class, text);
            if (d == null) {
                corruptFiles++;
                return null;
            }
            sanitize(d);
            return d;
        } catch (Exception e) {
            corruptFiles++;
            log("unreadable save " + f.name());
            return null;
        }
    }

    /** Clamps everything a corrupted or hand-edited file could break. */
    static void sanitize(SaveData d) {
        if (!Float.isFinite(d.x) || !Float.isFinite(d.z)) {
            d.x = 0f;
            d.z = 0f;
        }
        d.x = clamp(d.x, -60000f, 60000f);
        d.z = clamp(d.z, -60000f, 60000f);
        if (!Float.isFinite(d.health)) {
            d.health = 100f;
        }
        if (!Float.isFinite(d.armor)) {
            d.armor = 0f;
        }
        if (!Float.isFinite(d.playTime)) {
            d.playTime = 0f;
        }
        if (!Float.isFinite(d.timeOfDay)) {
            d.timeOfDay = 9f;
        }
        d.health = clamp(d.health, 0f, 100f);
        d.armor = clamp(d.armor, 0f, 100f);
        d.playTime = Math.max(0f, d.playTime);
        d.timeOfDay = clamp(d.timeOfDay, 0f, 24f);
        d.cash = Math.max(0, d.cash);
        d.bank = Math.max(0, d.bank);
        d.wanted = Math.max(0, Math.min(5, d.wanted));
        d.objectiveIndex = Math.max(0, d.objectiveIndex);
        d.character = Math.max(0, Math.min(1, d.character));
        if (d.completedMissions == null) {
            d.completedMissions = new String[0];
        }
        if (d.properties == null) {
            d.properties = new String[0];
        }
        if (d.vehicles == null) {
            d.vehicles = new Economy.OwnedVehicle[0];
        }
        if (d.clothes == null) {
            d.clothes = new String[0];
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public boolean delete(int slot) {
        boolean ok = true;
        try {
            FileHandle f = fileFor(slot);
            if (f.exists()) {
                ok = f.delete();
            }
            FileHandle b = backupFor(slot);
            if (b.exists()) {
                b.delete();
            }
        } catch (Exception e) {
            return false;
        }
        return ok;
    }

    public List<Integer> existingSlots() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i <= SLOTS; i++) {
            if (exists(i)) {
                out.add(i);
            }
        }
        return out;
    }
}

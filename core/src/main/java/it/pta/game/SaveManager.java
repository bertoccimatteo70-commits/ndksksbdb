package it.pta.game;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;

import java.util.ArrayList;
import java.util.List;

/** Three manual save slots plus autosave, stored as local JSON (fully offline). */
public final class SaveManager {
    public static final int SLOTS = 3;

    public static final class SaveData {
        public int version = 1;
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

    public SaveManager(FileHandle dir) {
        this.dir = dir;
        json.setOutputType(JsonWriter.OutputType.json);
        json.setUsePrototypes(false);
    }

    public FileHandle fileFor(int slot) {
        return dir.child(slot == 0 ? "autosave.json" : "slot" + slot + ".json");
    }

    public boolean exists(int slot) {
        return fileFor(slot).exists();
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
        try {
            dir.mkdirs();
            data.timestamp = System.currentTimeMillis();
            fileFor(slot).writeString(json.prettyPrint(data), false, "UTF-8");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public SaveData load(int slot) {
        try {
            FileHandle f = fileFor(slot);
            if (!f.exists()) {
                return null;
            }
            return json.fromJson(SaveData.class, f.readString("UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    public boolean delete(int slot) {
        try {
            return fileFor(slot).delete();
        } catch (Exception e) {
            return false;
        }
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

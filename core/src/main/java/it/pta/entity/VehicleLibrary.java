package it.pta.entity;

import com.badlogic.gdx.utils.Disposable;
import it.pta.world.MapData;

import java.util.EnumMap;
import java.util.Map;

/** Shares one GPU model per vehicle type across traffic, police and the player. */
public final class VehicleLibrary implements Disposable {
    private final Map<VehicleType, VehicleModel> models = new EnumMap<>(VehicleType.class);
    private final MapData map;

    public VehicleLibrary(MapData map) {
        this.map = map;
    }

    public VehicleModel model(VehicleType type) {
        VehicleModel m = models.get(type);
        if (m == null) {
            m = new VehicleModel(type);
            models.put(type, m);
        }
        return m;
    }

    public Vehicle spawn(VehicleType type, float x, float z, float heading) {
        return new Vehicle(type, model(type), x, z, heading, map);
    }

    @Override
    public void dispose() {
        for (VehicleModel m : models.values()) {
            m.dispose();
        }
        models.clear();
    }
}

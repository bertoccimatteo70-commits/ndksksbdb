package it.pta.entity;

import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import it.pta.render.WorldRenderer;
import it.pta.world.MapData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * GTA-style wanted system: stars, pursuit vehicles, foot officers, roadblocks and
 * a hiding/heat model that lets the player escape.
 */
public final class PoliceSystem {
    public static final class Officer {
        public float x, y, z, heading, phase;
        public float arrestTimer;
    }

    private final MapData map;
    private final VehicleLibrary vehicles;
    private final Random rnd = new Random(77123L);
    public final List<Vehicle> squadCars = new ArrayList<>();
    public final List<Officer> officers = new ArrayList<>();

    public int wanted;
    public float heat;
    public float unseenTime;
    public float bustedTime;
    public boolean arrested;
    public float arrestProgress;
    public boolean roadblockActive;

    private float spawnCooldown;
    private float roadblockCooldown;
    private final Matrix4 xform = new Matrix4();
    private final Vector3 tmp = new Vector3();

    public PoliceSystem(MapData map, VehicleLibrary vehicles) {
        this.map = map;
        this.vehicles = vehicles;
    }

    public void crime(int levels) {
        wanted = Math.min(5, wanted + levels);
        heat = 0f;
        unseenTime = 0f;
    }

    public void clearWanted() {
        wanted = 0;
        heat = 0;
        unseenTime = 0;
        arresteesClear();
    }

    private void arresteesClear() {
        squadCars.clear();
        officers.clear();
    }

    public void update(float dt, Player player, PoliceHost host) {
        if (wanted <= 0) {
            heat = 0;
            unseenTime = 0;
            arresteesClear();
            arrestProgress = 0;
            return;
        }
        heat += dt;
        spawnCooldown -= dt;
        roadblockCooldown -= dt;

        if (spawnCooldown <= 0 && squadCars.size() < Math.min(4, 1 + wanted)) {
            spawnCooldown = 5.0f;
            spawnSquadCar(player);
        }

        boolean seen = false;
        Iterator<Vehicle> it = squadCars.iterator();
        while (it.hasNext()) {
            Vehicle car = it.next();
            if (car == player.vehicle) {
                continue;
            }
            car.sirenOn = true;
            float dist = (float) Math.hypot(car.x - player.x, car.z - player.z);
            if (dist > 600f) {
                it.remove();
                continue;
            }
            if (dist < 180f) {
                seen = true;
            }
            if (dist > 20f) {
                chaseWithCar(car, player, dt);
            } else {
                car.speed *= 0.90f;
                if (car.speed < 1f && officers.size() < 5) {
                    spawnOfficer(car.x, car.z, player);
                    it.remove();
                }
            }
            if (!player.inVehicle() && dist < 3.2f) {
                arrestProgress += dt * 1.2f;
            }
        }

        if (wanted >= 2 && roadblockCooldown <= 0 && !player.inVehicle()) {
            roadblockCooldown = 18f;
            spawnRoadblock(player);
        }

        Iterator<Officer> oit = officers.iterator();
        while (oit.hasNext()) {
            Officer o = oit.next();
            float dx = player.x - o.x;
            float dz = player.z - o.z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            if (dist > 260f) {
                oit.remove();
                continue;
            }
            if (dist < 60f) {
                seen = true;
            }
            if (dist > 1.2f) {
                float desired = (float) Math.atan2(dx, -dz);
                o.heading += MathUtils.clamp(wrap(desired - o.heading), -5f * dt, 5f * dt);
                float step = 3.4f * dt;
                float nx = o.x + MathUtils.sin(o.heading) * step;
                float nz = o.z - MathUtils.cos(o.heading) * step;
                if (!map.blocked(nx, nz, 0.35f)) {
                    o.x = nx;
                    o.z = nz;
                }
                o.phase += dt * 8f;
            } else if (!player.inVehicle()) {
                o.arrestTimer += dt;
                arrestProgress += dt * 1.6f;
            } else if (player.inVehicle()) {
                // try to pull the driver out when the car stops
                if (Math.abs(player.vehicle.speed) < 2f) {
                    arrestProgress += dt * 0.8f;
                }
            }
            o.y = map.heightAt(o.x, o.z);
        }

        if (seen) {
            unseenTime = 0;
        } else {
            unseenTime += dt;
            if (unseenTime > 9f) {
                unseenTime = 0;
                wanted = Math.max(0, wanted - 1);
                if (wanted == 0) {
                    arresteesClear();
                }
                host.onWantedReduced(wanted);
            }
        }
        if (arrestProgress > 2.4f) {
            arrested = true;
            arrestProgress = 0;
        } else {
            arrestProgress = Math.max(0, arrestProgress - dt * 0.45f);
        }
        if (wanted >= 4 && roadblockCooldown <= 0) {
            roadblockCooldown = 12f;
            spawnRoadblock(player);
        }
    }

    private void chaseWithCar(Vehicle car, Player player, float dt) {
        float desired = (float) Math.atan2(player.x - car.x, -(player.z - car.z));
        float diff = wrap(desired - car.heading);
        car.heading += MathUtils.clamp(diff, -2.6f * dt, 2.6f * dt);
        float targetSpeed = Math.min(car.type.topSpeed * 0.82f, 22f);
        car.speed += (targetSpeed - car.speed) * Math.min(1f, dt * 1.2f);
        float fx = MathUtils.sin(car.heading);
        float fz = -MathUtils.cos(car.heading);
        float nx = car.x + fx * car.speed * dt;
        float nz = car.z + fz * car.speed * dt;
        if (!map.blocked(nx, nz, 1.2f)) {
            car.x = nx;
            car.z = nz;
        } else {
            car.heading += 2.4f * dt;
            car.speed *= 0.6f;
        }
        car.y = map.heightAt(car.x, car.z);
        car.wheelSpin += car.speed / Math.max(0.2f, car.type.wheelRadius) * dt;
    }

    private void spawnSquadCar(Player player) {
        for (int attempt = 0; attempt < 12; attempt++) {
            float ang = rnd.nextFloat() * MathUtils.PI * 2;
            float dist = 160f + rnd.nextFloat() * 140f;
            float px = player.x + MathUtils.cos(ang) * dist;
            float pz = player.z + MathUtils.sin(ang) * dist;
            MapData.RoadHit hit = map.nearestRoad(px, pz, true, 45f);
            if (hit == null) {
                continue;
            }
            Vehicle car = vehicles.spawn(VehicleType.POLICE, hit.x, hit.z,
                    (float) Math.atan2(player.x - hit.x, -(player.z - hit.z)));
            car.sirenOn = true;
            car.aiRoad = hit.way;
            squadCars.add(car);
            return;
        }
    }

    private void spawnOfficer(float x, float z, Player player) {
        Officer o = new Officer();
        o.x = x + (rnd.nextFloat() - 0.5f) * 2f;
        o.z = z + (rnd.nextFloat() - 0.5f) * 2f;
        o.y = map.heightAt(o.x, o.z);
        o.heading = (float) Math.atan2(player.x - x, -(player.z - z));
        officers.add(o);
    }

    private void spawnRoadblock(Player player) {
        roadblockActive = true;
        float fx = MathUtils.sin(player.heading);
        float fz = -MathUtils.cos(player.heading);
        for (int side = -1; side <= 1; side += 2) {
            float px = player.x + fx * 95f + fz * side * 3.4f;
            float pz = player.z + fz * 95f - fx * side * 3.4f;
            MapData.RoadHit hit = map.nearestRoad(px, pz, true, 30f);
            if (hit == null) {
                continue;
            }
            Vehicle car = vehicles.spawn(VehicleType.POLICE, hit.x, hit.z,
                    player.heading + (float) Math.PI * 0.5f);
            car.sirenOn = true;
            car.speed = 0;
            squadCars.add(car);
        }
    }

    private static float wrap(float a) {
        while (a > MathUtils.PI) {
            a -= MathUtils.PI * 2;
        }
        while (a < -MathUtils.PI) {
            a += MathUtils.PI * 2;
        }
        return a;
    }

    public void draw(ShaderProgram shader, WorldRenderer renderer, Vector3 camPos,
                     Humanoid policeModel) {
        for (Vehicle car : squadCars) {
            car.draw(shader, renderer, true);
            car.collectLights(renderer, renderer.sun.night);
        }
        for (Officer o : officers) {
            xform.idt().translate(o.x, 0, o.z).rotate(Vector3.Y, -(float) Math.toDegrees(o.heading));
            shader.setUniformf("u_tint", 1f, 1f, 1f, 1f);
            policeModel.draw(shader, xform, o.phase, 0.85f, false, 0f, 0f, o.y);
            renderer.addShadow(o.x, o.z, 0.55f, o.y + 0.03f);
        }
    }

    public void reset() {
        wanted = 0;
        heat = 0;
        arrestProgress = 0;
        arrested = false;
        unseenTime = 0;
        arresteesClear();
    }

    public interface PoliceHost {
        void onWantedReduced(int wanted);
    }
}

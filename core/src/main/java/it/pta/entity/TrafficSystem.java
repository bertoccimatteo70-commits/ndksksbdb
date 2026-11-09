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
 * Ambient city life: cars following the real road network and pedestrians walking
 * the pavements. Spawn radius and counts are driven by the graphics preset.
 */
public final class TrafficSystem {
    public static final class Ped {
        public float x, y, z, heading, phase, speed;
        public MapData.RoadWay road;
        public float along;
        public int dir;
        public float life;
        public float scare;
        public float bobSeed;
    }

    private final MapData map;
    private final VehicleLibrary vehicles;
    private final List<Vehicle> cars = new ArrayList<>();
    private final List<Ped> peds = new ArrayList<>();
    private final Random rnd = new Random(20261109L);
    private final Matrix4 xform = new Matrix4();
    private final Vector3 tmp = new Vector3();

    public int maxCars = 14;
    public int maxPeds = 22;
    private float spawnTimer;
    private float pedTimer;
    public float lastCarHitPlayer;
    public float lastPedHit;
    public int pedsHitTotal;

    public TrafficSystem(MapData map, VehicleLibrary vehicles) {
        this.map = map;
        this.vehicles = vehicles;
    }

    public List<Vehicle> cars() {
        return cars;
    }

    public List<Ped> pedestrians() {
        return peds;
    }

    public void update(float dt, Player player, float night) {
        updateCars(dt, player);
        updatePeds(dt, player);
    }

    private void updateCars(float dt, Player player) {
        spawnTimer -= dt;
        if (spawnTimer <= 0) {
            spawnTimer = 0.55f;
            if (cars.size() < maxCars) {
                trySpawnCar(player, 110f, 330f);
            }
        }
        Iterator<Vehicle> it = cars.iterator();
        while (it.hasNext()) {
            Vehicle v = it.next();
            if (v == player.vehicle) {
                continue;
            }
            float dist = (float) Math.hypot(v.x - player.x, v.z - player.z);
            if (dist > 430f) {
                it.remove();
                continue;
            }
            driveAlongRoad(v, dt);
            // player collision
            float pd = (float) Math.hypot(v.x - player.x, v.z - player.z);
            if (!player.inVehicle() && pd < 1.5f + v.type.length * 0.18f && Math.abs(v.speed) > 1.2f) {
                player.damage(8f + Math.min(30f, Math.abs(v.speed) * 1.4f));
                lastCarHitPlayer = Math.abs(v.speed);
                v.speed *= 0.4f;
            }
        }
        // simple separation so cars do not overlap
        for (int i = 0; i < cars.size(); i++) {
            Vehicle a = cars.get(i);
            for (int j = i + 1; j < cars.size(); j++) {
                Vehicle b = cars.get(j);
                if (a == player.vehicle || b == player.vehicle) {
                    continue;
                }
                float dx = b.x - a.x, dz = b.z - a.z;
                float d2 = dx * dx + dz * dz;
                if (d2 < 9f && d2 > 0.0001f) {
                    float d = (float) Math.sqrt(d2);
                    float push = (3f - d) * 0.5f;
                    a.x -= dx / d * push;
                    a.z -= dz / d * push;
                    b.x += dx / d * push;
                    b.z += dz / d * push;
                    if (a.speed > b.speed) {
                        a.speed *= 0.7f;
                    }
                }
            }
        }
    }

    private void driveAlongRoad(Vehicle v, float dt) {
        if (v.aiRoad == null) {
            MapData.RoadHit hit = map.nearestRoad(v.x, v.z, true, 40f);
            if (hit == null) {
                return;
            }
            v.aiRoad = hit.way;
            v.aiSegment = hit.segment;
            v.aiDirection = 1;
        }
        float[] pts = v.aiRoad.pts;
        int segCount = v.aiRoad.segments();
        if (segCount <= 0) {
            v.aiRoad = null;
            return;
        }
        int s = MathUtils.clamp(v.aiSegment, 0, segCount - 1);
        int i0 = s * 2;
        int i1 = i0 + 2;
        if (i1 + 1 >= pts.length) {
            v.aiRoad = null;
            return;
        }
        float tx = pts[i1] - pts[i0];
        float tz = pts[i1 + 1] - pts[i0 + 1];
        float segLen = (float) Math.hypot(tx, tz);
        if (segLen < 0.2f) {
            v.aiSegment++;
            return;
        }
        float speed = v.aiCruise > 0 ? v.aiCruise : 9f;
        // slow down for vehicles ahead
        for (Vehicle other : cars) {
            if (other == v) {
                continue;
            }
            float dx = other.x - v.x, dz = other.z - v.z;
            float ahead = dx * MathUtils.sin(v.heading) - dz * MathUtils.cos(v.heading);
            float lateral = Math.abs(dx * MathUtils.cos(v.heading) + dz * MathUtils.sin(v.heading));
            if (ahead > 0 && ahead < 9f && lateral < 2.2f) {
                speed = Math.min(speed, Math.max(0, ahead * 0.9f));
            }
        }
        v.aiAlong += speed * dt;
        while (v.aiAlong >= segLen) {
            v.aiAlong -= segLen;
            v.aiSegment++;
            if (v.aiSegment >= segCount) {
                v.aiSegment = 0;
                v.aiDirection = 1;
                pickNextRoad(v);
                return;
            }
            s = v.aiSegment;
            i0 = s * 2;
            i1 = i0 + 2;
            if (i1 + 1 >= pts.length) {
                return;
            }
            tx = pts[i1] - pts[i0];
            tz = pts[i1 + 1] - pts[i0 + 1];
            segLen = (float) Math.hypot(tx, tz);
            if (segLen < 0.2f) {
                continue;
            }
        }
        float t = v.aiAlong / Math.max(0.2f, segLen);
        float px = pts[i0] + (pts[i1] - pts[i0]) * t;
        float pz = pts[i0 + 1] + (pts[i1 + 1] - pts[i0 + 1]) * t;
        // lane offset to the right
        float dirSign = v.aiDirection >= 0 ? 1 : -1;
        float nx = (tz / segLen) * dirSign;
        float nz = (-tx / segLen) * dirSign;
        float laneOff = Math.min(2.4f, v.aiRoad.width * 0.24f);
        float targetX = px + nx * laneOff;
        float targetZ = pz + nz * laneOff;
        float desired = (float) Math.atan2(targetX - v.x, -(targetZ - v.z));
        float diff = wrap(desired - v.heading);
        v.heading += MathUtils.clamp(diff, -2.4f * dt, 2.4f * dt);
        v.speed += (speed - v.speed) * Math.min(1f, dt * 1.6f);
        float fx = MathUtils.sin(v.heading);
        float fz = -MathUtils.cos(v.heading);
        float nxPos = v.x + fx * v.speed * dt;
        float nzPos = v.z + fz * v.speed * dt;
        if (!map.blocked(nxPos, nzPos, 0.9f)) {
            v.x = nxPos;
            v.z = nzPos;
        } else {
            v.speed *= 0.5f;
        }
        v.y = map.heightAt(v.x, v.z);
        v.wheelSpin += v.speed / Math.max(0.2f, v.type.wheelRadius) * dt;
    }

    private void pickNextRoad(Vehicle v) {
        float ex = v.aiRoad.pts[v.aiRoad.pts.length - 2];
        float ez = v.aiRoad.pts[v.aiRoad.pts.length - 1];
        MapData.RoadHit hit = map.nearestRoad(ex, ez, true, 25f);
        if (hit != null && hit.way != v.aiRoad) {
            v.aiRoad = hit.way;
            v.aiSegment = hit.segment;
            v.aiAlong = 0;
            v.aiDirection = hit.way.pts[0] == ex && hit.way.pts[1] == ez ? 1 : 1;
        } else {
            v.aiSegment = 0;
            v.aiAlong = 0;
            v.heading += MathUtils.PI;
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

    private void trySpawnCar(Player player, float minDist, float maxDist) {
        for (int attempt = 0; attempt < 8; attempt++) {
            float ang = rnd.nextFloat() * MathUtils.PI * 2;
            float d = minDist + rnd.nextFloat() * (maxDist - minDist);
            float px = player.x + MathUtils.cos(ang) * d;
            float pz = player.z + MathUtils.sin(ang) * d;
            MapData.RoadHit hit = map.nearestRoad(px, pz, true, 35f);
            if (hit == null) {
                continue;
            }
            VehicleType type = randomType();
            Vehicle v = vehicles.spawn(type, hit.x, hit.z, 0f);
            v.aiRoad = hit.way;
            v.aiSegment = hit.segment;
            v.aiAlong = 0.5f;
            v.aiCruise = 6f + rnd.nextFloat() * Math.min(12f, type.topSpeed * 0.35f);
            v.aiDirection = 1;
            v.heading = (float) Math.atan2(
                    hit.way.pts[Math.min(hit.way.pts.length - 2, (hit.segment + 1) * 2)] - hit.x,
                    -(hit.way.pts[Math.min(hit.way.pts.length - 1, (hit.segment + 1) * 2 + 1)] - hit.z));
            cars.add(v);
            return;
        }
    }

    private VehicleType randomType() {
        int r = rnd.nextInt(100);
        if (r < 34) return VehicleType.HATCHBACK;
        if (r < 58) return VehicleType.SEDAN;
        if (r < 70) return VehicleType.SUV;
        if (r < 79) return VehicleType.VAN;
        if (r < 85) return VehicleType.SPORT;
        if (r < 89) return VehicleType.TRUCK;
        if (r < 93) return VehicleType.MOTORCYCLE;
        if (r < 97) return VehicleType.SCOOTER;
        return VehicleType.BUS;
    }

    private void updatePeds(float dt, Player player) {
        pedTimer -= dt;
        if (pedTimer <= 0) {
            pedTimer = 0.35f;
            if (peds.size() < maxPeds) {
                trySpawnPed(player);
            }
        }
        Iterator<Ped> it = peds.iterator();
        while (it.hasNext()) {
            Ped p = it.next();
            float dist = (float) Math.hypot(p.x - player.x, p.z - player.z);
            if (dist > 240f) {
                it.remove();
                continue;
            }
            p.life += dt;
            if (p.scare > 0) {
                p.scare -= dt;
                float away = (float) Math.atan2(p.x - player.x, -(p.z - player.z));
                p.heading += MathUtils.clamp(wrap(away - p.heading), -3f * dt, 3f * dt);
            }
            // dodge traffic
            for (Vehicle v : cars) {
                float d = (float) Math.hypot(v.x - p.x, v.z - p.z);
                if (d < 7f && Math.abs(v.speed) > 3f) {
                    p.scare = Math.max(p.scare, 1.4f);
                }
            }
            float speed = p.scare > 0 ? 4.2f : p.speed;
            float nx = p.x + MathUtils.sin(p.heading) * speed * dt;
            float nz = p.z - MathUtils.cos(p.heading) * speed * dt;
            float[] pos = {nx, 0, nz};
            if (map.blocked(nx, nz, 0.35f)) {
                p.heading += 1.6f;
            } else {
                p.x = nx;
                p.z = nz;
            }
            p.y = map.heightAt(p.x, p.z);
            p.phase += dt * (speed > 3f ? 7.5f : 4.2f);

            if (!player.inVehicle() && Math.hypot(p.x - player.x, p.z - player.z) < 1.4f
                    && player.isPunching() && p.scare < 0.2f) {
                p.scare = 2.5f;
                lastPedHit = 1f;
                pedsHitTotal++;
            }
            // knocked by the player's car
            if (player.inVehicle()) {
                Vehicle v = player.vehicle;
                if (Math.hypot(v.x - p.x, v.z - p.z) < 2.2f && Math.abs(v.speed) > 4f) {
                    p.scare = 3f;
                    p.heading += MathUtils.PI * 0.6f;
                    lastPedHit = Math.abs(v.speed);
                }
            }
            // wander around a bounded route
            if (p.life > 30f && p.road != null) {
                it.remove();
            }
        }
    }

    private void trySpawnPed(Player player) {
        for (int attempt = 0; attempt < 6; attempt++) {
            float ang = rnd.nextFloat() * MathUtils.PI * 2;
            float d = 45f + rnd.nextFloat() * 120f;
            float px = player.x + MathUtils.cos(ang) * d;
            float pz = player.z + MathUtils.sin(ang) * d;
            if (map.blocked(px, pz, 0.5f)) {
                continue;
            }
            MapData.RoadHit hit = map.nearestRoad(px, pz, false, 30f);
            if (hit == null) {
                continue;
            }
            Ped p = new Ped();
            p.x = hit.x + (rnd.nextFloat() - 0.5f) * 4f;
            p.z = hit.z + (rnd.nextFloat() - 0.5f) * 4f;
            p.y = map.heightAt(p.x, p.z);
            p.heading = rnd.nextFloat() * MathUtils.PI * 2;
            p.speed = 1.1f + rnd.nextFloat() * 0.6f;
            p.phase = rnd.nextFloat() * 6f;
            p.road = hit.way;
            p.bobSeed = rnd.nextFloat();
            peds.add(p);
            return;
        }
    }

    public void drawCars(ShaderProgram shader, WorldRenderer renderer, Vector3 camPos, float night) {
        for (Vehicle v : cars) {
            float d2 = camPos.dst2(v.x, v.y, v.z);
            if (d2 > 520f * 520f) {
                continue;
            }
            v.draw(shader, renderer, d2 < 130f * 130f);
            v.collectLights(renderer, night);
        }
    }

    public void drawPeds(ShaderProgram shader, WorldRenderer renderer, Vector3 camPos,
                         Humanoid[] models) {
        for (Ped p : peds) {
            if (camPos.dst2(p.x, p.y, p.z) > 260f * 260f) {
                continue;
            }
            Humanoid h = models[(int) (Math.abs(p.bobSeed * 1000) % models.length)];
            xform.idt().translate(p.x, 0, p.z).rotate(Vector3.Y, -(float) Math.toDegrees(p.heading));
            shader.setUniformf("u_tint", 1f, 1f, 1f, 1f);
            h.draw(shader, xform, p.phase, p.scare > 0 ? 1f : 0.45f, false, 0f, 0f, p.y);
            renderer.addShadow(p.x, p.z, 0.5f, p.y + 0.03f);
        }
    }

    public void clear() {
        cars.clear();
        peds.clear();
    }
}

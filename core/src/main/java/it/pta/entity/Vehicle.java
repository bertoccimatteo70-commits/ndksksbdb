package it.pta.entity;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import it.pta.render.WorldRenderer;
import it.pta.world.MapData;

/** Arcade vehicle physics with fuel, damage, terrain following and per-class handling. */
public final class Vehicle {
    public static final float[][] RIMS = {
            {0.78f, 0.80f, 0.82f}, {0.10f, 0.10f, 0.11f},
            {0.74f, 0.56f, 0.22f}, {0.90f, 0.91f, 0.93f}
    };

    public static final float[][] PAINTS = {
            {0.72f, 0.10f, 0.10f}, {0.10f, 0.20f, 0.42f}, {0.86f, 0.86f, 0.84f},
            {0.10f, 0.10f, 0.11f}, {0.55f, 0.57f, 0.60f}, {0.20f, 0.42f, 0.26f},
            {0.80f, 0.62f, 0.16f}, {0.36f, 0.14f, 0.30f}, {0.28f, 0.34f, 0.38f},
            {0.62f, 0.34f, 0.16f}
    };

    public final VehicleType type;
    public final VehicleModel model;
    public String displayName;
    public float x, y, z;
    public float heading;
    public float speed;
    public float steer;
    public float wheelSpin;
    public float rotorSpin;
    public float fuel;
    public float damage;
    public float fuelCapacity;
    public float[] paint = {0.8f, 0.8f, 0.8f};
    public boolean engineOn = true;
    public boolean isPolice;
    public boolean sirenOn;
    public boolean isPlayerOwned;
    public boolean parked;
    public int rimIndex;
    public float lastImpact;
    public float pitchAngle, rollAngle;

    // simple AI bookkeeping used by traffic and police systems
    public MapData.RoadWay aiRoad;
    public int aiSegment;
    public float aiAlong;
    public int aiDirection = 1;
    public float aiCruise;
    public float aiStuck;

    private final Matrix4 base = new Matrix4();
    private final Matrix4 tmp = new Matrix4();
    private final Matrix4 wheelXf = new Matrix4();
    private final Vector3 tint = new Vector3(1, 1, 1);
    private final float[] probe = new float[3];

    public Vehicle(VehicleType type, VehicleModel model, float x, float z, float heading, MapData map) {
        this.type = type;
        this.model = model;
        this.x = x;
        this.z = z;
        this.heading = heading;
        this.y = map.heightAt(x, z);
        this.displayName = type.label;
        this.fuelCapacity = type.fuelCapacity;
        this.fuel = type.fuelCapacity;
        this.isPolice = type == VehicleType.POLICE;
        int c = (int) ((Math.abs(x * 31 + z * 17)) % PAINTS.length);
        this.paint = PAINTS[c].clone();
    }

    public void setPaint(int index) {
        paint = PAINTS[Math.floorMod(index, PAINTS.length)].clone();
    }

    public void refuel() {
        fuel = fuelCapacity;
    }

    public void repair() {
        damage = 0;
        engineOn = true;
    }

    public boolean brokenDown() {
        return damage >= 99.5f || fuel <= 0.01f;
    }

    public boolean isAir() {
        return type.kind == VehicleType.Kind.HELI || type.kind == VehicleType.Kind.PLANE;
    }

    public boolean isBoat() {
        return type.kind == VehicleType.Kind.BOAT;
    }

    /**
     * @param throttle -1 (brake/reverse) .. 1 (accelerate)
     * @param steerIn  -1 (left) .. 1 (right)
     */
    public void update(float dt, MapData map, float throttle, float steerIn, boolean handbrake,
                       boolean lights, float gripFactor) {
        lastImpact = Math.max(0f, lastImpact - dt * 2f);
        this.steer = MathUtils.clamp(steerIn, -1f, 1f);

        if (isAir()) {
            updateAir(dt, map, throttle, steerIn);
            return;
        }

        MapData.Area area = map.areaAt(x, z);
        boolean onWater = area != null && area.kind == 13;
        if (isBoat() && !onWater) {
            speed *= 0.90f;
        }
        if (!isBoat() && onWater) {
            // cars can ford shallow water slowly, boats cannot drive on roads
            throttle *= 0.4f;
        }

        boolean onRoad = map.nearestRoad(x, z, true, 28f) != null;
        float surf = (onRoad ? 1f : 0.72f) * gripFactor * (handbrake ? 0.55f : 1f);
        if (isBoat()) {
            surf = onWater ? 1f : 0.2f;
        }
        float maxSpeed = type.topSpeed * surf;
        float maxReverse = -Math.min(9f, type.topSpeed * 0.30f);

        boolean hasPower = fuel > 0.01f && damage < 99.5f;
        if (hasPower) {
            if (throttle > 0.02f) {
                float headroom = 1f - Math.max(0f, speed) / Math.max(1f, maxSpeed);
                speed += type.accel * throttle * headroom * dt;
            } else if (throttle < -0.02f) {
                if (speed > 0.3f) {
                    speed -= type.brake * -throttle * dt;
                } else {
                    speed -= type.accel * 0.35f * -throttle * dt;
                }
            }
        }
        // drag and rolling resistance
        speed -= speed * (0.055f + Math.abs(speed) * 0.0022f) * dt;
        float roll = (handbrake ? 7.5f : 0.85f) * dt;
        if (Math.abs(speed) < roll) {
            speed = 0;
        } else {
            speed -= Math.signum(speed) * roll;
        }
        if (handbrake) {
            speed *= 1f - Math.min(0.9f, dt * 2.2f);
        }
        speed = MathUtils.clamp(speed, maxReverse, maxSpeed);

        float steerAuthority = Math.min(0.3f + Math.abs(speed) / 7f, 1.15f);
        float turn = this.steer * type.steerRate * dt * steerAuthority * 0.5f;
        if (speed < -0.2f) {
            turn = -turn;
        }
        heading += Math.toRadians(turn);
        heading = (float) ((heading + Math.PI * 4) % (Math.PI * 2));

        float fx = MathUtils.sin(heading);
        float fz = -MathUtils.cos(heading);
        float nx = x + fx * speed * dt;
        float nz = z + fz * speed * dt;

        // four-corner collision test against buildings
        float hw = type.width * 0.42f;
        float hl = type.length * 0.42f;
        boolean blocked = false;
        for (int i = 0; i < 4; i++) {
            float ox = (i % 2 == 0 ? -hw : hw);
            float oz = (i < 2 ? -hl : hl);
            float px = nx + MathUtils.cos(heading) * ox + fx * oz;
            float pz = nz - MathUtils.sin(heading) * ox + fz * oz;
            if (map.blocked(px, pz, 0.35f)) {
                blocked = true;
                break;
            }
        }
        if (blocked) {
            float impact = Math.abs(speed);
            if (impact > 3f) {
                damage = Math.min(100f, damage + impact * 2.4f);
                lastImpact = impact;
            }
            speed = -speed * 0.18f;
        } else {
            x = nx;
            z = nz;
        }
        x = MathUtils.clamp(x, map.minX + 30, map.maxX - 30);
        z = MathUtils.clamp(z, map.minZ + 30, map.maxZ - 30);

        y = map.heightAt(x, z);
        if (isBoat()) {
            y = Math.max(y, 0f);
        }

        // follow the terrain under the wheels
        float hf = map.heightAt(x + fx * type.wheelBase * 0.5f, z + fz * type.wheelBase * 0.5f);
        float hb = map.heightAt(x - fx * type.wheelBase * 0.5f, z - fz * type.wheelBase * 0.5f);
        float hLeft = map.heightAt(x + MathUtils.cos(heading) * hw, z - MathUtils.sin(heading) * hw);
        float hRight = map.heightAt(x - MathUtils.cos(heading) * hw, z + MathUtils.sin(heading) * hw);
        pitchAngle = (float) Math.atan2(hb - hf, type.wheelBase);
        rollAngle = (float) Math.atan2(hRight - hLeft, type.width);
        if (isBoat()) {
            pitchAngle = (float) Math.sin(System.nanoTime() * 1e-9 + x) * 0.02f;
        }

        float consumption = (0.014f + Math.abs(throttle) * 0.075f) * (0.6f + type.topSpeed / 60f);
        fuel = Math.max(0f, fuel - consumption * dt);
        wheelSpin += (speed / Math.max(0.15f, type.wheelRadius)) * dt;
        if (wheelSpin > 1000f) {
            wheelSpin %= (float) (Math.PI * 2);
        }
        if (damage > 60f) {
            engineOn = damage < 95f;
        }
    }

    private void updateAir(float dt, MapData map, float throttle, float steerIn) {
        boolean heli = type.kind == VehicleType.Kind.HELI;
        float ground = map.heightAt(x, z);
        float alt = y - ground;
        if (heli) {
            speed += (throttle * type.accel * 0.5f - speed * 0.5f) * dt;
            speed = MathUtils.clamp(speed, -8f, type.topSpeed);
            if (throttle > 0.1f) {
                y += (2.5f + throttle * 6f) * dt;
            } else if (throttle < -0.1f) {
                y -= 5.5f * dt;
            } else {
                y += (float) Math.sin(System.nanoTime() * 1e-9) * 0.05f;
            }
            y = Math.max(ground + 0.4f, Math.min(ground + 900f, y));
            rotorSpin += dt * (18f + Math.abs(throttle) * 22f);
        } else {
            speed += type.accel * throttle * 0.55f * dt;
            speed = MathUtils.clamp(speed, 8f, type.topSpeed);
            float lift = MathUtils.clamp(speed / type.topSpeed, 0.45f, 1f);
            y += (speed * 0.09f * lift) * dt;
            if (throttle < -0.2f) {
                y -= 3.4f * dt;
            }
            y = Math.max(ground + 1.2f, Math.min(ground + 1500f, y));
        }
        heading += Math.toRadians(steerIn * type.steerRate * dt * 0.55f);
        float fx = MathUtils.sin(heading);
        float fz = -MathUtils.cos(heading);
        x += fx * speed * dt;
        z += fz * speed * dt;
        x = MathUtils.clamp(x, map.minX + 80, map.maxX - 80);
        z = MathUtils.clamp(z, map.minZ + 80, map.maxZ - 80);
        pitchAngle = (float) Math.sin(System.nanoTime() * 1e-9) * 0.02f;
        rollAngle = -steerIn * 0.12f;
        fuel = Math.max(0f, fuel - (0.05f + Math.abs(throttle) * 0.12f) * dt);
        lastImpact = 0;
    }

    public void draw(ShaderProgram shader, WorldRenderer renderer, boolean detailed) {
        float yOffset = 0f;
        base.idt().translate(x, y + yOffset, z)
                .rotate(Vector3.Y, -(float) Math.toDegrees(heading))
                .rotate(Vector3.X, (float) Math.toDegrees(pitchAngle))
                .rotate(Vector3.Z, (float) Math.toDegrees(rollAngle));
        tint.set(paint[0], paint[1], paint[2]);
        if (isPolice) {
            tint.set(1f, 1f, 1f);
        }
        if (model.body != null) {
            shader.setUniformf("u_tint", tint.x, tint.y, tint.z, 1f);
            shader.setUniformMatrix("u_model", base);
            model.body.render(shader, GL20.GL_TRIANGLES);
        }
        if (detailed && model.wheel != null) {
            float[] rim = RIMS[Math.floorMod(rimIndex, RIMS.length)];
            shader.setUniformf("u_tint", rim[0], rim[1], rim[2], 1f);
            int count = model.wheelX.length;
            for (int i = 0; i < count; i++) {
                boolean front = model.wheelZ[i] < 0;
                wheelXf.set(base)
                        .translate(model.wheelX[i], model.wheelRadius, -model.wheelZ[i])
                        .rotate(Vector3.Y, front ? -(float) Math.toDegrees(steer) * 0.5f : 0f)
                        .rotate(Vector3.X, (float) Math.toDegrees(wheelSpin));
                shader.setUniformMatrix("u_model", wheelXf);
                model.wheel.render(shader, GL20.GL_TRIANGLES);
            }
        }
        if (detailed && model.rotor != null) {
            tmp.set(base).translate(0, type.height * 0.95f, 0).rotate(Vector3.Y, (float) Math.toDegrees(rotorSpin));
            shader.setUniformMatrix("u_model", tmp);
            shader.setUniformf("u_tint", 1f, 1f, 1f, 1f);
            model.rotor.render(shader, GL20.GL_TRIANGLES);
        }
        renderer.addShadow(x, z, type.length * 0.42f, mapGroundShade(renderer) + 0.03f);
    }

    private float mapGroundShade(WorldRenderer renderer) {
        return renderer.map().heightAt(x, z);
    }

    /** Adds head/tail light glows at night. */
    public void collectLights(WorldRenderer renderer, float night) {
        if (night < 0.25f) {
            return;
        }
        float fx = MathUtils.sin(heading);
        float fz = -MathUtils.cos(heading);
        float front = type.length * 0.52f;
        releaseGlow(renderer, x + fx * front, y + 0.7f, z + fz * front, 1.6f, 1f, 0.92f, 0.72f);
        if (isPolice && sirenOn) {
            float t = (float) (System.nanoTime() * 1e-9);
            float blink = (float) (Math.sin(t * 9f) * 0.5f + 0.5f);
            releaseGlow(renderer, x, y + type.height + 0.2f, z, 2.6f,
                    0.9f * blink, 0.15f, 0.15f);
            releaseGlow(renderer, x, y + type.height + 0.2f, z, 2.6f,
                    0.15f, 0.25f, 1.0f * (1f - blink));
        }
    }

    private void releaseGlow(WorldRenderer r, float px, float py, float pz, float size,
                             float rr, float gg, float bb) {
        r.addGlow(px, py, pz, size, rr, gg, bb);
    }

    public void dispose() {
        // models are shared through VehicleLibrary
    }

    public float groundHeight(MapData map) {
        return map.heightAt(x, z);
    }

    public static String plateFor(int seed) {
        String letters = "ABCDEFGHJKLMNPRSTVWXYZ";
        StringBuilder sb = new StringBuilder();
        sb.append(letters.charAt(Math.abs(seed) % letters.length()));
        sb.append(letters.charAt((Math.abs(seed) / 7) % letters.length()));
        sb.append(' ');
        sb.append(100 + Math.abs(seed / 13) % 900);
        sb.append(letters.charAt((Math.abs(seed) / 31) % letters.length()));
        sb.append(letters.charAt((Math.abs(seed) / 53) % letters.length()));
        return sb.toString();
    }

}

package it.pta.entity;

import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import it.pta.render.WorldRenderer;
import it.pta.world.MapData;

/** On-foot protagonist state: movement, collision, stamina, health and melee. */
public final class Player {
    public float x, y, z;
    public float heading;
    public float vy;
    public boolean grounded = true;
    public float health = 100f;
    public float armor = 0f;
    public float stamina = 100f;
    public int character;
    public Humanoid humanoid;
    public Vehicle vehicle;
    public float animPhase;
    public float speed;
    public float punchTimer;
    public float hurtTimer;
    public float walkSpeed = 2.2f;
    public float runSpeed = 5.9f;

    private final Matrix4 xform = new Matrix4();
    private float lastVx, lastVz;
    private float punchCooldown;

    public Player(int character, Humanoid humanoid) {
        this.character = character;
        this.humanoid = humanoid;
    }

    /** Walkable interior mode, used by safehouses and garages. */
    public boolean indoor;
    public float roomMinX, roomMaxX, roomMinZ, roomMaxZ, roomFloorY;

    public boolean inVehicle() {
        return vehicle != null;
    }

    public void setPosition(float px, float pz, MapData map) {
        x = px;
        z = pz;
        y = map.heightAt(px, pz);
        vy = 0;
        grounded = true;
        lastVx = 0;
        lastVz = 0;
        speed = 0;
    }

    public void update(float dt, MapData map, float inputX, float inputY, float camYaw,
                       boolean run, boolean jump, boolean attack) {
        punchTimer = Math.max(0, punchTimer - dt);
        punchCooldown = Math.max(0, punchCooldown - dt);
        hurtTimer = Math.max(0, hurtTimer - dt);

        if (inVehicle()) {
            Vehicle v = vehicle;
            x = v.x;
            z = v.z;
            y = v.y;
            speed = Math.abs(v.speed);
            heading = v.heading;
            animPhase += dt * 2f;
            return;
        }

        float len = (float) Math.sqrt(inputX * inputX + inputY * inputY);
        if (len > 1f) {
            inputX /= len;
            inputY /= len;
            len = 1f;
        }
        boolean canRun = run && stamina > 1f && grounded && len > 0.2f;
        float targetSpeed = canRun ? runSpeed : walkSpeed;
        float wx = (MathUtils.sin(camYaw) * inputY + MathUtils.cos(camYaw) * inputX) * targetSpeed;
        float wz = (-MathUtils.cos(camYaw) * inputY + MathUtils.sin(camYaw) * inputX) * targetSpeed;
        if (len < 0.05f) {
            wx = 0;
            wz = 0;
        }
        float ax = wx - lastVx;
        float az = wz - lastVz;
        float maxDelta = (grounded ? 26f : 5f) * dt;
        float al = (float) Math.sqrt(ax * ax + az * az);
        if (al > maxDelta && al > 0) {
            ax = ax / al * maxDelta;
            az = az / al * maxDelta;
        }
        lastVx += ax;
        lastVz += az;
        float sp = (float) Math.sqrt(lastVx * lastVx + lastVz * lastVz);
        if (sp > targetSpeed) {
            lastVx = lastVx / sp * targetSpeed;
            lastVz = lastVz / sp * targetSpeed;
        }
        x += lastVx * dt;
        z += lastVz * dt;
        speed = (float) Math.sqrt(lastVx * lastVx + lastVz * lastVz);

        if (indoor) {
            x = MathUtils.clamp(x, roomMinX + 0.4f, roomMaxX - 0.4f);
            z = MathUtils.clamp(z, roomMinZ + 0.4f, roomMaxZ - 0.4f);
            y = roomFloorY;
            vy = 0;
            grounded = true;
        } else {
            float[] pos = {x, 0, z};
            if (map.resolveCollision(pos, 0.34f)) {
                x = pos[0];
                z = pos[2];
                lastVx *= 0.25f;
                lastVz *= 0.25f;
            }
            x = MathUtils.clamp(x, map.minX + 20, map.maxX - 20);
            z = MathUtils.clamp(z, map.minZ + 20, map.maxZ - 20);
        }

        float ground = indoor ? roomFloorY : map.heightAt(x, z);
        if (jump && grounded) {
            vy = 6.5f;
            grounded = false;
        }
        vy -= 22f * dt;
        y += vy * dt;
        if (y <= ground) {
            y = ground;
            vy = 0;
            grounded = true;
        }
        if (canRun && speed > 1f) {
            stamina = Math.max(0, stamina - dt * 9f);
        } else {
            stamina = Math.min(100f, stamina + dt * 14f);
        }

        if (speed > 0.3f) {
            heading = indoor ? heading : (float) Math.atan2(lastVx, -lastVz);
            animPhase += dt * (speed / (walkSpeed * 0.9f)) * 3.4f;
        } else {
            animPhase += dt * 1.2f;
        }

        if (attack && punchCooldown <= 0f && grounded) {
            punchTimer = 0.32f;
            punchCooldown = 0.5f;
        }
    }

    public void stop() {
        lastVx = 0;
        lastVz = 0;
        speed = 0;
    }

    public boolean isPunching() {
        return punchTimer > 0.18f;
    }

    public void damage(float amount) {
        if (armor > 0) {
            float absorbed = Math.min(armor, amount * 0.7f);
            armor -= absorbed;
            amount -= absorbed;
        }
        health = Math.max(0, health - amount);
        hurtTimer = 0.6f;
    }

    public void heal(float amount) {
        health = Math.min(100f, health + amount);
    }

    public void draw(ShaderProgram shader, WorldRenderer renderer) {
        if (inVehicle() || humanoid == null) {
            return;
        }
        float sp = MathUtils.clamp(speed / runSpeed, 0f, 1f);
        xform.idt().translate(x, 0, z).rotate(Vector3.Y, -(float) Math.toDegrees(heading));
        float punch = punchTimer > 0 ? punchTimer / 0.32f : 0f;
        shader.setUniformf("u_tint", 1f, 1f, 1f, 1f);
        humanoid.draw(shader, xform, animPhase, sp, !grounded, punch, 0f, y);
        renderer.addShadow(x, z, 0.62f, y + 0.04f);
    }

    public String protagonistName() {
        return character == 0 ? "Marco Bellandi" : "Elisa Vannucci";
    }
}

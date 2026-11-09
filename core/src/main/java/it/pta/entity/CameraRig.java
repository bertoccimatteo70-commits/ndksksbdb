package it.pta.entity;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import it.pta.world.MapData;

/** Camera controller: orbit third person, first person and vehicle chase views. */
public final class CameraRig {
    public final PerspectiveCamera camera;
    public float yaw = 0f; // radians, 0 looks north (-Z)
    public float pitch = 0.24f;
    public boolean firstPerson;
    public float distance = 5.2f;
    public float sensitivity = 1f;

    private final Vector3 target = new Vector3();
    private final Vector3 offset = new Vector3();
    private final Vector3 look = new Vector3();
    private final float[] probe = new float[3];

    public CameraRig(float aspect) {
        camera = new PerspectiveCamera(68f, 16, 9);
        camera.near = 0.25f;
        camera.far = 2200f;
        camera.fieldOfView = 68f;
    }

    public void setAspect(float aspect) {
        camera.viewportWidth = 1600;
        camera.viewportHeight = Math.max(1, 1600 / Math.max(0.1f, aspect));
        camera.update();
    }

    public void rotate(float dxPixels, float dyPixels) {
        yaw -= dxPixels * 0.0065f * sensitivity;
        pitch += dyPixels * 0.0045f * sensitivity;
        pitch = MathUtils.clamp(pitch, -0.28f, 1.15f);
    }

    public void toggleFirstPerson() {
        firstPerson = !firstPerson;
    }

    public void setFirstPerson(boolean fp) {
        firstPerson = fp;
    }

    public Vector3 forward() {
        return offset.set(MathUtils.sin(yaw), 0, -MathUtils.cos(yaw));
    }

    /** Updates the camera to follow a character or vehicle. */
    public void update(float x, float y, float z, float groundY, boolean inVehicle,
                       float vehicleLength, MapData map, float delta) {
        target.set(x, y + (inVehicle ? 0.9f : 1.35f), z);
        if (firstPerson) {
            camera.position.set(x, y + (inVehicle ? 1.15f : 1.62f), z);
            float p = MathUtils.clamp(-pitch * 0.85f, -0.9f, 0.9f);
            camera.direction.set(
                    MathUtils.sin(yaw) * MathUtils.cos(p),
                    MathUtils.sin(p),
                    -MathUtils.cos(yaw) * MathUtils.cos(p)).nor();
            camera.up.set(Vector3.Y);
            camera.update();
            return;
        }
        float dist = inVehicle ? Math.max(7.5f, vehicleLength * 1.5f) : distance;
        float p = MathUtils.clamp(pitch, -0.15f, 1.05f);
        float cy = MathUtils.sin(p) * dist;
        float horiz = MathUtils.cos(p) * dist;
        offset.set(-MathUtils.sin(yaw) * horiz, cy, MathUtils.cos(yaw) * horiz);
        // Keep the boom out of buildings. If every shortened position is blocked,
        // lift the camera above the player instead of leaving it inside a facade.
        if (map != null) {
            boolean found = false;
            for (float t = 1f; t >= 0.12f; t -= 0.11f) {
                float px = target.x + offset.x * t;
                float pz = target.z + offset.z * t;
                if (!map.blocked(px, pz, 0.4f)) {
                    offset.scl(t);
                    found = true;
                    break;
                }
            }
            if (!found) {
                offset.set(0f, 1.8f, 0f);
            }
        }
        camera.position.set(target.x + offset.x, target.y + offset.y, target.z + offset.z);
        float minY = groundY + 0.6f;
        if (camera.position.y < minY) {
            camera.position.y = minY;
        }
        look.set(target.x, target.y + 0.15f, target.z);
        camera.lookAt(look);
        camera.up.set(Vector3.Y);
        camera.update();
    }

    public Vector3 cameraPosition() {
        return camera.position;
    }
}

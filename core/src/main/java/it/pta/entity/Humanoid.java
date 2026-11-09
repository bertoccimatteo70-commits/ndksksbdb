package it.pta.entity;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import it.pta.render.Atlas;
import it.pta.render.MeshBuilder;
import it.pta.render.Meshes;

/**
 * Low-poly articulated character (torso, head, arms, legs) with a procedural walk,
 * run, idle, jump and punch animation. Six bones = six draw calls, sized for mobile.
 */
public final class Humanoid implements Disposable {
    public static final int TORSO = 0, HEAD = 1, ARM_L = 2, ARM_R = 3, LEG_L = 4, LEG_R = 5;

    public final String name;
    private final Mesh[] parts = new Mesh[6];
    private final Matrix4[] bones = new Matrix4[6];
    private final Matrix4 out = new Matrix4();
    private final Matrix4 local = new Matrix4();
    private final Matrix4 root = new Matrix4();

    public float[][] palette; // skin, hair, top, trousers, shoes, accent

    public Humanoid(String name, float[] skin, float[] hair, float[] top, float[] legs,
                    float[] shoes, boolean longHair, boolean cap) {
        this.name = name;
        palette = new float[][]{skin, hair, top, legs, shoes};
        for (int i = 0; i < 6; i++) {
            bones[i] = new Matrix4();
        }
        parts[TORSO] = buildTorso(top, skin, cap);
        parts[HEAD] = buildHead(skin, hair, longHair, cap);
        parts[ARM_L] = buildArm(top, skin, -1);
        parts[ARM_R] = buildArm(top, skin, 1);
        parts[LEG_L] = buildLeg(legs, shoes, -1);
        parts[LEG_R] = buildLeg(legs, shoes, 1);
    }

    private Mesh mesh(MeshBuilder mb) {
        return Meshes.from(mb);
    }

    private Mesh buildTorso(float[] top, float[] skin, boolean cap) {
        MeshBuilder mb = new MeshBuilder();
        // torso (jacket)
        mb.box(0, 0.31f, 0, 0.215f, 0.245f, 0.125f, top[0], top[1], top[2], Atlas.PAINT, 0.6f);
        // shoulders
        mb.box(0, 0.50f, 0, 0.245f, 0.045f, 0.125f,
                top[0] * 0.9f, top[1] * 0.9f, top[2] * 0.9f, Atlas.PAINT, 0.6f);
        // belt
        mb.box(0, 0.075f, 0, 0.20f, 0.045f, 0.12f,
                top[0] * 0.55f, top[1] * 0.55f, top[2] * 0.55f, Atlas.PAINT, 0.6f);
        // neck
        mb.box(0, 0.565f, 0, 0.055f, 0.04f, 0.055f, skin[0], skin[1], skin[2], Atlas.PAINT, 0.6f);
        return mesh(mb);
    }

    private Mesh buildHead(float[] skin, float[] hair, boolean longHair, boolean cap) {
        MeshBuilder mb = new MeshBuilder();
        mb.box(0, 0.135f, 0, 0.112f, 0.132f, 0.112f, skin[0], skin[1], skin[2], Atlas.PAINT, 0.7f);
        // hair cap
        mb.box(0, 0.255f, 0.005f, 0.118f, 0.045f, 0.118f, hair[0], hair[1], hair[2], Atlas.PAINT, 0.7f);
        mb.box(0, 0.175f, 0.09f, 0.112f, 0.09f, 0.045f, hair[0], hair[1], hair[2], Atlas.PAINT, 0.7f);
        if (longHair) {
            mb.box(0, 0.10f, 0.10f, 0.115f, 0.20f, 0.05f, hair[0], hair[1], hair[2], Atlas.PAINT, 0.7f);
            mb.box(-0.10f, 0.12f, 0.02f, 0.03f, 0.16f, 0.09f, hair[0], hair[1], hair[2], Atlas.PAINT, 0.7f);
            mb.box(0.10f, 0.12f, 0.02f, 0.03f, 0.16f, 0.09f, hair[0], hair[1], hair[2], Atlas.PAINT, 0.7f);
        }
        // eyes
        mb.box(-0.045f, 0.15f, -0.115f, 0.022f, 0.014f, 0.01f, 0.12f, 0.12f, 0.13f, Atlas.PAINT, 1f);
        mb.box(0.045f, 0.15f, -0.115f, 0.022f, 0.014f, 0.01f, 0.12f, 0.12f, 0.13f, Atlas.PAINT, 1f);
        return mesh(mb);
    }

    private Mesh buildArm(float[] top, float[] skin, int side) {
        MeshBuilder mb = new MeshBuilder();
        mb.box(0, -0.24f, 0, 0.070f, 0.24f, 0.075f, top[0], top[1], top[2], Atlas.PAINT, 0.7f);
        // sleeve
        mb.box(0, -0.44f, 0, 0.062f, 0.10f, 0.066f,
                top[0] * 0.85f, top[1] * 0.85f, top[2] * 0.85f, Atlas.PAINT, 0.7f);
        // hand
        mb.box(0, -0.57f, 0, 0.058f, 0.055f, 0.062f, skin[0], skin[1], skin[2], Atlas.PAINT, 1f);
        return mesh(mb);
    }

    private Mesh buildLeg(float[] legs, float[] shoes, int side) {
        MeshBuilder mb = new MeshBuilder();
        mb.box(0, -0.36f, 0, 0.083f, 0.36f, 0.092f, legs[0], legs[1], legs[2], Atlas.PAINT, 0.5f);
        mb.box(0, -0.76f, 0, 0.078f, 0.055f, 0.145f, shoes[0], shoes[1], shoes[2], Atlas.PAINT, 0.8f);
        return mesh(mb);
    }

    /**
     * @param phase     animation phase in radians
     * @param speed01   0 idle .. 1 full sprint
     * @param airborne  jump/fall pose
     * @param punch     0..1 punch blend
     */
    public void draw(ShaderProgram shader, Matrix4 model, float phase, float speed01,
                     boolean airborne, float punch, float lean, float groundY) {
        float amp = airborne ? 0f : (0.18f + speed01 * 0.55f);
        float swing = (float) Math.sin(phase) * amp;
        float bob = airborne ? 0f : (float) Math.abs(Math.sin(phase * 2f)) * 0.035f * speed01
                + (float) Math.sin(phase * 0.7f) * 0.008f * (1f - speed01);
        float breath = (float) Math.sin(phase * 0.6f) * 0.012f * (1f - speed01);

        root.setToTranslation(0, groundY, 0);
        float torsoLean = lean + (airborne ? 0.10f : speed01 * 0.12f);

        bones[TORSO].setToTranslation(0, 0.98f + bob + breath, 0).rotate(Vector3.X, torsoLean * 57.2958f);
        bones[HEAD].setToTranslation(0, 1.60f + bob + breath, 0)
                .rotate(Vector3.X, -torsoLean * 0.7f * 57.2958f);
        if (airborne) {
            bones[ARM_L].setToTranslation(-0.285f, 1.51f, 0).rotate(Vector3.X, -125);
            bones[ARM_R].setToTranslation(0.285f, 1.51f, 0).rotate(Vector3.X, -125);
            bones[LEG_L].setToTranslation(-0.105f, 0.94f, 0).rotate(Vector3.X, 28);
            bones[LEG_R].setToTranslation(0.105f, 0.94f, 0).rotate(Vector3.X, -16);
        } else {
            bones[ARM_L].setToTranslation(-0.285f, 1.51f, 0).rotate(Vector3.X, -swing * 46);
            bones[ARM_R].setToTranslation(0.285f, 1.51f, 0).rotate(Vector3.X, swing * 46);
            if (punch > 0.01f) {
                // right arm forward jab
                bones[ARM_R].setToTranslation(0.285f, 1.51f, 0)
                        .rotate(Vector3.X, -95 * punch).rotate(Vector3.Y, -20 * punch);
            }
            bones[LEG_L].setToTranslation(-0.105f, 0.94f, 0).rotate(Vector3.X, swing * 42);
            bones[LEG_R].setToTranslation(0.105f, 0.94f, 0).rotate(Vector3.X, -swing * 42);
        }

        for (int i = 0; i < 6; i++) {
            if (parts[i] == null) {
                continue;
            }
            local.set(model).mul(root).mul(bones[i]);
            shader.setUniformMatrix("u_model", local);
            parts[i].render(shader, GL20.GL_TRIANGLES);
        }
        shader.setUniformMatrix("u_model", model);
    }

    public void dispose() {
        for (Mesh m : parts) {
            if (m != null) {
                m.dispose();
            }
        }
        Gdx.app.log("PTA", "humanoid disposed: " + name);
    }
}

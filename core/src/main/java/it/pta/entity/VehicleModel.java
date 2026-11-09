package it.pta.entity;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;
import it.pta.render.Atlas;
import it.pta.render.MeshBuilder;
import it.pta.render.Meshes;

/** Procedurally built low-poly vehicle model (body + wheels + optional rotor). */
public final class VehicleModel implements Disposable {
    public final VehicleType type;
    public final Mesh body;
    public final Mesh wheel;
    public final Mesh rotor;
    public final float wheelRadius;
    public final float[] wheelX;
    public final float[] wheelZ;
    public final float halfLength;
    public final float halfWidth;

    VehicleModel(VehicleType type) {
        this.type = type;
        this.halfLength = type.length * 0.5f;
        this.halfWidth = type.width * 0.5f;
        this.wheelRadius = type.wheelRadius;
        MeshBuilder mb = new MeshBuilder();
        buildBody(mb, type);
        body = Meshes.from(mb);
        MeshBuilder wb = new MeshBuilder();
        buildWheel(wb, type.wheelRadius, type.twoWheeler());
        wheel = Meshes.from(wb);
        MeshBuilder rb = new MeshBuilder();
        if (type.kind == VehicleType.Kind.HELI) {
            float r = type.length * 0.62f;
            rb.box(0, 0, 0, r, 0.03f, 0.12f, 0.22f, 0.22f, 0.24f, Atlas.METAL, 0.4f);
            rb.box(0, 0, 0, 0.12f, 0.03f, r, 0.22f, 0.22f, 0.24f, Atlas.METAL, 0.4f);
            rotor = Meshes.from(rb);
        } else {
            rotor = null;
        }
        if (type.twoWheeler()) {
            wheelX = new float[]{0f, 0f};
            wheelZ = new float[]{-type.wheelBase * 0.5f, type.wheelBase * 0.5f};
        } else {
            wheelX = new float[]{-halfWidth + 0.18f, halfWidth - 0.18f};
            wheelZ = new float[]{-type.wheelBase * 0.5f, type.wheelBase * 0.5f};
        }
    }

    private static void buildWheel(MeshBuilder mb, float r, boolean thin) {
        float halfW = thin ? 0.07f : 0.11f;
        int sides = 10;
        float[] col = {0.09f, 0.09f, 0.10f};
        float[] rim = {0.55f, 0.55f, 0.58f};
        for (int i = 0; i < sides; i++) {
            double a0 = Math.PI * 2 * i / sides;
            double a1 = Math.PI * 2 * (i + 1) / sides;
            float y0 = (float) Math.cos(a0) * r, z0 = (float) Math.sin(a0) * r;
            float y1 = (float) Math.cos(a1) * r, z1 = (float) Math.sin(a1) * r;
            // tread
            int v0 = mb.vertex(-halfW, y0, z0, 0, (float) Math.cos(a0), (float) Math.sin(a0),
                    col[0], col[1], col[2], Atlas.METAL, 0.5f, 0.5f);
            int v1 = mb.vertex(halfW, y0, z0, 0, (float) Math.cos(a0), (float) Math.sin(a0),
                    col[0], col[1], col[2], Atlas.METAL, 0.5f, 0.5f);
            int v2 = mb.vertex(halfW, y1, z1, 0, (float) Math.cos(a1), (float) Math.sin(a1),
                    col[0], col[1], col[2], Atlas.METAL, 0.5f, 0.5f);
            int v3 = mb.vertex(-halfW, y1, z1, 0, (float) Math.cos(a1), (float) Math.sin(a1),
                    col[0], col[1], col[2], Atlas.METAL, 0.5f, 0.5f);
            mb.quad(v0, v1, v2, v3);
            // rim disc
            int c0 = mb.vertex(halfW + 0.002f, 0, 0, 1, 0, 0, rim[0], rim[1], rim[2], Atlas.METAL, 0.6f, 0.6f);
            int r0 = mb.vertex(halfW + 0.002f, y0 * 0.55f, z0 * 0.55f, 1, 0, 0,
                    rim[0], rim[1], rim[2], Atlas.METAL, 0.6f, 0.6f);
            int r1 = mb.vertex(halfW + 0.002f, y1 * 0.55f, z1 * 0.55f, 1, 0, 0,
                    rim[0], rim[1], rim[2], Atlas.METAL, 0.6f, 0.6f);
            mb.tri(c0, r0, r1);
            int c0b = mb.vertex(-halfW - 0.002f, 0, 0, -1, 0, 0, rim[0], rim[1], rim[2], Atlas.METAL, 0.6f, 0.6f);
            int r0b = mb.vertex(-halfW - 0.002f, y0 * 0.55f, z0 * 0.55f, -1, 0, 0,
                    rim[0], rim[1], rim[2], Atlas.METAL, 0.6f, 0.6f);
            int r1b = mb.vertex(-halfW - 0.002f, y1 * 0.55f, z1 * 0.55f, -1, 0, 0,
                    rim[0], rim[1], rim[2], Atlas.METAL, 0.6f, 0.6f);
            mb.tri(c0b, r1b, r0b);
        }
    }

    private static void buildBody(MeshBuilder mb, VehicleType t) {
        float L = t.length * 0.5f;
        float W = t.width * 0.5f;
        float H = t.height;
        float wr = t.wheelRadius;
        float[] paint = {1f, 1f, 1f}; // tinted per instance
        float[] dark = {0.13f, 0.13f, 0.14f};
        float[] glass = {0.18f, 0.22f, 0.26f};
        float[] chrome = {0.62f, 0.63f, 0.65f};

        switch (t.kind) {
            case BIKE:
            case SCOOTER:
            case BICYCLE: {
                float deckY = wr + H * 0.32f;
                mb.box(0, deckY, 0, W * 0.35f, H * 0.16f, L * 0.52f,
                        paint[0], paint[1], paint[2], Atlas.PAINT, 0.5f);
                mb.box(0, deckY + H * 0.30f, -L * 0.22f, W * 0.32f, H * 0.20f, L * 0.22f,
                        glass[0], glass[1], glass[2], Atlas.GLASS, 0.6f);
                // handlebar
                mb.box(0, deckY + H * 0.55f, -L * 0.55f, W * 0.85f, 0.03f, 0.03f,
                        chrome[0], chrome[1], chrome[2], Atlas.METAL, 0.6f);
                mb.box(0, deckY + H * 0.42f, L * 0.15f, W * 0.34f, 0.06f, L * 0.28f,
                        dark[0], dark[1], dark[2], Atlas.METAL, 0.6f);
                break;
            }
            case BUS: {
                mb.box(0, wr + (H * 0.30f), 0, W, H * 0.30f, L, paint[0], paint[1], paint[2],
                        Atlas.PAINT, 0.35f);
                mb.box(0, wr + H * 0.66f, 0, W * 0.96f, H * 0.30f, L * 0.96f,
                        paint[0], paint[1], paint[2], Atlas.PAINT, 0.35f);
                // window band
                mb.box(0, wr + H * 0.70f, 0, W * 0.965f, H * 0.10f, L * 0.90f,
                        glass[0], glass[1], glass[2], Atlas.GLASS, 0.25f);
                mb.box(0, wr + H * 0.28f, -L - 0.02f, W * 0.98f, H * 0.16f, 0.03f,
                        0.8f, 0.8f, 0.82f, Atlas.PAINT, 0.5f);
                break;
            }
            case TRUCK: {
                // cab at the front (-Z)
                mb.box(0, wr + H * 0.26f, -L + L * 0.30f, W, H * 0.26f, L * 0.30f,
                        paint[0], paint[1], paint[2], Atlas.PAINT, 0.4f);
                mb.box(0, wr + H * 0.58f, -L + L * 0.22f, W * 0.94f, H * 0.34f, L * 0.20f,
                        paint[0], paint[1], paint[2], Atlas.PAINT, 0.4f);
                mb.box(0, wr + H * 0.60f, -L + L * 0.36f, W * 0.80f, H * 0.18f, 0.03f,
                        glass[0], glass[1], glass[2], Atlas.GLASS, 0.6f);
                // cargo box
                mb.box(0, wr + H * 0.48f, L * 0.18f, W, H * 0.46f, L * 0.68f,
                        paint[0] * 0.92f, paint[1] * 0.92f, paint[2] * 0.92f, Atlas.METAL, 0.28f);
                break;
            }
            case HELI: {
                mb.box(0, wr + H * 0.34f, 0, W * 0.85f, H * 0.30f, L * 0.62f,
                        paint[0], paint[1], paint[2], Atlas.PAINT, 0.45f);
                mb.box(0, wr + H * 0.62f, -L * 0.10f, W * 0.55f, H * 0.26f, L * 0.34f,
                        glass[0], glass[1], glass[2], Atlas.GLASS, 0.6f);
                mb.box(0, wr + H * 0.42f, L * 0.75f, W * 0.18f, H * 0.14f, L * 0.45f,
                        paint[0], paint[1], paint[2], Atlas.PAINT, 0.5f);
                mb.box(0, wr + H * 0.55f, L * 1.05f, 0.06f, H * 0.30f, 0.06f,
                        dark[0], dark[1], dark[2], Atlas.METAL, 0.5f);
                // skids
                mb.box(-W * 0.55f, wr * 0.5f, 0, 0.05f, 0.05f, L * 0.6f,
                        chrome[0], chrome[1], chrome[2], Atlas.METAL, 0.6f);
                mb.box(W * 0.55f, wr * 0.5f, 0, 0.05f, 0.05f, L * 0.6f,
                        chrome[0], chrome[1], chrome[2], Atlas.METAL, 0.6f);
                break;
            }
            case PLANE: {
                mb.box(0, wr + H * 0.34f, 0, W * 0.55f, H * 0.26f, L,
                        paint[0], paint[1], paint[2], Atlas.PAINT, 0.4f);
                mb.box(0, wr + H * 0.62f, -L * 0.18f, W * 0.42f, H * 0.20f, L * 0.30f,
                        glass[0], glass[1], glass[2], Atlas.GLASS, 0.6f);
                // wings
                mb.box(0, wr + H * 0.40f, -L * 0.05f, L * 0.92f, 0.05f, L * 0.25f,
                        paint[0], paint[1], paint[2], Atlas.PAINT, 0.35f);
                mb.box(0, wr + H * 0.55f, L * 0.92f, L * 0.28f, 0.04f, L * 0.14f,
                        paint[0], paint[1], paint[2], Atlas.PAINT, 0.4f);
                mb.box(0, wr + H * 0.45f, L * 0.92f, 0.05f, H * 0.22f, 0.05f,
                        paint[0], paint[1], paint[2], Atlas.PAINT, 0.5f);
                break;
            }
            case BOAT: {
                // hull wedge
                float[] hull = {0.86f, 0.87f, 0.90f};
                mb.box(0, wr + H * 0.18f, 0, W, H * 0.20f, L, hull[0], hull[1], hull[2],
                        Atlas.PAINT, 0.45f);
                mb.box(0, wr + H * 0.40f, L * 0.05f, W * 0.82f, H * 0.20f, L * 0.70f,
                        paint[0], paint[1], paint[2], Atlas.PAINT, 0.45f);
                mb.box(0, wr + H * 0.52f, -L * 0.18f, W * 0.55f, H * 0.16f, L * 0.32f,
                        glass[0], glass[1], glass[2], Atlas.GLASS, 0.6f);
                break;
            }
            default: {
                // cars, sports, vans, police, ambulance
                float bodyBottom = wr * 0.72f;
                float belt = H * (t.kind == VehicleType.Kind.SPORT ? 0.52f : 0.58f);
                float bodyH = belt - bodyBottom;
                mb.box(0, bodyBottom + bodyH * 0.5f, 0, W, bodyH * 0.5f, L,
                        paint[0], paint[1], paint[2], Atlas.PAINT, 0.35f);
                // bumpers
                mb.box(0, bodyBottom + bodyH * 0.30f, -L + 0.04f, W * 1.005f, bodyH * 0.22f, 0.06f,
                        dark[0], dark[1], dark[2], Atlas.METAL, 0.8f);
                mb.box(0, bodyBottom + bodyH * 0.30f, L - 0.04f, W * 1.005f, bodyH * 0.22f, 0.06f,
                        dark[0], dark[1], dark[2], Atlas.METAL, 0.8f);
                // cabin
                float cabFront = -L * (t.kind == VehicleType.Kind.VAN ? 0.72f : 0.42f);
                float cabBack = L * (t.kind == VehicleType.Kind.VAN ? 0.82f : 0.55f);
                float cabLen = cabBack - cabFront;
                float cabH = H - belt;
                mb.box(0, belt + cabH * 0.5f, (cabFront + cabBack) * 0.5f,
                        W * 0.88f, cabH * 0.5f, cabLen * 0.5f,
                        paint[0], paint[1], paint[2], Atlas.PAINT, 0.35f);
                // glazing
                mb.box(0, belt + cabH * 0.55f, cabFront + 0.01f, W * 0.76f, cabH * 0.32f, 0.03f,
                        glass[0], glass[1], glass[2], Atlas.GLASS, 0.6f);
                mb.box(0, belt + cabH * 0.55f, cabBack - 0.01f, W * 0.76f, cabH * 0.32f, 0.03f,
                        glass[0], glass[1], glass[2], Atlas.GLASS, 0.6f);
                mb.box(-W * 0.885f, belt + cabH * 0.55f, (cabFront + cabBack) * 0.5f,
                        0.02f, cabH * 0.30f, cabLen * 0.40f,
                        glass[0], glass[1], glass[2], Atlas.GLASS, 0.6f);
                mb.box(W * 0.885f, belt + cabH * 0.55f, (cabFront + cabBack) * 0.5f,
                        0.02f, cabH * 0.30f, cabLen * 0.40f,
                        glass[0], glass[1], glass[2], Atlas.GLASS, 0.6f);
                // roof
                mb.box(0, H - 0.02f, (cabFront + cabBack) * 0.5f, W * 0.80f, 0.03f, cabLen * 0.46f,
                        paint[0], paint[1], paint[2], Atlas.PAINT, 0.4f);
                // headlights
                mb.box(-W * 0.62f, bodyBottom + bodyH * 0.62f, -L - 0.005f, W * 0.20f, 0.075f, 0.02f,
                        1.4f, 1.35f, 1.1f, Atlas.PAINT, 0.8f);
                mb.box(W * 0.62f, bodyBottom + bodyH * 0.62f, -L - 0.005f, W * 0.20f, 0.075f, 0.02f,
                        1.4f, 1.35f, 1.1f, Atlas.PAINT, 0.8f);
                // tail lights
                mb.box(-W * 0.66f, bodyBottom + bodyH * 0.60f, L + 0.005f, W * 0.17f, 0.07f, 0.02f,
                        1.2f, 0.18f, 0.12f, Atlas.PAINT, 0.8f);
                mb.box(W * 0.66f, bodyBottom + bodyH * 0.60f, L + 0.005f, W * 0.17f, 0.07f, 0.02f,
                        1.2f, 0.18f, 0.12f, Atlas.PAINT, 0.8f);
                if (t.kind == VehicleType.Kind.POLICE) {
                    mb.box(0, H + 0.06f, (cabFront + cabBack) * 0.5f, W * 0.55f, 0.055f, 0.16f,
                            0.15f, 0.28f, 0.85f, Atlas.PAINT, 0.8f);
                }
                if (t.kind == VehicleType.Kind.VAN) {
                    mb.box(0, belt + cabH * 0.55f, cabFront + 0.02f, W * 0.60f, cabH * 0.26f, 0.03f,
                            glass[0], glass[1], glass[2], Atlas.GLASS, 0.6f);
                }
            }
        }
    }

    public void drawBody(ShaderProgram shader, com.badlogic.gdx.math.Matrix4 matrix,
                         com.badlogic.gdx.math.Vector3 tint) {
        shader.setUniformf("u_tint", tint.x, tint.y, tint.z, 1f);
        shader.setUniformMatrix("u_model", matrix);
        if (body != null) {
            body.render(shader, com.badlogic.gdx.Gdx.gl.GL_TRIANGLES);
        }
    }

    public void dispose() {
        if (body != null) {
            body.dispose();
        }
        if (wheel != null) {
            wheel.dispose();
        }
        if (rotor != null) {
            rotor.dispose();
        }
    }

    private static final VertexAttribute[] UNUSED = {
            new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position")
    };
}

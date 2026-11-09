package it.pta.render;

import it.pta.world.MapData;

/**
 * Turns baked OpenStreetMap geometry into renderable 3D chunk meshes: terrain,
 * roads, pavement, lane lines, extruded buildings with roofs, trees and lamps.
 *
 * Runs off the GL thread; no libGDX GL calls in here.
 */
public final class ChunkBuilder {
    private static final float TILING = 1f / 8f;

    public static ChunkGeometry build(MapData map, int cx, int cz, int lod) {
        ChunkGeometry g = new ChunkGeometry(cx, cz, lod);
        float x0 = cx * map.chunkSize;
        float z0 = cz * map.chunkSize;
        float x1 = x0 + map.chunkSize;
        float z1 = z0 + map.chunkSize;

        ground(map, g, x0, z0, x1, z1, lod);
        water(map, g, x0, z0, x1, z1);
        roads(map, g, cx, cz, x0, z0, x1, z1, lod);
        buildings(map, g, cx, cz, lod);
        if (lod == 0) {
            props(map, g, cx, cz, x0, z0, x1, z1);
        }

        g.centerX = (x0 + x1) * 0.5f;
        g.centerZ = (z0 + z1) * 0.5f;
        g.centerY = map.heightAt(g.centerX, g.centerZ) + 18f;
        g.radius = 480f;
        return g;
    }

    // ------------------------------------------------------------------ terrain

    private static void ground(MapData map, ChunkGeometry g, float x0, float z0,
                               float x1, float z1, int lod) {
        int cells = lod == 2 ? 5 : 11;
        float cs = (x1 - x0) / cells;
        MeshBuilder mb = g.current();
        java.util.Random rnd = new java.util.Random(((long) g.cx << 32) ^ g.cz * 31L);
        for (int r = 0; r < cells; r++) {
            for (int c = 0; c < cells; c++) {
                float ax = x0 + c * cs, az = z0 + r * cs;
                float bx = ax + cs, bz = az + cs;
                MapData.Area a = map.areaAt(ax + cs * 0.5f, az + cs * 0.5f);
                float[] tint = Palette.groundColor(a == null ? 0 : a.kind);
                float variation = 0.92f + rnd.nextFloat() * 0.12f;
                float tr = tint[0] * variation;
                float tg = tint[1] * variation;
                float tb = tint[2] * variation;
                if (a != null && a.kind == 13) {
                    tr *= 0.7f;
                    tg *= 0.75f;
                    tb *= 0.9f;
                }
                quadTerrain(map, mb, ax, az, bx, bz, tr, tg, tb);
            }
        }
    }

    /** Terrain quad sampled at the four corners so hills slope smoothly. */
    private static void quadTerrain(MapData map, MeshBuilder mb, float ax, float az,
                                    float bx, float bz, float r, float gg, float b) {
        float ha = map.heightAt(ax, az);
        float hb = map.heightAt(bx, az);
        float hc = map.heightAt(bx, bz);
        float hd = map.heightAt(ax, bz);
        float nx = (ha + hd - hb - hc) * 0.5f;
        float nz = (ha + hb - hc - hd) * 0.5f;
        float len = (float) Math.sqrt(nx * nx + (bx - ax) * (bx - ax) + nz * nz);
        float ny = (bx - ax) / Math.max(0.001f, len);
        int a = mb.vertex(ax, ha + 0.02f, az, nx / len, ny, nz / len, r, gg, b,
                Atlas.GROUND, ax * 0.12f, az * 0.12f);
        int bb = mb.vertex(bx, hb + 0.02f, az, nx / len, ny, nz / len, r, gg, b,
                Atlas.GROUND, bx * 0.12f, az * 0.12f);
        int c = mb.vertex(bx, hc + 0.02f, bz, nx / len, ny, nz / len, r, gg, b,
                Atlas.GROUND, bx * 0.12f, bz * 0.12f);
        int d = mb.vertex(ax, hd + 0.02f, bz, nx / len, ny, nz / len, r, gg, b,
                Atlas.GROUND, ax * 0.12f, bz * 0.12f);
        mb.quad(a, bb, c, d);
    }

    private static void water(MapData map, ChunkGeometry g, float x0, float z0,
                              float x1, float z1) {
        int[] idxs = map.areasIn(g.cx, g.cz);
        MeshBuilder wb = g.water;
        for (int idx : idxs) {
            MapData.Area a = map.areas[idx];
            if (a.kind != 13) {
                continue;
            }
            if (a.maxX < x0 || a.minX > x1 || a.maxZ < z0 || a.minZ > z1) {
                continue;
            }
            int n = a.pts.length / 2;
            if (n < 3) {
                continue;
            }
            float sum = 0;
            for (int i = 0; i < n; i++) {
                sum += map.heightAt(a.pts[i * 2], a.pts[i * 2 + 1]);
            }
            float y = sum / n - 0.35f;
            int center = wb.vertex(a.minX + (a.maxX - a.minX) * 0.5f, y,
                    a.minZ + (a.maxZ - a.minZ) * 0.5f, 0, 1, 0, 0.30f, 0.55f, 0.62f,
                    Atlas.GLASS, 0, 0);
            int first = -1;
            float cx = a.pts[0], cz = a.pts[1];
            for (int i = 0; i < n; i++) {
                int i2 = (i + 1) % n;
                float vx = a.pts[i2 * 2], vz = a.pts[i2 * 2 + 1];
                int v = wb.vertex(vx, y, vz, 0, 1, 0, 0.30f, 0.55f, 0.62f,
                        Atlas.GLASS, vx * 0.08f, vz * 0.08f);
                if (first >= 0) {
                    wb.tri(center, first, v);
                }
                first = v;
            }
            // close fan
            int close = wb.vertex(cx, y, cz, 0, 1, 0, 0.30f, 0.55f, 0.62f,
                    Atlas.GLASS, cx * 0.08f, cz * 0.08f);
            wb.tri(center, first, close);
        }
    }

    // -------------------------------------------------------------------- roads

    private static void roads(MapData map, ChunkGeometry g, int cx, int cz,
                              float x0, float z0, float x1, float z1, int lod) {
        int[] idxs = map.roadsIn(cx, cz);
        for (int idx : idxs) {
            MapData.RoadWay w = map.roads[idx];
            boolean paved = w.cls <= 8;
            MeshBuilder mb = g.current();
            int tile = paved ? (w.cls == 9 ? Atlas.CONCRETE : Atlas.ASHPALT) : Atlas.CONCRETE;
            float[] tint = roadTint(w.cls, paved);
            float yOff = 0.06f + (10 - Math.min(10, w.cls)) * 0.002f;
            for (int s = 0; s + 3 < w.pts.length; s += 2) {
                float ax = w.pts[s], az = w.pts[s + 1];
                float bx = w.pts[s + 2], bz = w.pts[s + 3];
                float[] clip = clipSegment(ax, az, bx, bz, x0, z0, x1, z1);
                if (clip == null) {
                    continue;
                }
                float cax = ax + (bx - ax) * clip[0];
                float caz = az + (bz - az) * clip[0];
                float cbx = ax + (bx - ax) * clip[1];
                float cbz = az + (bz - az) * clip[1];
                float dx = cbx - cax, dz = cbz - caz;
                float len = (float) Math.hypot(dx, dz);
                if (len < 0.35f) {
                    continue;
                }
                float px = -dz / len, pz = dx / len;
                float hw = w.width * 0.5f;
                float ya = map.heightAt(cax, caz) + yOff;
                float yb = map.heightAt(cbx, cbz) + yOff;
                int v0 = mb.vertex(cax + px * hw, ya, caz + pz * hw, 0, 1, 0,
                        tint[0], tint[1], tint[2], tile, 0, 0);
                int v1 = mb.vertex(cax - px * hw, ya, caz - pz * hw, 0, 1, 0,
                        tint[0], tint[1], tint[2], tile, 0, 1);
                int v2 = mb.vertex(cbx - px * hw, yb, cbz - pz * hw, 0, 1, 0,
                        tint[0], tint[1], tint[2], tile, len * 0.12f, 1);
                int v3 = mb.vertex(cbx + px * hw, yb, cbz + pz * hw, 0, 1, 0,
                        tint[0], tint[1], tint[2], tile, len * 0.12f, 0);
                mb.quad(v0, v1, v2, v3);

                if (lod == 0) {
                    // centre line on wider roads
                    if (w.width >= 5.0f && w.cls <= 4) {
                        MeshBuilder paint = g.current();
                        float lw = 0.11f;
                        float ly = 0.015f;
                        int p0 = paint.vertex(cax + px * lw, ya + ly, caz + pz * lw, 0, 1, 0,
                                0.95f, 0.92f, 0.78f, Atlas.PAINT, 0, 0);
                        int p1 = paint.vertex(cax - px * lw, ya + ly, caz - pz * lw, 0, 1, 0,
                                0.95f, 0.92f, 0.78f, Atlas.PAINT, 0, 1);
                        int p2 = paint.vertex(cbx - px * lw, yb + ly, cbz - pz * lw, 0, 1, 0,
                                0.95f, 0.92f, 0.78f, Atlas.PAINT, len * 0.12f, 1);
                        int p3 = paint.vertex(cbx + px * lw, yb + ly, cbz + pz * lw, 0, 1, 0,
                                0.95f, 0.92f, 0.78f, Atlas.PAINT, len * 0.12f, 0);
                        paint.quad(p0, p1, p2, p3);
                    }
                    // pavements on town streets
                    if (paved && w.cls <= 8 && w.width >= 5.0f) {
                        for (int side = -1; side <= 1; side += 2) {
                            float off = hw + 1.0f;
                            float sw = 1.5f;
                            MeshBuilder sb = g.current();
                            float sx0 = cax + px * (side * off), sz0 = caz + pz * (side * off);
                            float sx1 = cbx + px * (side * off), sz1 = cbz + pz * (side * off);
                            float sy0 = map.heightAt(sx0, sz0) + 0.16f;
                            float sy1 = map.heightAt(sx1, sz1) + 0.16f;
                            int s0 = sb.vertex(sx0 + px * sw * 0.5f, sy0, sz0 + pz * sw * 0.5f,
                                    0, 1, 0, 0.80f, 0.77f, 0.70f, Atlas.CONCRETE, 0, 0);
                            int s1 = sb.vertex(sx0 - px * sw * 0.5f, sy0, sz0 - pz * sw * 0.5f,
                                    0, 1, 0, 0.80f, 0.77f, 0.70f, Atlas.CONCRETE, 0, 1);
                            int s2 = sb.vertex(sx1 - px * sw * 0.5f, sy1, sz1 - pz * sw * 0.5f,
                                    0, 1, 0, 0.80f, 0.77f, 0.70f, Atlas.CONCRETE, len * 0.12f, 1);
                            int s3 = sb.vertex(sx1 + px * sw * 0.5f, sy1, sz1 + pz * sw * 0.5f,
                                    0, 1, 0, 0.80f, 0.77f, 0.70f, Atlas.CONCRETE, len * 0.12f, 0);
                            sb.quad(s0, s1, s2, s3);
                        }
                    }
                }
            }
        }
    }

    private static float[] roadTint(int cls, boolean paved) {
        if (!paved) {
            return new float[]{0.52f, 0.50f, 0.44f};
        }
        switch (cls) {
            case 0:
            case 1:
                return new float[]{0.78f, 0.78f, 0.80f};
            case 2:
                return new float[]{0.76f, 0.76f, 0.78f};
            case 3:
                return new float[]{0.74f, 0.74f, 0.76f};
            case 4:
                return new float[]{0.72f, 0.72f, 0.74f};
            case 7:
            case 8:
                return new float[]{0.68f, 0.67f, 0.66f};
            case 9:
                return new float[]{0.92f, 0.87f, 0.76f};
            default:
                return new float[]{0.70f, 0.70f, 0.72f};
        }
    }

    /** Liang-Barsky clip of a segment to a rectangle. Returns {t0,t1} or null. */
    static float[] clipSegment(float ax, float az, float bx, float bz,
                               float x0, float z0, float x1, float z1) {
        float dx = bx - ax, dz = bz - az;
        float t0 = 0, t1 = 1;
        float[] p = {-dx, dx, -dz, dz};
        float[] q = {ax - x0, x1 - ax, az - z0, z1 - az};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-9f) {
                if (q[i] < 0) {
                    return null;
                }
            } else {
                float t = q[i] / p[i];
                if (p[i] < 0) {
                    if (t > t1) return null;
                    if (t > t0) t0 = t;
                } else {
                    if (t < t0) return null;
                    if (t < t1) t1 = t;
                }
            }
        }
        return new float[]{t0, t1};
    }

    // ---------------------------------------------------------------- buildings

    private static void buildings(MapData map, ChunkGeometry g, int cx, int cz, int lod) {
        int[] idxs = map.buildingsIn(cx, cz);
        for (int idx : idxs) {
            MapData.Building b = map.buildings[idx];
            if (lod == 1 && b.area < 38f) {
                continue;
            }
            if (lod == 2 && b.area < 130f) {
                continue;
            }
            MeshBuilder mb = g.current();
            float baseY = map.heightAt(b.cx, b.cz) - 0.25f;
            float top = baseY + b.height;
            float[] fac = Palette.facade(b.facade);
            float[] roofC = Palette.roof(b.roof);
            int n = b.vertexCount();
            java.util.Random rnd = new java.util.Random(idx * 2654435761L);
            float shade = 0.94f + rnd.nextFloat() * 0.1f;
            float uAcc = 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                float ax = b.polyX(i), az = b.polyZ(i);
                float bx = b.polyX(j), bz = b.polyZ(j);
                float dx = bx - ax, dz = bz - az;
                float len = (float) Math.hypot(dx, dz);
                if (len < 0.25f) {
                    continue;
                }
                float nx = dz / len, nz = -dx / len;
                float mx = (ax + bx) * 0.5f - b.cx;
                float mz = (az + bz) * 0.5f - b.cz;
                if (nx * mx + nz * mz < 0) {
                    nx = -nx;
                    nz = -nz;
                }
                float yBase = map.heightAt(ax, az) - 0.25f;
                float wallTop = Math.max(top, yBase + b.height * 0.9f);
                float u0 = uAcc * TILING;
                float u1 = (uAcc + len) * TILING;
                float v0 = 0f;
                float v1 = (wallTop - yBase) * TILING;
                // darker at the bottom to fake contact occlusion
                int v00 = mb.vertex(ax, yBase, az, nx, 0, nz,
                        fac[0] * shade * 0.80f, fac[1] * shade * 0.80f, fac[2] * shade * 0.80f,
                        Atlas.FACADE, u0, v0);
                int v10 = mb.vertex(bx, yBase, bz, nx, 0, nz,
                        fac[0] * shade * 0.80f, fac[1] * shade * 0.80f, fac[2] * shade * 0.80f,
                        Atlas.FACADE, u1, v0);
                int v11 = mb.vertex(bx, wallTop, bz, nx, 0, nz,
                        fac[0] * shade, fac[1] * shade, fac[2] * shade,
                        Atlas.FACADE, u1, v1);
                int v01 = mb.vertex(ax, wallTop, az, nx, 0, nz,
                        fac[0] * shade, fac[1] * shade, fac[2] * shade,
                        Atlas.FACADE, u0, v1);
                mb.quad(v00, v10, v11, v01);
                uAcc += len;
            }
            // flat roof (fan around centroid)
            MeshBuilder rb = g.current();
            float roofY = top + 0.05f;
            int cidx = rb.vertex(b.cx, roofY, b.cz, 0, 1, 0,
                    roofC[0] * 0.90f, roofC[1] * 0.90f, roofC[2] * 0.90f,
                    Atlas.ROOF, b.cx * TILING, b.cz * TILING);
            int first = -1;
            int prev = -1;
            for (int i = 0; i <= n; i++) {
                int k = i % n;
                float px = b.polyX(k), pz = b.polyZ(k);
                int v = rb.vertex(px, roofY, pz, 0, 1, 0, roofC[0], roofC[1], roofC[2],
                        Atlas.ROOF, px * TILING, pz * TILING);
                if (prev >= 0) {
                    rb.tri(cidx, prev, v);
                }
                prev = v;
                if (first < 0) {
                    first = prev;
                }
            }
        }
    }

    // -------------------------------------------------------------------- props

    private static void props(MapData map, ChunkGeometry g, int cx, int cz,
                              float x0, float z0, float x1, float z1) {
        java.util.Random rnd = new java.util.Random((cx * 73856093L) ^ (cz * 19349663L));
        int[] areas = map.areasIn(cx, cz);
        for (int idx : areas) {
            MapData.Area a = map.areas[idx];
            if (a.kind != 5 && a.kind != 6 && a.kind != 7) {
                continue;
            }
            if (a.maxX < x0 || a.minX > x1 || a.maxZ < z0 || a.minZ > z1) {
                continue;
            }
            int count = Math.min(14, Math.max(1, (int) (a.radius / 26)));
            for (int i = 0; i < count; i++) {
                float px = a.minX + rnd.nextFloat() * (a.maxX - a.minX);
                float pz = a.minZ + rnd.nextFloat() * (a.maxZ - a.minZ);
                if (!a.contains(px, pz)) {
                    continue;
                }
                if (map.blocked(px, pz, 1.6f)) {
                    continue;
                }
                MapData.RoadHit hit = map.nearestRoad(px, pz, false, 7f);
                if (hit != null) {
                    continue;
                }
                tree(g.current(), px, map.heightAt(px, pz), pz, 3.2f + rnd.nextFloat() * 2.4f, rnd);
            }
        }

        // street lamps along main roads
        int[] roads = map.roadsIn(cx, cz);
        for (int idx : roads) {
            MapData.RoadWay w = map.roads[idx];
            if (w.cls > 6 || w.length < 4) {
                continue;
            }
            float spacing = 42f;
            int count = (int) (w.length / spacing);
            for (int i = 0; i <= count; i++) {
                float d = i * spacing + rnd.nextFloat() * 6f;
                float[] p = pointAt(w, d);
                if (p == null || p[0] < x0 || p[0] > x1 || p[2] < z0 || p[2] > z1) {
                    continue;
                }
                float side = ((i + cx + cz) % 2 == 0) ? 1 : -1;
                float lx = p[0] + p[3] * side * (w.width * 0.5f + 1.2f);
                float lz = p[2] + p[4] * side * (w.width * 0.5f + 1.2f);
                if (map.blocked(lx, lz, 0.4f)) {
                    continue;
                }
                lamp(g, lx, map.heightAt(lx, lz), lz);
            }
        }
    }

    /** Position and perpendicular at distance d along a road. */
    private static float[] pointAt(MapData.RoadWay w, float d) {
        float acc = 0;
        for (int i = 0; i + 3 < w.pts.length; i += 2) {
            float ax = w.pts[i], az = w.pts[i + 1];
            float bx = w.pts[i + 2], bz = w.pts[i + 3];
            float len = (float) Math.hypot(bx - ax, bz - az);
            if (len < 1e-4f) {
                continue;
            }
            if (acc + len >= d) {
                float t = (d - acc) / len;
                float px = ax + (bx - ax) * t;
                float pz = az + (bz - az) * t;
                float nx = -(bz - az) / len;
                float nz = (bx - ax) / len;
                return new float[]{px, 0, pz, nx, nz};
            }
            acc += len;
        }
        return null;
    }

    private static void tree(MeshBuilder mb, float x, float y, float z, float size, java.util.Random rnd) {
        float trunkH = size * 0.55f;
        mb.box(x, y + trunkH * 0.5f, z, 0.16f, trunkH, 0.16f,
                0.38f, 0.28f, 0.20f, Atlas.METAL, TILING);
        float crown = size * 0.45f;
        float g = 0.62f + rnd.nextFloat() * 0.16f;
        mb.box(x, y + trunkH + crown * 0.55f, z, crown, crown * 0.95f, crown,
                0.26f * g, 0.46f * g, 0.20f * g, Atlas.FOLIAGE, 0.30f);
        mb.box(x, y + trunkH + crown * 1.55f, z, crown * 0.62f, crown * 0.7f, crown * 0.62f,
                0.30f * g, 0.52f * g, 0.23f * g, Atlas.FOLIAGE, 0.34f);
    }

    private static void lamp(ChunkGeometry g, float x, float y, float z) {
        MeshBuilder mb = g.current();
        mb.box(x, y + 2.6f, z, 0.07f, 2.6f, 0.07f, 0.36f, 0.36f, 0.38f, Atlas.METAL, 0.5f);
        mb.box(x, y + 5.25f, z, 0.20f, 0.10f, 0.20f, 0.90f, 0.84f, 0.62f, Atlas.PAINT, 0.5f);
        g.addGlow(x, y + 5.15f, z);
    }

    private ChunkBuilder() {
    }
}

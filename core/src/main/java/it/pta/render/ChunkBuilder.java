package it.pta.render;

import it.pta.world.MapData;

/**
 * Turns baked OpenStreetMap geometry into renderable 3D chunk meshes.
 *
 * <p>The overhaul keeps every OSM footprint, road and area untouched. What changes
 * is the interpretation: buildings get a real architectural family (packed in the
 * map file), geometric windows/shopfronts/balconies, pitched terracotta or flat
 * modern roofs and zone-appropriate materials. Roads get asphalt variants, kerbs,
 * pavements, lane paint and crossings; parks and verges get varied trees, lamps,
 * benches, bins, signals and bus stops. LOD1/LOD2 still show every building, just
 * with progressively cheaper silhouettes.</p>
 *
 * <p>Runs off the GL thread; no libGDX GL calls in here.</p>
 */
public final class ChunkBuilder {
    private static final float TILING = 1f / 4f;
    private static final float STONE_TILING = 1f / 3f;
    private static final float ROOF_TILING = 1f / 2.6f;

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
        int cells = lod == 2 ? 5 : (lod == 1 ? 11 : 24);
        float cs = (x1 - x0) / cells;
        MeshBuilder mb = g.current();
        for (int r = 0; r < cells; r++) {
            for (int c = 0; c < cells; c++) {
                float ax = x0 + c * cs, az = z0 + r * cs;
                float bx = ax + cs, bz = az + cs;
                MapData.Area a = map.areaAt(ax + cs * 0.5f, az + cs * 0.5f);
                float[] tint = Palette.groundColor(a == null ? 0 : a.kind);
                // Coherent low-frequency variation avoids a checkerboard of 32 m
                // colour tiles while still keeping large surfaces from looking flat.
                float variation = 0.985f
                        + 0.025f * (float) Math.sin(ax * 0.017f + az * 0.011f)
                        + 0.018f * (float) Math.sin(az * 0.023f - ax * 0.007f);
                int tile = Atlas.GROUND;
                if (a != null) {
                    if (a.kind == 12 || a.kind == 14 || a.kind == 3) {
                        tile = Atlas.CONCRETE;
                    } else if (a.kind == 9) {
                        tile = Atlas.PAVING;
                    } else if (a.kind == 13) {
                        continue;
                    }
                }
                float central = (float) Math.hypot(ax + cs * 0.5f, az + cs * 0.5f);
                if (central < 540f && (a == null || (a.kind != 4 && a.kind != 6
                        && a.kind != 7 && a.kind != 13))) {
                    // The historic core has a continuous stone-paved surface. Using
                    // PAVING only on a few OSM area kinds produced conspicuous colour
                    // wedges between the piazza, its side streets and nearby parking.
                    tile = Atlas.PAVING;
                    tint = blend(tint, new float[]{0.62f, 0.58f, 0.50f}, 0.70f);
                }
                float tr = tint[0] * variation;
                float tg = tint[1] * variation;
                float tb = tint[2] * variation;
                if (a != null && a.kind == 4) {
                    // Fields are not a uniform green carpet: alternate warmer strips.
                    if (((int) (ax / 24f) + (int) (az / 24f)) % 2 == 0) {
                        tr *= 1.06f;
                        tg *= 1.02f;
                        tb *= 0.88f;
                    }
                }
                quadTerrain(map, mb, ax, az, bx, bz, tr, tg, tb, tile);
            }
        }
    }

    /** Terrain quad sampled at the four corners so hills slope smoothly. */
    private static void quadTerrain(MapData map, MeshBuilder mb, float ax, float az,
                                    float bx, float bz, float r, float gg, float b, int tile) {
        float ha = map.heightAt(ax, az);
        float hb = map.heightAt(bx, az);
        float hc = map.heightAt(bx, bz);
        float hd = map.heightAt(ax, bz);
        float nx = (ha + hd - hb - hc) * 0.5f;
        float nz = (ha + hb - hc - hd) * 0.5f;
        float len = (float) Math.sqrt(nx * nx + (bx - ax) * (bx - ax) + nz * nz);
        float ny = (bx - ax) / Math.max(0.001f, len);
        int a = mb.vertex(ax, ha + 0.02f, az, nx / len, ny, nz / len, r, gg, b,
                tile, ax * 0.18f, az * 0.18f);
        int bb = mb.vertex(bx, hb + 0.02f, az, nx / len, ny, nz / len, r, gg, b,
                tile, bx * 0.18f, az * 0.18f);
        int c = mb.vertex(bx, hc + 0.02f, bz, nx / len, ny, nz / len, r, gg, b,
                tile, bx * 0.18f, bz * 0.18f);
        int d = mb.vertex(ax, hd + 0.02f, bz, nx / len, ny, nz / len, r, gg, b,
                tile, ax * 0.18f, bz * 0.18f);
        mb.quad(a, bb, c, d);
    }

    private static void water(MapData map, ChunkGeometry g, float x0, float z0,
                              float x1, float z1) {
        int[] idxs = map.areasIn(g.cx, g.cz);
        MeshBuilder wb = g.water;
        for (int idx : idxs) {
            MapData.Area a = map.area(idx);
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
                    a.minZ + (a.maxZ - a.minZ) * 0.5f, 0, 1, 0, 0.25f, 0.46f, 0.55f,
                    Atlas.GLASS, 0, 0);
            int first = -1;
            float cx = a.pts[0], cz = a.pts[1];
            for (int i = 0; i < n; i++) {
                int i2 = (i + 1) % n;
                float vx = a.pts[i2 * 2], vz = a.pts[i2 * 2 + 1];
                int v = wb.vertex(vx, y, vz, 0, 1, 0, 0.25f, 0.46f, 0.55f,
                        Atlas.GLASS, vx * 0.08f, vz * 0.08f);
                if (first >= 0) {
                    wb.tri(center, first, v);
                }
                first = v;
            }
            int close = wb.vertex(cx, y, cz, 0, 1, 0, 0.25f, 0.46f, 0.55f,
                    Atlas.GLASS, cx * 0.08f, cz * 0.08f);
            wb.tri(center, first, close);
        }
    }

    // -------------------------------------------------------------------- roads

    private static void roads(MapData map, ChunkGeometry g, int cx, int cz,
                              float x0, float z0, float x1, float z1, int lod) {
        int[] idxs = map.roadsIn(cx, cz);
        for (int idx : idxs) {
            MapData.RoadWay w = map.road(idx);
            boolean paved = w.cls <= 8;
            boolean central = Math.hypot((x0 + x1) * 0.5f, (z0 + z1) * 0.5f) < 650f;
            MeshBuilder mb = g.current();
            int tile = paved ? (w.cls == 9 ? Atlas.PAVING : Atlas.ASHPALT) : Atlas.CONCRETE;
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
                    roadMarkings(map, mb, w, cax, caz, cbx, cbz, dx, dz, len,
                            px, pz, hw, ya, yb, central);
                    if (paved && w.width >= 4.8f) {
                        sidewalks(map, mb, w, cax, caz, cbx, cbz, dx, dz, len,
                                px, pz, hw, central);
                    }
                    if (paved && w.cls <= 4 && central && len > 8f) {
                        junctionCrosswalk(map, mb, w, (cax + cbx) * 0.5f,
                                (caz + cbz) * 0.5f, dx / len, dz / len, hw);
                    }
                }
            }
        }
    }

    private static void roadMarkings(MapData map, MeshBuilder mb, MapData.RoadWay w,
                                     float ax, float az, float bx, float bz,
                                     float dx, float dz, float len, float px, float pz,
                                     float hw, float ya, float yb, boolean central) {
        float tx = dx / len, tz = dz / len;
        // Road surfaces sit at 0.06-0.08 m; paint must stay above them.
        float y = 0.12f;
        boolean asphalt = w.cls <= 8 && w.cls != 9;
        if (!asphalt) {
            return;
        }
        // Dashed centre line; emergency lane edge line on wide roads.
        if (w.width >= 5.8f && w.cls <= 5 && !w.oneWay()) {
            if (w.cls <= 3 && w.width >= 9f) {
                paintLine(map, mb, ax, az, bx, bz, px, pz, -0.20f, 0.11f, y, 0.94f, 0.90f, 0.71f);
                paintLine(map, mb, ax, az, bx, bz, px, pz, 0.20f, 0.11f, y, 0.94f, 0.90f, 0.71f);
            } else {
                dashedLine(map, mb, ax, az, bx, bz, px, pz, 0f, 0.11f, y, len,
                        w.cls <= 3 ? 4.0f : 5.5f, w.cls <= 3 ? 5.0f : 7.0f);
            }
        }
        if (w.width >= 4.5f && w.cls <= 8) {
            float ew = w.width >= 8f ? 0.10f : 0.075f;
            float er = w.cls <= 4 ? 0.92f : 0.86f;
            float eg = w.cls <= 4 ? 0.90f : 0.85f;
            float eb = w.cls <= 4 ? 0.82f : 0.78f;
            paintLine(map, mb, ax, az, bx, bz, px, pz, hw - 0.34f, ew, y, er, eg, eb);
            paintLine(map, mb, ax, az, bx, bz, px, pz, -hw + 0.34f, ew, y, er, eg, eb);
        }
        if (w.cls >= 6 && w.cls <= 8 && w.width >= 4.8f && !w.oneWay()) {
            dashedLine(map, mb, ax, az, bx, bz, px, pz, 0f, 0.075f, y, len, 4.0f, 7.0f);
        }
        // A simple direction arrow at the start of major roads, kept sparse.
        if (w.cls <= 3 && len > 18f && ((w.id >>> 3) & 7) == 0) {
            float at = Math.min(len * 0.45f, 20f);
            float s = at / len;
            float cx = ax + dx * s, cz = az + dz * s;
            arrow(mb, cx, cz, tx, tz, px, pz, 0.65f, y,
                    map.heightAt(cx, cz) + 0.09f);
        }
        // Residential parking bays: short ticks along the kerb, not painted decks.
        if (w.cls >= 5 && w.cls <= 8 && w.width >= 6.2f && ((w.id >>> 5) & 3) == 0) {
            for (int i = 0; i < 3; i++) {
                float d = 5f + i * 6.5f;
                if (d > len - 1f) {
                    break;
                }
                float s = d / len;
                float cx = ax + dx * s, cz = az + dz * s;
                paintLine(map, mb, cx, cz, cx + tx * 0.8f, cz + tz * 0.8f,
                        px, pz, hw + 0.2f, 0.055f, y, 0.78f, 0.76f, 0.70f);
            }
        }
    }

    private static void sidewalks(MapData map, MeshBuilder mb, MapData.RoadWay w,
                                  float ax, float az, float bx, float bz,
                                  float dx, float dz, float len, float px, float pz,
                                  float hw, boolean central) {
        float sw = central ? 1.75f : 1.45f;
        float inner = hw + 0.18f;
        float outer = inner + sw;
        int tile = central ? Atlas.PAVING : Atlas.CONCRETE;
        float tr = central ? 0.72f : 0.77f;
        float tg = central ? 0.68f : 0.75f;
        float tb = central ? 0.61f : 0.70f;
        for (int side = -1; side <= 1; side += 2) {
            float ox = px * side;
            float oz = pz * side;
            float y0 = 0.15f;
            float sx0 = ax + ox * inner, sz0 = az + oz * inner;
            float sx1 = bx + ox * inner, sz1 = bz + oz * inner;
            float ox0 = ax + ox * outer, oz0 = az + oz * outer;
            float ox1 = bx + ox * outer, oz1 = bz + oz * outer;
            float sy0 = map.heightAt(sx0, sz0) + y0;
            float sy1 = map.heightAt(sx1, sz1) + y0;
            float oy0 = map.heightAt(ox0, oz0) + y0;
            float oy1 = map.heightAt(ox1, oz1) + y0;
            // pavement top
            surface(mb, sx0, sy0, sz0, sx1, sy1, sz1,
                    ox1, oy1, oz1, ox0, oy0, oz0,
                    tr, tg, tb, tile, 0.22f, false);
            // kerb face, slightly darker, between road and pavement
            float ky0 = map.heightAt(sx0, sz0) + 0.015f;
            float ky1 = map.heightAt(sx1, sz1) + 0.015f;
            wall(mb, sx0, sz0, sx1, sz1, ky0, sy0, -ox, -oz,
                    0.62f, 0.60f, 0.55f, Atlas.CONCRETE, 0.45f, 0.82f);
            if (!central && (w.cls <= 4)) {
                // slim planting strip on major boulevards
                float gx0 = ax + ox * (outer + 0.35f);
                float gz0 = az + oz * (outer + 0.35f);
                if (!map.blocked(gx0, gz0, 0.3f)) {
                    flatBox(mb, gx0, mountY(map, gx0, gz0, 0.14f), gz0,
                            0.35f, 0.10f, 0.35f, 0.30f, 0.42f, 0.24f, Atlas.FOLIAGE);
                }
            }
        }
    }

    private static void paintLine(MapData map, MeshBuilder mb, float ax, float az,
                                  float bx, float bz, float px, float pz,
                                  float offset, float width, float yOff,
                                  float r, float g, float b) {
        float ox = px * offset, oz = pz * offset;
        float x0 = ax + ox - px * width * 0.5f;
        float z0 = az + oz - pz * width * 0.5f;
        float x1 = ax + ox + px * width * 0.5f;
        float z1 = az + oz + pz * width * 0.5f;
        float x2 = bx + ox + px * width * 0.5f;
        float z2 = bz + oz + pz * width * 0.5f;
        float x3 = bx + ox - px * width * 0.5f;
        float z3 = bz + oz - pz * width * 0.5f;
        float y0 = map.heightAt(x0, z0) + yOff;
        float y1 = map.heightAt(x1, z1) + yOff;
        float y2 = map.heightAt(x2, z2) + yOff;
        float y3 = map.heightAt(x3, z3) + yOff;
        surface(mb, x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3,
                r, g, b, Atlas.PAINT, 0.0f, false);
    }

    private static void dashedLine(MapData map, MeshBuilder mb, float ax, float az,
                                   float bx, float bz, float px, float pz,
                                   float offset, float width, float yOff, float len,
                                   float dash, float gap) {
        float dx = bx - ax, dz = bz - az;
        for (float d = 0; d < len; d += dash + gap) {
            float e = Math.min(len, d + dash);
            float s0 = d / len, s1 = e / len;
            paintLine(map, mb,
                    ax + dx * s0, az + dz * s0,
                    ax + dx * s1, az + dz * s1,
                    px, pz, offset, width, yOff, 0.94f, 0.91f, 0.75f);
        }
    }

    private static void arrow(MeshBuilder mb, float cx, float cz, float tx, float tz,
                              float px, float pz, float size, float y, float ground) {
        float x0 = cx - px * size * 0.35f;
        float z0 = cz - pz * size * 0.35f;
        float x1 = cx + px * size * 0.35f;
        float z1 = cz + pz * size * 0.35f;
        float x2 = cx + tx * size;
        float z2 = cz + tz * size;
        surface(mb, x0, ground + y, z0, x1, ground + y, z1,
                x2, ground + y, z2, cx, ground + y, cz,
                0.94f, 0.91f, 0.75f, Atlas.PAINT, 0.0f, false);
    }

    private static float[] roadTint(int cls, boolean paved) {
        if (!paved) {
            return new float[]{0.48f, 0.45f, 0.38f};
        }
        switch (cls) {
            case 0:
            case 1:
                return new float[]{0.62f, 0.63f, 0.65f};
            case 2:
                return new float[]{0.66f, 0.67f, 0.69f};
            case 3:
                return new float[]{0.70f, 0.71f, 0.73f};
            case 4:
                return new float[]{0.73f, 0.73f, 0.75f};
            case 7:
            case 8:
                return new float[]{0.69f, 0.68f, 0.65f};
            case 9:
                return new float[]{0.84f, 0.80f, 0.71f};
            default:
                return new float[]{0.72f, 0.72f, 0.73f};
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
            MapData.Building b = map.building(idx);
            if (lod > 0) {
                simpleBuilding(map, g, b, lod);
                continue;
            }
            detailedBuilding(map, g, b, idx);
        }
    }

    private static void detailedBuilding(MapData map, ChunkGeometry g, MapData.Building b, int id) {
        float baseY = map.heightAt(b.cx, b.cz) - 0.30f;
        float top = baseY + Math.max(2.8f, b.height);
        float[] fac = Palette.facade(b.facade);
        float[] roofC = Palette.roof(b.roof);
        int type = b.type;
        boolean central = Math.hypot(b.cx, b.cz) < 560f;
        int n = b.vertexCount();
        java.util.Random rnd = new java.util.Random(id * 2654435761L);
        float shade = 0.94f + rnd.nextFloat() * 0.10f;

        int wallTile = Atlas.PLASTER;
        int trimTile = Atlas.STONE;
        float trimR = 0.82f, trimG = 0.78f, trimB = 0.69f;
        float shutterR = 0.20f, shutterG = 0.29f, shutterB = 0.20f;
        boolean shutters = true;
        boolean modern = false;
        if (type == MapData.Building.MODERN) {
            wallTile = Atlas.MODERN;
            trimTile = Atlas.CONCRETE;
            trimR = 0.66f;
            trimG = 0.67f;
            trimB = 0.66f;
            fac = blend(fac, new float[]{0.62f, 0.66f, 0.68f}, 0.52f);
            shutters = false;
            modern = true;
        } else if (type == MapData.Building.PUBLIC) {
            wallTile = central ? Atlas.STONE : Atlas.PLASTER;
        } else if (type == MapData.Building.INDUSTRIAL) {
            wallTile = (id & 1) == 0 ? Atlas.BRICK : Atlas.METAL;
            trimTile = Atlas.METAL;
            trimR = 0.48f;
            trimG = 0.50f;
            trimB = 0.52f;
            fac = blend(fac, new float[]{0.55f, 0.53f, 0.49f}, 0.55f);
            shutters = false;
        } else if (type == MapData.Building.SHOP || type == MapData.Building.COMMERCIAL) {
            wallTile = type == MapData.Building.COMMERCIAL ? Atlas.MODERN : Atlas.PLASTER;
            shutters = false;
        } else if (type == MapData.Building.RURAL) {
            wallTile = (id & 3) == 0 ? Atlas.STONE : Atlas.PLASTER;
            shutterR = 0.31f;
            shutterG = 0.22f;
            shutterB = 0.15f;
        } else if (type == MapData.Building.VILLA) {
            wallTile = (id & 7) == 0 ? Atlas.STONE : Atlas.PLASTER;
        } else if (type == MapData.Building.HISTORIC) {
            wallTile = (id & 3) == 0 ? Atlas.STONE : Atlas.PLASTER;
            shutterR = 0.22f;
            shutterG = 0.32f;
            shutterB = 0.22f;
        }

        int doorEdge = Math.floorMod(id * 31 + (id >>> 4), Math.max(1, n));
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
            float yBase = Math.min(map.heightAt(ax, az), map.heightAt(bx, bz)) - 0.30f;
            float wallTop = Math.max(top, yBase + Math.max(2.8f, b.height * 0.88f));
            float wallHeight = wallTop - yBase;
            float tr = fac[0] * shade, tg = fac[1] * shade, tb = fac[2] * shade;
            float u0 = uAcc * TILING, u1 = (uAcc + len) * TILING;
            float v0 = 0f, v1 = wallHeight * TILING;
            wall(mb(g), ax, az, bx, bz, yBase, wallTop, nx, nz,
                    tr, tg, tb, wallTile, u0, u1, v0, v1, 0.84f);

            // Stone plinth / rusticated ground floor grounds the building visually.
            if (type != MapData.Building.INDUSTRIAL && type != MapData.Building.MODERN) {
                facadePanel(g, ax, az, bx, bz, nx, nz, 0, len, yBase, yBase + 0.95f,
                        0.025f, Atlas.STONE, 0.73f, 0.69f, 0.59f,
                        0, 1, 0, 1, 0.92f);
            }
            // A cornice makes the roofline read as architecture rather than a box.
            if (type != MapData.Building.INDUSTRIAL) {
                facadePanel(g, ax, az, bx, bz, nx, nz, 0, len, wallTop - 0.26f, wallTop,
                        0.055f, trimTile, trimR, trimG, trimB, 0, 1, 0, 1, 1f);
                facadePanel(g, ax, az, bx, bz, nx, nz, 0, len, wallTop - 0.36f,
                        wallTop - 0.26f, 0.10f, trimTile, trimR * 0.78f,
                        trimG * 0.78f, trimB * 0.78f, 0, 1, 0, 1, 0.9f);
            }
            if (len > 2.0f) {
                facadeDetails(g, b, ax, az, bx, bz, dx, dz, len, nx, nz,
                        yBase, wallTop, fac, type, central, modern, shutters,
                        shutterR, shutterG, shutterB, doorEdge == i, i, id, rnd);
                // A gutter and a single downpipe keep the roofline from reading as a
                // clean extrusion. Only one edge gets them, so this stays cheap.
                if (i == doorEdge && type != MapData.Building.INDUSTRIAL
                        && type != MapData.Building.MODERN) {
                    facadePanel(g, ax, az, bx, bz, nx, nz, 0.28f, len - 0.28f,
                            wallTop - 0.15f, wallTop - 0.07f, 0.13f, Atlas.METAL,
                            0.31f, 0.32f, 0.30f, 0, 1, 0, 1, 1f);
                    float pipe = Math.min(0.55f, len * 0.18f);
                    facadePanel(g, ax, az, bx, bz, nx, nz, pipe - 0.035f,
                            pipe + 0.035f, yBase + 0.30f, wallTop - 0.14f, 0.14f,
                            Atlas.METAL, 0.29f, 0.30f, 0.29f, 0, 1, 0, 1, 0.9f);
                }
            }
            uAcc += len;
        }

        addRoof(map, g, b, baseY, top, type, central, roofC, fac, id);
    }

    private static void facadeDetails(ChunkGeometry g, MapData.Building b,
                                      float ax, float az, float bx, float bz,
                                      float dx, float dz, float len, float nx, float nz,
                                      float baseY, float wallTop, float[] fac,
                                      int type, boolean central, boolean modern,
                                      boolean shutters, float sr, float sg, float sb,
                                      boolean doorEdge, int edge, int id,
                                      java.util.Random rnd) {
        float height = wallTop - baseY;
        if (height < 3.8f) {
            return;
        }
        int floors = Math.min(modern ? 8 : 6,
                Math.max(1, (int) ((height - 2.5f) / 3.1f)));
        int bays = Math.min(5, Math.max(1, (int) (len / 3.25f)));
        float spacing = len / Math.max(1, bays);
        float tx = dx / len, tz = dz / len;
        float floorH = Math.max(2.8f, (height - 2.5f) / floors);
        float winW = modern ? 1.20f : (type == MapData.Building.HISTORIC ? 0.78f : 0.92f);
        float winH = Math.min(1.55f, floorH * 0.54f);
        float glassR = modern ? 0.72f : 0.82f;
        float glassG = modern ? 0.82f : 0.90f;
        float glassB = modern ? 0.90f : 0.98f;
        boolean shop = type == MapData.Building.SHOP || type == MapData.Building.COMMERCIAL;
        // Deterministic facade rhythm: buildings with the same footprint still get
        // different window spacing, skipped bays, wide windows and balconies.
        int layout = Math.floorMod(id * 13 + edge * 7, 7);
        boolean groundWindow = (type == MapData.Building.PALAZZO
                || type == MapData.Building.HISTORIC || type == MapData.Building.VILLA)
                && len > 5.0f && edge == Math.floorMod(id * 11 + 3,
                Math.max(1, b.vertexCount()));

        if (shop && edge == Math.floorMod(id * 17, Math.max(1, b.vertexCount()))) {
            shopFront(g, ax, az, tx, tz, nx, nz, len, baseY, wallTop, fac, type, id);
        }

        for (int f = 0; f < floors; f++) {
            float wy = baseY + 2.75f + f * floorH;
            if (wy + winH > wallTop - 0.45f) {
                break;
            }
            for (int bay = 0; bay < bays; bay++) {
                float s = (bay + 0.5f) * spacing;
                if (doorEdge && f == 0 && Math.abs(s - len * 0.5f) < 1.9f) {
                    continue;
                }
                // A few closed/blank bays break the repeating grid without hiding
                // the structure or removing a real building.
                if (f > 0 && bays > 2 && (bay * 3 + f * 5 + id + edge * 2) % 17 == 0) {
                    continue;
                }
                float ww = winW;
                if ((type == MapData.Building.VILLA || type == MapData.Building.HISTORIC)
                        && layout % 3 == 0 && bay == bays / 2) {
                    ww *= 1.32f;
                } else if (type == MapData.Building.PALAZZO && layout % 2 == 0
                        && bay % 2 == 1 && bays > 2) {
                    ww *= 0.82f;
                }
                window(g, ax, az, tx, tz, nx, nz, s, wy, ww, winH,
                        glassR, glassG, glassB, fac[0], fac[1], fac[2],
                        shutters, sr, sg, sb, modern);
                boolean balconyType = modern || type == MapData.Building.PALAZZO
                        || type == MapData.Building.VILLA || type == MapData.Building.HISTORIC;
                if (balconyType && f > 0
                        && (bay + f * 2 + id) % (type == MapData.Building.HISTORIC ? 5 : 4) == 0) {
                    balcony(g, ax, az, tx, tz, nx, nz, s, wy - 0.12f, ww + 1.05f,
                            modern ? 0.92f : 0.72f,
                            type == MapData.Building.HISTORIC || type == MapData.Building.VILLA);
                }
            }
        }

        if (groundWindow && bays > 1 && !(doorEdge
                && Math.abs(((Math.floorMod(id, bays) + 0.5f) * spacing) - len * 0.5f) < 1.8f)) {
            float gs = (Math.floorMod(id, bays) + 0.5f) * spacing;
            groundWindow(g, ax, az, tx, tz, nx, nz, gs, baseY);
        }

        if (doorEdge) {
            door(g, ax, az, tx, tz, nx, nz, len * 0.5f, baseY, type, fac, id);
        }
        // A few historic facades get a stone string course.
        if (type == MapData.Building.HISTORIC && central && floors >= 3) {
            float bandY = baseY + 2.75f + floorH * (floors - 1) - 0.32f;
            facadePanel(g, ax, az, bx, bz, nx, nz, 0.4f, len - 0.4f, bandY,
                    bandY + 0.14f, 0.07f, Atlas.STONE, 0.82f, 0.77f, 0.67f,
                    0, 1, 0, 1, 0.9f);
        }
    }

    private static void window(ChunkGeometry g, float ax, float az,
                               float tx, float tz, float nx, float nz,
                               float s, float y, float w, float h,
                               float gr, float gg, float gb,
                               float fr, float fg, float fb,
                               boolean shutters, float sr, float sg, float sb,
                               boolean modern) {
        float s0 = s - w * 0.5f, s1 = s + w * 0.5f;
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s0, s1, y, y + h,
                0.055f, Atlas.WINDOW, gr, gg, gb, 0, 1, 0, 1, 1f);
        float trim = 0.075f;
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s0 - 0.08f, s1 + 0.08f,
                y - 0.11f, y, 0.10f, Atlas.STONE, fr * 1.12f, fg * 1.12f,
                fb * 1.12f, 0, 1, 0, 1, 1f);
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s0 - 0.08f, s1 + 0.08f,
                y + h, y + h + 0.09f, 0.09f, Atlas.STONE, fr * 1.08f,
                fg * 1.08f, fb * 1.08f, 0, 1, 0, 1, 1f);
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s0 - trim, s0,
                y, y + h, 0.07f, Atlas.STONE, fr, fg, fb, 0, 1, 0, 1, 1f);
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s1, s1 + trim,
                y, y + h, 0.07f, Atlas.STONE, fr, fg, fb, 0, 1, 0, 1, 1f);
        if (shutters && !modern) {
            facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s0 - 0.26f, s0 - 0.03f,
                    y, y + h, 0.085f, Atlas.SHUTTER, sr, sg, sb, 0, 1, 0, 1, 1f);
            facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s1 + 0.03f, s1 + 0.26f,
                    y, y + h, 0.085f, Atlas.SHUTTER, sr, sg, sb, 0, 1, 0, 1, 1f);
        }
        if (modern) {
            // A thin horizontal reveal under each floor-to-ceiling window.
            facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s0 - 0.10f, s1 + 0.10f,
                    y - 0.16f, y - 0.10f, 0.08f, Atlas.CONCRETE,
                    fr * 1.15f, fg * 1.15f, fb * 1.15f, 0, 1, 0, 1, 1f);
        }
    }

    private static void balcony(ChunkGeometry g, float ax, float az,
                                float tx, float tz, float nx, float nz,
                                float s, float y, float w, float depth, boolean stone) {
        float s0 = s - w * 0.5f, s1 = s + w * 0.5f;
        float x0 = ax + tx * s0, z0 = az + tz * s0;
        float x1 = ax + tx * s1, z1 = az + tz * s1;
        float[] slab = stone ? new float[]{0.72f, 0.68f, 0.59f}
                : new float[]{0.66f, 0.64f, 0.59f};
        surface(g.current(),
                x0 + nx * 0.04f, y, z0 + nz * 0.04f,
                x1 + nx * 0.04f, y, z1 + nz * 0.04f,
                x1 + nx * depth, y, z1 + nz * depth,
                x0 + nx * depth, y, z0 + nz * depth,
                slab[0], slab[1], slab[2], stone ? Atlas.STONE : Atlas.CONCRETE, 0.3f, false);
        float ry = y + 0.80f;
        float rr = stone ? 0.25f : 0.22f;
        float rg = stone ? 0.26f : 0.23f;
        float rb = stone ? 0.25f : 0.23f;
        // Five balusters, a top rail and a lower rail make the balcony read as a
        // real iron/stone railing instead of a floating slab.
        for (int i = 0; i < 5; i++) {
            float ss = s0 + (s1 - s0) * (i / 4f);
            facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, ss - 0.025f, ss + 0.025f,
                    y + 0.08f, ry, depth * 0.92f, Atlas.METAL, rr, rg, rb,
                    0, 1, 0, 1, 1f);
        }
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s0, s1, ry, ry + 0.055f,
                depth * 0.92f, Atlas.METAL, rr + 0.05f, rg + 0.05f, rb + 0.05f,
                0, 1, 0, 1, 1f);
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s0, s1, y + 0.16f,
                y + 0.21f, depth * 0.92f, Atlas.METAL, rr, rg, rb, 0, 1, 0, 1, 0.9f);
    }

    private static void door(ChunkGeometry g, float ax, float az,
                             float tx, float tz, float nx, float nz,
                             float s, float baseY, int type, float[] fac, int id) {
        float w = type == MapData.Building.PUBLIC ? 1.35f : 1.05f;
        float h = type == MapData.Building.PUBLIC ? 2.5f : 2.2f;
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s - w * 0.5f, s + w * 0.5f,
                baseY + 0.06f, baseY + h, 0.07f, Atlas.SHUTTER,
                0.22f, 0.15f, 0.10f, 0, 1, 0, 1, 1f);
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s - w * 0.5f - 0.09f,
                s - w * 0.5f, baseY + 0.02f, baseY + h + 0.10f, 0.10f,
                Atlas.STONE, 0.74f, 0.69f, 0.58f, 0, 1, 0, 1, 1f);
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s + w * 0.5f,
                s + w * 0.5f + 0.09f, baseY + 0.02f, baseY + h + 0.10f, 0.10f,
                Atlas.STONE, 0.74f, 0.69f, 0.58f, 0, 1, 0, 1, 1f);
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s - w * 0.5f - 0.12f,
                s + w * 0.5f + 0.12f, baseY + h, baseY + h + 0.14f, 0.12f,
                Atlas.STONE, 0.78f, 0.73f, 0.62f, 0, 1, 0, 1, 1f);
        // Two shallow steps.
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s - w * 0.7f,
                s + w * 0.7f, baseY - 0.18f, baseY + 0.02f, 0.34f,
                Atlas.CONCRETE, 0.66f, 0.62f, 0.54f, 0, 1, 0, 1, 1f);
        if (type == MapData.Building.PUBLIC || type == MapData.Building.SHOP
                || type == MapData.Building.COMMERCIAL) {
            float cy = baseY + h + 0.22f;
            float x0 = ax + tx * (s - w * 0.92f) + nx * 0.06f;
            float z0 = az + tz * (s - w * 0.92f) + nz * 0.06f;
            float x1 = ax + tx * (s + w * 0.92f) + nx * 0.06f;
            float z1 = az + tz * (s + w * 0.92f) + nz * 0.06f;
            surface(g.current(),
                    x0, cy, z0, x1, cy, z1,
                    x1 + nx * 0.85f, cy - 0.18f, z1 + nz * 0.85f,
                    x0 + nx * 0.85f, cy - 0.18f, z0 + nz * 0.85f,
                    0.35f, 0.18f, 0.13f, Atlas.PAINT, 0.3f, false);
        }
        // Small wall lantern beside the entrance.
        float lx = ax + tx * (s + w * 0.72f) + nx * 0.12f;
        float lz = az + tz * (s + w * 0.72f) + nz * 0.12f;
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s + w * 0.63f,
                s + w * 0.81f, baseY + h + 0.32f, baseY + h + 0.52f, 0.15f,
                Atlas.METAL, 0.72f, 0.58f, 0.34f, 0, 1, 0, 1, 1f);
        g.addGlow(lx, baseY + h + 0.40f, lz);
        if (type == MapData.Building.SHOP || type == MapData.Building.COMMERCIAL) {
            g.addGlow(ax + tx * s + nx * 0.8f, baseY + 2.6f,
                    az + tz * s + nz * 0.8f);
        }
    }

    private static void shopFront(ChunkGeometry g, float ax, float az,
                                  float tx, float tz, float nx, float nz,
                                  float len, float baseY, float wallTop,
                                  float[] fac, int type, int id) {
        float usable = Math.max(2.0f, len - 2.0f);
        float s0 = (len - usable) * 0.5f;
        float y0 = baseY + 0.25f;
        float y1 = Math.min(baseY + 2.45f, wallTop - 0.5f);
        if (y1 <= y0 + 0.5f) {
            return;
        }
        int panes = Math.max(2, Math.min(4, (int) (usable / 2.4f)));
        float step = usable / panes;
        for (int i = 0; i < panes; i++) {
            float a = s0 + i * step + 0.05f;
            float b = s0 + (i + 1) * step - 0.05f;
            facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, a, b, y0, y1,
                    0.06f, Atlas.WINDOW, 0.78f, 0.88f, 0.96f, 0, 1, 0, 1, 1f);
            facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, a - 0.04f, a,
                    y0, y1, 0.09f, Atlas.METAL, 0.25f, 0.25f, 0.25f,
                    0, 1, 0, 1, 1f);
        }
        // awning
        float ax0 = ax + tx * s0, az0 = az + tz * s0;
        float ax1 = ax + tx * (s0 + usable), az1 = az + tz * (s0 + usable);
        float ay = Math.min(baseY + 2.7f, wallTop - 0.35f);
        surface(g.current(),
                ax0 + nx * 0.06f, ay, az0 + nz * 0.06f,
                ax1 + nx * 0.06f, ay, az1 + nz * 0.06f,
                ax1 + nx * 0.95f, ay - 0.28f, az1 + nz * 0.95f,
                ax0 + nx * 0.95f, ay - 0.28f, az0 + nz * 0.95f,
                0.64f, 0.28f, 0.20f, Atlas.PAINT, 0.3f, false);
        g.addGlow(ax + tx * (s0 + usable * 0.5f) + nx * 1.1f, ay - 0.30f,
                az + tz * (s0 + usable * 0.5f) + nz * 1.1f);
        // Readable sign board above the awning.
        float sy = Math.min(ay + 0.18f, wallTop - 0.24f);
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s0 + usable * 0.12f,
                s0 + usable * 0.88f, sy, sy + 0.42f, 0.10f, Atlas.PAINT,
                0.22f, 0.23f, 0.22f, 0, 1, 0, 1, 1f);
    }

    private static void groundWindow(ChunkGeometry g, float ax, float az,
                                     float tx, float tz, float nx, float nz,
                                     float s, float baseY) {
        float w = 0.64f, h = 1.05f;
        float y = baseY + 0.92f;
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s - w * 0.5f, s + w * 0.5f,
                y, y + h, 0.06f, Atlas.WINDOW, 0.76f, 0.85f, 0.94f,
                0, 1, 0, 1, 1f);
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s - w * 0.5f - 0.08f,
                s - w * 0.5f, y, y + h, 0.10f, Atlas.STONE, 0.78f, 0.73f, 0.62f,
                0, 1, 0, 1, 1f);
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s + w * 0.5f,
                s + w * 0.5f + 0.08f, y, y + h, 0.10f, Atlas.STONE,
                0.78f, 0.73f, 0.62f, 0, 1, 0, 1, 1f);
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s - w * 0.5f - 0.10f,
                s + w * 0.5f + 0.10f, y - 0.10f, y, 0.12f, Atlas.STONE,
                0.82f, 0.77f, 0.66f, 0, 1, 0, 1, 1f);
        // Iron bars: these are cheap, but make the ground floor feel inhabited.
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s - 0.24f, s - 0.20f,
                y + 0.12f, y + h - 0.10f, 0.10f, Atlas.METAL,
                0.22f, 0.23f, 0.24f, 0, 1, 0, 1, 1f);
        facadePanel(g, ax, az, ax + tx, az + tz, nx, nz, s + 0.20f, s + 0.24f,
                y + 0.12f, y + h - 0.10f, 0.10f, Atlas.METAL,
                0.22f, 0.23f, 0.24f, 0, 1, 0, 1, 1f);
    }

    // --------------------------------------------------------------------- roofs

    private static void addRoof(MapData map, ChunkGeometry g, MapData.Building b,
                                float baseY, float top, int type, boolean central,
                                float[] roofC, float[] fac, int id) {
        int roofType = b.roofType;
        int n = b.vertexCount();
        boolean flat = roofType == MapData.Building.ROOF_FLAT
                || type == MapData.Building.MODERN
                || type == MapData.Building.COMMERCIAL
                || type == MapData.Building.INDUSTRIAL;
        float compact = b.area / Math.max(1f, (b.maxX - b.minX) * (b.maxZ - b.minZ));
        if (!flat && (n > 8 || compact < 0.46f)) {
            flat = true;
        }
        if (flat) {
            flatRoof(g, b, top, type, roofC, fac, id);
            return;
        }
        pitchedRoof(g, b, top, roofType, type, central, roofC, fac, id);
    }

    private static void flatRoof(ChunkGeometry g, MapData.Building b, float top,
                                 int type, float[] roofC, float[] fac, int id) {
        int n = b.vertexCount();
        MeshBuilder rb = g.current();
        float roofY = top + 0.08f;
        int cidx = rb.vertex(b.cx, roofY, b.cz, 0, 1, 0,
                roofC[0] * 0.90f, roofC[1] * 0.90f, roofC[2] * 0.90f,
                Atlas.ROOF, b.cx * ROOF_TILING, b.cz * ROOF_TILING);
        int prev = -1;
        for (int i = 0; i <= n; i++) {
            int k = i % n;
            float px = b.polyX(k), pz = b.polyZ(k);
            int v = rb.vertex(px, roofY, pz, 0, 1, 0, roofC[0], roofC[1], roofC[2],
                    Atlas.ROOF, px * ROOF_TILING, pz * ROOF_TILING);
            if (prev >= 0) {
                rb.tri(cidx, prev, v);
            }
            prev = v;
        }
        // Parapet: only for modern/commercial, so old roofs keep their eaves.
        if (type == MapData.Building.MODERN || type == MapData.Building.COMMERCIAL
                || type == MapData.Building.INDUSTRIAL) {
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                float ax = b.polyX(i), az = b.polyZ(i);
                float bx = b.polyX(j), bz = b.polyZ(j);
                float dx = bx - ax, dz = bz - az;
                float len = (float) Math.hypot(dx, dz);
                if (len < 0.4f) continue;
                float nx = dz / len, nz = -dx / len;
                wall(g.current(), ax, az, bx, bz, roofY, roofY + 0.34f, nx, nz,
                        0.68f, 0.69f, 0.68f, Atlas.CONCRETE, 0.4f, 0.9f);
            }
            // Compact rooftop plant / lift housing breaks the flat silhouette.
            if (b.area > 100f) {
                float s = Math.min(3.2f, Math.max(1.4f, (b.maxX - b.minX) * 0.18f));
                rb.box(b.cx + (b.maxX - b.minX) * 0.18f, roofY + 0.65f,
                        b.cz - (b.maxZ - b.minZ) * 0.16f, s * 0.5f, 0.65f,
                        s * 0.45f, 0.56f, 0.57f, 0.57f, Atlas.CONCRETE, 0.35f);
            }
        }
    }

    private static void pitchedRoof(ChunkGeometry g, MapData.Building b, float top,
                                    int roofType, int type, boolean central,
                                    float[] roofC, float[] fac, int id) {
        // Orient the roof along the longest footprint edge. This is still derived
        // from the real OSM polygon; no footprint is replaced or moved.
        float ux = 1, uz = 0, best = -1;
        int n = b.vertexCount();
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float dx = b.polyX(j) - b.polyX(i);
            float dz = b.polyZ(j) - b.polyZ(i);
            float l = dx * dx + dz * dz;
            if (l > best) {
                best = l;
                float inv = 1f / Math.max(0.001f, (float) Math.sqrt(l));
                ux = dx * inv;
                uz = dz * inv;
            }
        }
        float vx = -uz, vz = ux;
        float minU = 1e9f, maxU = -1e9f, minV = 1e9f, maxV = -1e9f;
        for (int i = 0; i < n; i++) {
            float px = b.polyX(i) - b.cx;
            float pz = b.polyZ(i) - b.cz;
            float du = px * ux + pz * uz;
            float dv = px * vx + pz * vz;
            minU = Math.min(minU, du);
            maxU = Math.max(maxU, du);
            minV = Math.min(minV, dv);
            maxV = Math.max(maxV, dv);
        }
        float over = 0.30f;
        minU -= over;
        maxU += over;
        minV -= over;
        maxV += over;
        float w = maxU - minU, d = maxV - minV;
        if (w < 1.2f || d < 1.2f) {
            flatRoof(g, b, top, type, roofC, fac, id);
            return;
        }
        float eaveY = top + 0.02f;
        float roofH = Math.min(3.2f, Math.max(0.9f, Math.min(w, d) * 0.23f));
        if (type == MapData.Building.VILLA || type == MapData.Building.RURAL) {
            roofH = Math.min(3.6f, roofH * 1.15f);
        }
        if (roofType == MapData.Building.ROOF_PYRAMID || d < w * 0.55f) {
            pyramidalRoof(g, b, ux, uz, vx, vz, minU, maxU, minV, maxV,
                    eaveY, roofH, roofC);
        } else if (roofType == MapData.Building.ROOF_SHED) {
            shedRoof(g, b, ux, uz, vx, vz, minU, maxU, minV, maxV,
                    eaveY, roofH, roofC);
        } else if (roofType == MapData.Building.ROOF_GABLE) {
            gableRoof(g, b, ux, uz, vx, vz, minU, maxU, minV, maxV,
                    eaveY, roofH, roofC, fac);
        } else {
            hippedRoof(g, b, ux, uz, vx, vz, minU, maxU, minV, maxV,
                    eaveY, roofH, roofC);
        }
        // Chimney; sparse and deterministic, not a forest of identical blocks.
        if (type != MapData.Building.MODERN && type != MapData.Building.COMMERCIAL
                && (id & 3) == 0) {
            float cu = minU + w * ((id & 7) < 4 ? 0.22f : 0.78f);
            float cv = minV + d * ((id & 8) == 0 ? 0.25f : 0.75f);
            float px = b.cx + ux * cu + vx * cv;
            float pz = b.cz + uz * cu + vz * cv;
            float cy = eaveY + roofH * 0.55f;
            MeshBuilder mb = g.current();
            mb.box(px, cy + 0.55f, pz, 0.23f, 0.55f, 0.23f,
                    0.58f, 0.42f, 0.33f, Atlas.BRICK, 0.6f);
            mb.box(px, cy + 1.12f, pz, 0.30f, 0.05f, 0.30f,
                    0.82f, 0.78f, 0.68f, Atlas.STONE, 0.6f);
        }
    }

    private static void roofPlane(MeshBuilder mb, float x0, float y0, float z0,
                                  float x1, float y1, float z1,
                                  float x2, float y2, float z2,
                                  float x3, float y3, float z3,
                                  float[] roofC) {
        surface(mb, x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3,
                roofC[0], roofC[1], roofC[2], Atlas.ROOF, ROOF_TILING, true);
    }

    /** A raised terracotta ridge gives gabled and hipped roofs a real profile. */
    private static void ridgeCap(MeshBuilder mb, MapData.Building b,
                                 float ux, float uz, float vx, float vz,
                                 float u0, float u1, float v, float y,
                                 float[] roofC) {
        float hw = 0.12f;
        float x0 = b.cx + ux * u0 + vx * (v - hw);
        float z0 = b.cz + uz * u0 + vz * (v - hw);
        float x1 = b.cx + ux * u1 + vx * (v - hw);
        float z1 = b.cz + uz * u1 + vz * (v - hw);
        float x2 = b.cx + ux * u1 + vx * (v + hw);
        float z2 = b.cz + uz * u1 + vz * (v + hw);
        float x3 = b.cx + ux * u0 + vx * (v + hw);
        float z3 = b.cz + uz * u0 + vz * (v + hw);
        surface(mb, x0, y, z0, x1, y, z1, x2, y + 0.04f, z2, x3, y + 0.04f, z3,
                roofC[0] * 1.10f, roofC[1] * 1.04f, roofC[2] * 0.92f,
                Atlas.ROOF, ROOF_TILING, true);
    }

    private static void gableRoof(ChunkGeometry g, MapData.Building b,
                                  float ux, float uz, float vx, float vz,
                                  float minU, float maxU, float minV, float maxV,
                                  float eaveY, float roofH, float[] roofC, float[] fac) {
        float midV = (minV + maxV) * 0.5f;
        float e = eaveY;
        float ridge = e + roofH;
        MeshBuilder mb = g.current();
        float[] c = new float[12];
        // north slope
        point(c, b, ux, uz, vx, vz, minU, minV, e);
        point(c, b, ux, uz, vx, vz, maxU, minV, e);
        point(c, b, ux, uz, vx, vz, maxU, midV, ridge);
        point(c, b, ux, uz, vx, vz, minU, midV, ridge);
        roofPlane(mb, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
                c[9], c[10], c[11], roofC);
        // south slope
        point(c, b, ux, uz, vx, vz, minU, maxV, e);
        point(c, b, ux, uz, vx, vz, maxU, maxV, e);
        point(c, b, ux, uz, vx, vz, maxU, midV, ridge);
        point(c, b, ux, uz, vx, vz, minU, midV, ridge);
        roofPlane(mb, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
                c[9], c[10], c[11], roofC);
        // gable end triangles in facade material
        ridgeCap(mb, b, ux, uz, vx, vz, minU, maxU, midV, ridge + 0.04f, roofC);
        triangleWall(g, b, ux, uz, vx, vz, minU, minV, maxV, e, ridge, fac);
        triangleWall(g, b, ux, uz, vx, vz, maxU, minV, maxV, e, ridge, fac);
    }

    private static void hippedRoof(ChunkGeometry g, MapData.Building b,
                                   float ux, float uz, float vx, float vz,
                                   float minU, float maxU, float minV, float maxV,
                                   float eaveY, float roofH, float[] roofC) {
        float midV = (minV + maxV) * 0.5f;
        float inset = Math.min(maxU - minU, maxV - minV) * 0.24f;
        float ridge = eaveY + roofH;
        MeshBuilder mb = g.current();
        float[] c = new float[12];
        // north and south slopes
        point(c, b, ux, uz, vx, vz, minU, minV, eaveY);
        point(c, b, ux, uz, vx, vz, maxU, minV, eaveY);
        point(c, b, ux, uz, vx, vz, maxU - inset, midV, ridge);
        point(c, b, ux, uz, vx, vz, minU + inset, midV, ridge);
        roofPlane(mb, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
                c[9], c[10], c[11], roofC);
        point(c, b, ux, uz, vx, vz, maxU, maxV, eaveY);
        point(c, b, ux, uz, vx, vz, minU, maxV, eaveY);
        point(c, b, ux, uz, vx, vz, minU + inset, midV, ridge);
        point(c, b, ux, uz, vx, vz, maxU - inset, midV, ridge);
        roofPlane(mb, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
                c[9], c[10], c[11], roofC);
        // hip ends
        point(c, b, ux, uz, vx, vz, minU, minV, eaveY);
        point(c, b, ux, uz, vx, vz, minU, maxV, eaveY);
        point(c, b, ux, uz, vx, vz, minU + inset, midV, ridge);
        roofPlane(mb, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
                c[9], c[10], c[11], roofC);
        point(c, b, ux, uz, vx, vz, maxU, maxV, eaveY);
        point(c, b, ux, uz, vx, vz, maxU, minV, eaveY);
        point(c, b, ux, uz, vx, vz, maxU - inset, midV, ridge);
        roofPlane(mb, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
                c[9], c[10], c[11], roofC);
        ridgeCap(mb, b, ux, uz, vx, vz, minU + inset, maxU - inset,
                midV, ridge + 0.04f, roofC);
    }

    private static void pyramidalRoof(ChunkGeometry g, MapData.Building b,
                                      float ux, float uz, float vx, float vz,
                                      float minU, float maxU, float minV, float maxV,
                                      float eaveY, float roofH, float[] roofC) {
        float cu = (minU + maxU) * 0.5f;
        float cv = (minV + maxV) * 0.5f;
        float apex = eaveY + roofH;
        MeshBuilder mb = g.current();
        float[] c = new float[12];
        point(c, b, ux, uz, vx, vz, minU, minV, eaveY);
        point(c, b, ux, uz, vx, vz, maxU, minV, eaveY);
        point(c, b, ux, uz, vx, vz, cu, cv, apex);
        roofPlane(mb, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
                c[9], c[10], c[11], roofC);
        point(c, b, ux, uz, vx, vz, maxU, minV, eaveY);
        point(c, b, ux, uz, vx, vz, maxU, maxV, eaveY);
        point(c, b, ux, uz, vx, vz, cu, cv, apex);
        roofPlane(mb, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
                c[9], c[10], c[11], roofC);
        point(c, b, ux, uz, vx, vz, maxU, maxV, eaveY);
        point(c, b, ux, uz, vx, vz, minU, maxV, eaveY);
        point(c, b, ux, uz, vx, vz, cu, cv, apex);
        roofPlane(mb, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
                c[9], c[10], c[11], roofC);
        point(c, b, ux, uz, vx, vz, minU, maxV, eaveY);
        point(c, b, ux, uz, vx, vz, minU, minV, eaveY);
        point(c, b, ux, uz, vx, vz, cu, cv, apex);
        roofPlane(mb, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
                c[9], c[10], c[11], roofC);
    }

    private static void shedRoof(ChunkGeometry g, MapData.Building b,
                                 float ux, float uz, float vx, float vz,
                                 float minU, float maxU, float minV, float maxV,
                                 float eaveY, float roofH, float[] roofC) {
        float ridgeY = eaveY + roofH;
        MeshBuilder mb = g.current();
        float[] c = new float[12];
        point(c, b, ux, uz, vx, vz, minU, minV, eaveY);
        point(c, b, ux, uz, vx, vz, maxU, minV, eaveY);
        point(c, b, ux, uz, vx, vz, maxU, maxV, ridgeY);
        point(c, b, ux, uz, vx, vz, minU, maxV, ridgeY);
        roofPlane(mb, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
                c[9], c[10], c[11], roofC);
    }

    private static void point(float[] out, MapData.Building b,
                              float ux, float uz, float vx, float vz,
                              float u, float v, float y) {
        out[0] = b.cx + ux * u + vx * v;
        out[1] = y;
        out[2] = b.cz + uz * u + vz * v;
    }

    private static void triangleWall(ChunkGeometry g, MapData.Building b,
                                     float ux, float uz, float vx, float vz,
                                     float u, float minV, float maxV,
                                     float eaveY, float ridgeY, float[] fac) {
        MeshBuilder mb = g.current();
        float x0 = b.cx + ux * u + vx * minV;
        float z0 = b.cz + uz * u + vz * minV;
        float x1 = b.cx + ux * u + vx * maxV;
        float z1 = b.cz + uz * u + vz * maxV;
        float xm = b.cx + ux * u + vx * (minV + maxV) * 0.5f;
        float zm = b.cz + uz * u + vz * (minV + maxV) * 0.5f;
        int a = mb.vertex(x0, eaveY, z0, -ux, 0, -uz, fac[0] * 0.92f,
                fac[1] * 0.92f, fac[2] * 0.92f, Atlas.PLASTER, 0, 0);
        int bb = mb.vertex(x1, eaveY, z1, -ux, 0, -uz, fac[0] * 0.92f,
                fac[1] * 0.92f, fac[2] * 0.92f, Atlas.PLASTER, 1, 0);
        int c = mb.vertex(xm, ridgeY, zm, -ux, 0, -uz, fac[0], fac[1], fac[2],
                Atlas.PLASTER, 0.5f, 1);
        mb.tri(a, bb, c);
    }

    /**
     * Cheap LOD representation: one box per building. Every footprint is kept;
     * LOD1 also keeps a shallow roof silhouette so distant skylines read correctly.
     */
    private static void simpleBuilding(MapData map, ChunkGeometry g, MapData.Building b, int lod) {
        float w = Math.max(1.0f, b.maxX - b.minX);
        float d = Math.max(1.0f, b.maxZ - b.minZ);
        float baseY = map.heightAt(b.cx, b.cz) - 0.25f;
        float[] fac = Palette.facade(b.facade);
        float[] roofC = Palette.roof(b.roof);
        float shade = lod == 1 ? 0.86f : 0.74f;
        MeshBuilder mb = g.current();
        mb.box(b.cx, baseY + b.height * 0.5f, b.cz, w * 0.5f, b.height * 0.5f,
                d * 0.5f, fac[0] * shade, fac[1] * shade, fac[2] * shade,
                Atlas.FACADE, TILING);
        float top = baseY + b.height;
        boolean pitched = b.roofType != MapData.Building.ROOF_FLAT
                && b.type != MapData.Building.MODERN
                && b.type != MapData.Building.INDUSTRIAL
                && b.type != MapData.Building.COMMERCIAL;
        
        if (pitched && Math.min(w, d) > 2.2f && b.area < 5000f) {
            float roofH = lod == 1 ? Math.min(2.2f, Math.min(w, d) * 0.18f)
                    : Math.min(1.0f, Math.min(w, d) * 0.12f);
            boolean xLong = w >= d;
            if (xLong) {
                cargoRoof(mb, b.cx, top, b.cz, w, d, roofH, roofC, true);
            } else {
                cargoRoof(mb, b.cx, top, b.cz, d, w, roofH, roofC, false);
            }
        } else {
            mb.flatQuad(b.minX, b.minZ, b.maxX, b.maxZ, top + 0.05f,
                    roofC[0], roofC[1], roofC[2], Atlas.ROOF, ROOF_TILING, ROOF_TILING);
        }
        if (lod == 1) {
            // A simple cornice keeps LOD1 recognisable at a glance, while the
            // footprint and complete silhouette remain untouched.
            mb.box(b.cx, top + 0.12f, b.cz, w * 0.5f + 0.08f, 0.10f,
                    d * 0.5f + 0.08f, fac[0] * 0.82f, fac[1] * 0.82f,
                    fac[2] * 0.82f, Atlas.STONE, 0.45f);
        }
    }

    private static void cargoRoof(MeshBuilder mb, float cx, float y, float cz,
                                  float longAxis, float shortAxis, float roofH,
                                  float[] roofC, boolean xLong) {
        float hl = longAxis * 0.5f, hs = shortAxis * 0.5f;
        if (xLong) {
            roofPlane(mb, cx - hl, y, cz - hs, cx + hl, y, cz - hs,
                    cx + hl, y + roofH, cz, cx - hl, y + roofH, cz, roofC);
            roofPlane(mb, cx + hl, y, cz + hs, cx - hl, y, cz + hs,
                    cx - hl, y + roofH, cz, cx + hl, y + roofH, cz, roofC);
        } else {
            roofPlane(mb, cx - hs, y, cz - hl, cx - hs, y, cz + hl,
                    cx, y + roofH, cz + hl, cx, y + roofH, cz - hl, roofC);
            roofPlane(mb, cx + hs, y, cz + hl, cx + hs, y, cz - hl,
                    cx, y + roofH, cz - hl, cx, y + roofH, cz + hl, roofC);
        }
    }

    // -------------------------------------------------------------------- props

    private static void props(MapData map, ChunkGeometry g, int cx, int cz,
                              float x0, float z0, float x1, float z1) {
        java.util.Random rnd = new java.util.Random((cx * 73856093L) ^ (cz * 19349663L));
        int[] areas = map.areasIn(cx, cz);
        for (int idx : areas) {
            MapData.Area a = map.area(idx);
            if (a.maxX < x0 || a.minX > x1 || a.maxZ < z0 || a.minZ > z1) {
                continue;
            }
            if (a.kind == 5 || a.kind == 6 || a.kind == 7 || a.kind == 8) {
                int count = Math.min(30, Math.max(1, (int) (a.radius / 11f)));
                for (int i = 0; i < count; i++) {
                    float px = a.minX + rnd.nextFloat() * (a.maxX - a.minX);
                    float pz = a.minZ + rnd.nextFloat() * (a.maxZ - a.minZ);
                    if (!a.contains(px, pz) || map.blocked(px, pz, 1.15f)) {
                        continue;
                    }
                    MapData.RoadHit hit = map.nearestRoad(px, pz, false, 3.2f);
                    if (hit != null) {
                        continue;
                    }
                    tree(g.current(), px, mountY(map, px, pz, 0.0f), pz,
                            2.6f + rnd.nextFloat() * 3.8f, rnd,
                            a.kind == 6 ? 1 : 0);
                }
                if ((a.kind == 7 || a.kind == 4) && a.radius < 240f) {
                    // Low hedges frame parks and fields, with gaps at paths and roads.
                    int n = a.pts.length / 2;
                    for (int i = 0; i < n; i++) {
                        int j = (i + 1) % n;
                        float ax = a.pts[i * 2], az = a.pts[i * 2 + 1];
                        float bx = a.pts[j * 2], bz = a.pts[j * 2 + 1];
                        float len = (float) Math.hypot(bx - ax, bz - az);
                        if (len < 5f || len > 42f || rnd.nextFloat() > (a.kind == 7 ? 0.42f : 0.24f)) {
                            continue;
                        }
                        float mx = (ax + bx) * 0.5f;
                        float mz = (az + bz) * 0.5f;
                        if (map.blocked(mx, mz, 0.8f)
                                || map.nearestRoad(mx, mz, false, 2.2f) != null) {
                            continue;
                        }
                        hedge(g.current(), mx, mountY(map, mx, mz, 0.0f), mz,
                                Math.min(len * 0.42f, 8f), Math.abs(bx - ax) >= Math.abs(bz - az));
                    }
                }
                if (a.kind == 7 || a.kind == 8) {
                    int furniture = Math.min(4, Math.max(1, (int) (a.radius / 32f)));
                    for (int i = 0; i < furniture; i++) {
                        float px = a.minX + rnd.nextFloat() * (a.maxX - a.minX);
                        float pz = a.minZ + rnd.nextFloat() * (a.maxZ - a.minZ);
                        if (!a.contains(px, pz) || map.blocked(px, pz, 0.8f)) {
                            continue;
                        }
                        if (rnd.nextBoolean()) {
                            bench(g.current(), px, mountY(map, px, pz, 0.12f), pz, rnd.nextBoolean());
                        } else {
                            bin(g.current(), px, mountY(map, px, pz, 0.0f), pz);
                        }
                    }
                }
            } else if (a.kind == 4) {
                // A field gets hedgerows and a few isolated trees at its edge, not
                // a random forest that would hide the real field geometry.
                int n = a.pts.length / 2;
                for (int i = 0; i < n; i += 2) {
                    int j = (i + 1) % n;
                    float ax = a.pts[i * 2], az = a.pts[i * 2 + 1];
                    float bx = a.pts[j * 2], bz = a.pts[j * 2 + 1];
                    if (Math.hypot(bx - ax, bz - az) > 32f) {
                        continue;
                    }
                    if (rnd.nextFloat() > 0.32f) {
                        continue;
                    }
                    float px = (ax + bx) * 0.5f + (rnd.nextFloat() - 0.5f) * 2f;
                    float pz = (az + bz) * 0.5f + (rnd.nextFloat() - 0.5f) * 2f;
                    if (!map.blocked(px, pz, 0.8f)) {
                        tree(g.current(), px, mountY(map, px, pz, 0.0f), pz,
                                2.4f + rnd.nextFloat() * 2.0f, rnd, 1);
                    }
                }
            } else if (a.kind == 12) {
                parking(g, map, a, rnd);
            } else if (a.kind == 3 && a.radius < 230f) {
                // Industrial yards get a readable perimeter instead of an empty slab.
                int n = a.pts.length / 2;
                for (int i = 0; i < n; i += 2) {
                    int j = (i + 1) % n;
                    float ax = a.pts[i * 2], az = a.pts[i * 2 + 1];
                    float bx = a.pts[j * 2], bz = a.pts[j * 2 + 1];
                    if (Math.hypot(bx - ax, bz - az) > 48f || rnd.nextFloat() > 0.34f) {
                        continue;
                    }
                    float mx = (ax + bx) * 0.5f, mz = (az + bz) * 0.5f;
                    if (!map.blocked(mx, mz, 0.7f)
                            && map.nearestRoad(mx, mz, false, 2.5f) == null) {
                        fence(g.current(), ax, az, bx, bz, mountY(map, mx, mz, 0.0f));
                    }
                }
            }
        }

        // Street lamps, residential trees and small furniture follow the real road
        // network. Spacing varies by class instead of dropping props at random.
        int[] roads = map.roadsIn(cx, cz);
        for (int idx : roads) {
            MapData.RoadWay w = map.road(idx);
            if (!w.drivable || w.length < 6f) {
                continue;
            }
            float spacing = w.cls <= 2 ? 30f : (w.cls <= 5 ? 38f : 52f);
            int count = Math.min(18, (int) (w.length / spacing));
            for (int i = 0; i <= count; i++) {
                float d = i * spacing + rnd.nextFloat() * 5f;
                float[] p = pointAt(w, d);
                if (p == null || p[0] < x0 || p[0] > x1 || p[2] < z0 || p[2] > z1) {
                    continue;
                }
                int side = ((i + cx + cz) % 2 == 0) ? 1 : -1;
                float lx = p[0] + p[3] * side * (w.width * 0.5f + 1.35f);
                float lz = p[2] + p[4] * side * (w.width * 0.5f + 1.35f);
                if (map.blocked(lx, lz, 0.4f)) {
                    continue;
                }
                if (i % 3 == 0 || w.cls <= 4) {
                    lamp(g, lx, mountY(map, lx, lz, 0.12f), lz,
                            Math.floorMod(i + idx, 3));
                }
                if (w.cls >= 4 && w.cls <= 7 && (i & 1) == 0) {
                    float tx = p[0] + p[3] * side * (w.width * 0.5f + 2.2f);
                    float tz = p[2] + p[4] * side * (w.width * 0.5f + 2.2f);
                    if (!map.blocked(tx, tz, 0.7f)) {
                        tree(g.current(), tx, mountY(map, tx, tz, 0.0f), tz,
                                3.0f + (i % 3) * 0.7f, rnd, 0);
                    }
                }
                if (i % 5 == 1) {
                    float bx = p[0] + p[3] * side * (w.width * 0.5f + 0.9f);
                    float bz = p[2] + p[4] * side * (w.width * 0.5f + 0.9f);
                    if (!map.blocked(bx, bz, 0.35f)) {
                        bin(g.current(), bx, mountY(map, bx, bz, 0.0f), bz);
                    }
                }
                if (i % 4 == 2) {
                    float px = p[0] + p[3] * side * (w.width * 0.5f + 1.05f);
                    float pz = p[2] + p[4] * side * (w.width * 0.5f + 1.05f);
                    if (!map.blocked(px, pz, 0.35f)) {
                        planter(g.current(), px, mountY(map, px, pz, 0.0f), pz);
                    }
                }
                if (i == 2 && w.cls <= 4) {
                    float sx = p[0] + p[3] * side * (w.width * 0.5f + 1.1f);
                    float sz = p[2] + p[4] * side * (w.width * 0.5f + 1.1f);
                    roadSign(g.current(), sx, mountY(map, sx, sz, 0.0f), sz,
                            p[4], -p[3]);
                }
            }
        }

        // Traffic signals and crossings come from the real OSM signal nodes.
        for (int pi : map.poisIn(cx, cz)) {
            MapData.Poi poi = map.poi(pi);
            if (poi.x < x0 || poi.x > x1 || poi.z < z0 || poi.z > z1) {
                continue;
            }
            if (poi.cat == 10) {
                MapData.RoadHit hit = map.nearestRoad(poi.x, poi.z, true, 12f);
                if (hit != null) {
                    trafficSignal(g, map, hit);
                    crosswalk(g, map, hit);
                }
            } else if (poi.cat == 9) {
                if (!map.blocked(poi.x, poi.z, 0.8f)) {
                    busStop(g.current(), poi.x, mountY(map, poi.x, poi.z, 0.0f), poi.z);
                }
            }
        }
    }

    private static void parking(ChunkGeometry g, MapData map, MapData.Area a, java.util.Random rnd) {
        MeshBuilder mb = g.current();
        float step = 5.2f;
        int max = 18;
        for (float z = a.minZ + 2f; z < a.maxZ - 2f && max > 0; z += step) {
            for (float x = a.minX + 2f; x < a.maxX - 2f && max > 0; x += step) {
                if (!a.contains(x, z) || map.blocked(x, z, 1.4f)) {
                    continue;
                }
                MapData.RoadHit hit = map.nearestRoad(x, z, true, 2.5f);
                if (hit != null) {
                    continue;
                }
                float y = mountY(map, x, z, 0.12f);
                surface(mb, x, y, z, x + 2.3f, y, z,
                        x + 2.3f, y, z + 4.0f, x, y, z + 4.0f,
                        0.70f, 0.69f, 0.65f, Atlas.PAINT, 0.0f, false);
                max--;
            }
        }
    }

    private static void crosswalk(ChunkGeometry g, MapData map, MapData.RoadHit hit) {
        int seg = hit.segment * 2;
        if (seg + 3 >= hit.way.pts.length) {
            return;
        }
        float ax = hit.way.pts[seg], az = hit.way.pts[seg + 1];
        float bx = hit.way.pts[seg + 2], bz = hit.way.pts[seg + 3];
        float dx = bx - ax, dz = bz - az;
        float len = (float) Math.hypot(dx, dz);
        if (len < 1f) return;
        float tx = dx / len, tz = dz / len;
        float hw = Math.min(5.5f, hit.way.width * 0.38f);
        crosswalkBars(map, g.current(), hit.x, hit.z, tx, tz, hw);
    }

    /** Crossings only where a second drivable road actually meets the first. */
    private static void junctionCrosswalk(MapData map, MeshBuilder mb, MapData.RoadWay w,
                                          float x, float z, float tx, float tz, float hw) {
        float px = -tz, pz = tx;
        boolean junction = false;
        for (int side = -1; side <= 1; side += 2) {
            MapData.RoadHit hit = map.nearestRoad(x + px * side * (hw + 2.0f),
                    z + pz * side * (hw + 2.0f), true, 6f);
            if (hit != null && hit.way.id != w.id) {
                junction = true;
                break;
            }
        }
        if (!junction) {
            return;
        }
        crosswalkBars(map, mb, x, z, tx, tz, Math.min(5.5f, w.width * 0.42f));
    }

    private static void crosswalkBars(MapData map, MeshBuilder mb, float x, float z,
                                      float tx, float tz, float hw) {
        float px = -tz, pz = tx;
        float y = mountY(map, x, z, 0.13f);
        for (int i = 0; i < 6; i++) {
            float off = (i - 2.5f) * 0.55f;
            float x0 = x + tx * off - px * hw;
            float z0 = z + tz * off - pz * hw;
            float x1 = x + tx * off + px * hw;
            float z1 = z + tz * off + pz * hw;
            float x2 = x1 + tx * 0.28f;
            float z2 = z1 + tz * 0.28f;
            float x3 = x0 + tx * 0.28f;
            float z3 = z0 + tz * 0.28f;
            surface(mb, x0, y, z0, x1, y, z1, x2, y, z2, x3, y, z3,
                    0.93f, 0.91f, 0.80f, Atlas.PAINT, 0.0f, false);
        }
        surface(mb, x + tx * 1.9f - px * hw, y, z + tz * 1.9f - pz * hw,
                x + tx * 1.9f + px * hw, y, z + tz * 1.9f + pz * hw,
                x + tx * 2.2f + px * hw, y, z + tz * 2.2f + pz * hw,
                x + tx * 2.2f - px * hw, y, z + tz * 2.2f - pz * hw,
                0.93f, 0.91f, 0.80f, Atlas.PAINT, 0.0f, false);
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

    private static float mountY(MapData map, float x, float z, float offset) {
        return map.heightAt(x, z) + offset;
    }

    private static void tree(MeshBuilder mb, float x, float y, float z, float size,
                             java.util.Random rnd, int kind) {
        float trunkH = size * 0.55f;
        float crown = size * 0.45f;
        int species = kind == 1 ? 1
                : (rnd.nextFloat() < 0.48f ? 0 : (rnd.nextBoolean() ? 2 : 3));
        float g = 0.62f + rnd.nextFloat() * 0.18f;
        if (species == 1) {
            // Cypress: narrow columnar silhouette, common in the Pistoia hills.
            mb.box(x, y + trunkH * 0.5f, z, 0.12f, trunkH, 0.12f,
                    0.27f, 0.20f, 0.14f, Atlas.METAL, 0.6f);
            mb.box(x, y + trunkH + crown * 1.08f, z, crown * 0.36f, crown * 1.55f,
                    crown * 0.36f, 0.16f * g, 0.33f * g, 0.14f * g,
                    Atlas.FOLIAGE, 0.30f);
            mb.box(x, y + trunkH + crown * 2.08f, z, crown * 0.20f, crown * 0.50f,
                    crown * 0.20f, 0.20f * g, 0.40f * g, 0.16f * g,
                    Atlas.FOLIAGE, 0.34f);
        } else if (species == 2) {
            // Stone pine: visible trunk and a broad, flat umbrella crown.
            mb.box(x, y + size * 0.34f, z, 0.20f, size * 0.34f, 0.20f,
                    0.32f, 0.22f, 0.14f, Atlas.METAL, 0.6f);
            mb.box(x, y + size * 0.73f, z, crown * 0.92f, crown * 0.36f,
                    crown * 0.92f, 0.20f * g, 0.37f * g, 0.15f * g,
                    Atlas.FOLIAGE, 0.30f);
            mb.box(x, y + size * 0.93f, z, crown * 0.52f, crown * 0.28f,
                    crown * 0.52f, 0.24f * g, 0.43f * g, 0.18f * g,
                    Atlas.FOLIAGE, 0.34f);
        } else if (species == 3) {
            // Olive: shorter, silver-green, irregular crown.
            mb.box(x, y + trunkH * 0.42f, z, 0.14f, trunkH * 0.42f, 0.14f,
                    0.29f, 0.24f, 0.16f, Atlas.METAL, 0.6f);
            mb.box(x, y + trunkH + crown * 0.48f, z, crown * 0.72f, crown * 0.58f,
                    crown * 0.72f, 0.30f * g, 0.43f * g, 0.28f * g,
                    Atlas.FOLIAGE, 0.30f);
            mb.box(x + crown * 0.34f, y + trunkH + crown * 0.86f,
                    z - crown * 0.20f, crown * 0.42f, crown * 0.38f, crown * 0.42f,
                    0.33f * g, 0.47f * g, 0.31f * g, Atlas.FOLIAGE, 0.34f);
        } else {
            // Plane/lime: layered broadleaf canopy.
            mb.box(x, y + trunkH * 0.5f, z, 0.16f, trunkH, 0.16f,
                    0.30f, 0.22f, 0.15f, Atlas.METAL, 0.6f);
            mb.box(x, y + trunkH + crown * 0.55f, z, crown, crown * 0.95f, crown,
                    0.22f * g, 0.42f * g, 0.16f * g, Atlas.FOLIAGE, 0.30f);
            mb.box(x, y + trunkH + crown * 1.55f, z, crown * 0.64f, crown * 0.72f,
                    crown * 0.64f, 0.27f * g, 0.49f * g, 0.20f * g,
                    Atlas.FOLIAGE, 0.34f);
            mb.box(x + crown * 0.35f, y + trunkH + crown * 1.15f, z - crown * 0.25f,
                    crown * 0.48f, crown * 0.52f, crown * 0.48f,
                    0.25f * g, 0.45f * g, 0.18f * g, Atlas.FOLIAGE, 0.30f);
        }
    }

    private static void lamp(ChunkGeometry g, float x, float y, float z, int style) {
        MeshBuilder mb = g.current();
        if (style == 1) {
            // Historic lantern: shorter pole, warm four-sided head.
            mb.box(x, y + 2.25f, z, 0.075f, 2.25f, 0.075f,
                    0.23f, 0.24f, 0.23f, Atlas.METAL, 0.5f);
            mb.box(x, y + 4.58f, z, 0.22f, 0.28f, 0.22f,
                    0.92f, 0.83f, 0.59f, Atlas.GLASS, 0.5f);
            mb.box(x, y + 4.90f, z, 0.27f, 0.05f, 0.27f,
                    0.24f, 0.25f, 0.24f, Atlas.METAL, 0.5f);
            g.addGlow(x, y + 4.62f, z);
        } else if (style == 2) {
            // Modern LED: slender pole with a short asymmetric arm.
            mb.box(x, y + 2.55f, z, 0.055f, 2.55f, 0.055f,
                    0.30f, 0.32f, 0.33f, Atlas.METAL, 0.5f);
            mb.box(x + 0.30f, y + 5.08f, z, 0.30f, 0.045f, 0.045f,
                    0.26f, 0.27f, 0.28f, Atlas.METAL, 0.5f);
            mb.box(x + 0.58f, y + 5.02f, z, 0.18f, 0.075f, 0.12f,
                    0.94f, 0.88f, 0.68f, Atlas.PAINT, 0.5f);
            g.addGlow(x + 0.58f, y + 4.98f, z);
        } else {
            // Classic curved streetlight.
            mb.box(x, y + 2.7f, z, 0.07f, 2.7f, 0.07f, 0.30f, 0.31f, 0.32f,
                    Atlas.METAL, 0.5f);
            mb.box(x + 0.34f, y + 5.28f, z, 0.34f, 0.06f, 0.06f,
                    0.28f, 0.29f, 0.30f, Atlas.METAL, 0.5f);
            mb.box(x + 0.68f, y + 5.16f, z, 0.22f, 0.12f, 0.16f,
                    0.92f, 0.85f, 0.64f, Atlas.PAINT, 0.5f);
            g.addGlow(x + 0.68f, y + 5.12f, z);
        }
        // occasional litter bin at the foot of the pole
        if (((int) (x * 3 + z * 5) & 15) == 0) {
            bin(mb, x - 0.45f, y, z);
        }
    }

    private static void bench(MeshBuilder mb, float x, float y, float z, boolean alongX) {
        float sx = alongX ? 0.85f : 0.26f;
        float sz = alongX ? 0.26f : 0.85f;
        mb.box(x, y + 0.42f, z, sx, 0.06f, sz, 0.40f, 0.28f, 0.18f, Atlas.SHUTTER, 0.6f);
        mb.box(x, y + 0.75f, z, sx, 0.28f, 0.05f, 0.36f, 0.25f, 0.16f, Atlas.SHUTTER, 0.6f);
        mb.box(x - sx * 0.72f, y + 0.21f, z, 0.06f, 0.21f, sz * 0.6f,
                0.22f, 0.23f, 0.24f, Atlas.METAL, 0.6f);
        mb.box(x + sx * 0.72f, y + 0.21f, z, 0.06f, 0.21f, sz * 0.6f,
                0.22f, 0.23f, 0.24f, Atlas.METAL, 0.6f);
    }

    private static void bin(MeshBuilder mb, float x, float y, float z) {
        mb.box(x, y + 0.42f, z, 0.22f, 0.42f, 0.22f,
                0.27f, 0.34f, 0.29f, Atlas.METAL, 0.7f);
        mb.box(x, y + 0.87f, z, 0.24f, 0.045f, 0.24f,
                0.20f, 0.24f, 0.22f, Atlas.METAL, 0.7f);
    }

    private static void hedge(MeshBuilder mb, float x, float y, float z, float len, boolean xAxis) {
        float hx = xAxis ? len * 0.5f : 0.42f;
        float hz = xAxis ? 0.42f : len * 0.5f;
        mb.box(x, y + 0.52f, z, hx, 0.52f, hz, 0.22f, 0.40f, 0.18f,
                Atlas.FOLIAGE, 0.35f);
    }

    private static void planter(MeshBuilder mb, float x, float y, float z) {
        mb.box(x, y + 0.28f, z, 0.34f, 0.28f, 0.34f, 0.58f, 0.54f, 0.48f,
                Atlas.CONCRETE, 0.6f);
        mb.box(x, y + 0.72f, z, 0.27f, 0.24f, 0.27f, 0.24f, 0.43f, 0.19f,
                Atlas.FOLIAGE, 0.4f);
    }

    private static void fence(MeshBuilder mb, float ax, float az, float bx, float bz,
                              float y) {
        float dx = bx - ax, dz = bz - az;
        float len = (float) Math.hypot(dx, dz);
        if (len < 1.0f) return;
        float tx = dx / len, tz = dz / len;
        int posts = Math.max(2, (int) (len / 4.0f));
        for (int i = 0; i <= posts; i++) {
            float s = len * i / posts;
            mb.box(ax + tx * s, y + 0.55f, az + tz * s, 0.055f, 0.55f, 0.055f,
                    0.30f, 0.31f, 0.31f, Atlas.METAL, 0.6f);
        }
        // Two horizontal rails, broken into short segments so diagonal yards do
        // not get one oversized axis-aligned beam.
        int rails = Math.max(1, (int) (len / 4.0f));
        for (int r = 0; r < 2; r++) {
            float ry = y + 0.45f + r * 0.45f;
            for (int i = 0; i < rails; i++) {
                float s = (i + 0.5f) * len / rails;
                mb.box(ax + tx * s, ry, az + tz * s,
                        Math.min(2.1f, len / rails * 0.55f), 0.035f, 0.035f,
                        0.34f, 0.35f, 0.34f, Atlas.METAL, 0.6f);
            }
        }
    }

    private static void busStop(MeshBuilder mb, float x, float y, float z) {
        mb.box(x - 1.8f, y + 1.25f, z, 0.06f, 1.25f, 0.06f,
                0.34f, 0.36f, 0.38f, Atlas.METAL, 0.6f);
        mb.box(x + 1.8f, y + 1.25f, z, 0.06f, 1.25f, 0.06f,
                0.34f, 0.36f, 0.38f, Atlas.METAL, 0.6f);
        mb.box(x, y + 2.55f, z, 2.0f, 0.08f, 0.85f,
                0.28f, 0.31f, 0.34f, Atlas.METAL, 0.5f);
        mb.box(x, y + 1.55f, z + 0.55f, 1.65f, 0.75f, 0.04f,
                0.46f, 0.55f, 0.60f, Atlas.GLASS, 0.4f);
        mb.box(x, y + 1.45f, z - 0.42f, 1.25f, 0.06f, 0.26f,
                0.38f, 0.28f, 0.17f, Atlas.SHUTTER, 0.6f);
        mb.box(x + 2.35f, y + 1.55f, z, 0.28f, 1.55f, 0.08f,
                0.18f, 0.42f, 0.52f, Atlas.PAINT, 0.5f);
    }

    private static void trafficSignal(ChunkGeometry g, MapData map, MapData.RoadHit hit) {
        int seg = hit.segment * 2;
        if (seg + 3 >= hit.way.pts.length) return;
        float dx = hit.way.pts[seg + 2] - hit.way.pts[seg];
        float dz = hit.way.pts[seg + 3] - hit.way.pts[seg + 1];
        float len = (float) Math.hypot(dx, dz);
        if (len < 0.2f) return;
        float nx = -dz / len, nz = dx / len;
        float x = hit.x + nx * (hit.way.width * 0.5f + 0.9f);
        float z = hit.z + nz * (hit.way.width * 0.5f + 0.9f);
        if (map.blocked(x, z, 0.4f)) return;
        float y = mountY(map, x, z, 0.0f);
        MeshBuilder mb = g.current();
        mb.box(x, y + 1.55f, z, 0.07f, 1.55f, 0.07f,
                0.20f, 0.21f, 0.22f, Atlas.METAL, 0.6f);
        mb.box(x, y + 3.05f, z, 0.24f, 0.55f, 0.18f,
                0.15f, 0.16f, 0.16f, Atlas.METAL, 0.7f);
        mb.box(x, y + 3.25f, z + 0.01f, 0.11f, 0.10f, 0.10f,
                0.90f, 0.14f, 0.10f, Atlas.PAINT, 0.8f);
        mb.box(x, y + 3.02f, z + 0.01f, 0.11f, 0.10f, 0.10f,
                0.92f, 0.70f, 0.16f, Atlas.PAINT, 0.8f);
        mb.box(x, y + 2.79f, z + 0.01f, 0.11f, 0.10f, 0.10f,
                0.18f, 0.68f, 0.28f, Atlas.PAINT, 0.8f);
        g.addGlow(x, y + 3.02f, z + 0.12f);
    }

    private static void roadSign(MeshBuilder mb, float x, float y, float z,
                                 float nx, float nz) {
        mb.box(x, y + 1.05f, z, 0.045f, 1.05f, 0.045f,
                0.28f, 0.30f, 0.31f, Atlas.METAL, 0.6f);
        mb.box(x, y + 2.05f, z, 0.38f, 0.27f, 0.035f,
                0.83f, 0.84f, 0.80f, Atlas.PAINT, 0.4f);
        mb.box(x, y + 2.05f, z + 0.045f, 0.28f, 0.06f, 0.012f,
                0.16f, 0.32f, 0.52f, Atlas.PAINT, 0.4f);
    }

    // ------------------------------------------------------------------ helpers

    private static MeshBuilder mb(ChunkGeometry g) {
        return g.current();
    }

    private static float[] blend(float[] a, float[] b, float t) {
        return new float[]{a[0] + (b[0] - a[0]) * t,
                a[1] + (b[1] - a[1]) * t,
                a[2] + (b[2] - a[2]) * t};
    }

    /** Adds a vertical wall with a subtle contact shadow at its base. */
    private static void wall(MeshBuilder mb, float ax, float az, float bx, float bz,
                             float y0, float y1, float nx, float nz,
                             float r, float g, float b, int tile, float uScale,
                             float bottomShade) {
        float len = (float) Math.hypot(bx - ax, bz - az);
        if (len < 0.01f || y1 <= y0) return;
        float u1 = len * uScale;
        int a = mb.vertex(ax, y0, az, nx, 0, nz, r * bottomShade,
                g * bottomShade, b * bottomShade, tile, 0, 0);
        int bb = mb.vertex(bx, y0, bz, nx, 0, nz, r * bottomShade,
                g * bottomShade, b * bottomShade, tile, u1, 0);
        int c = mb.vertex(bx, y1, bz, nx, 0, nz, r, g, b, tile, u1,
                (y1 - y0) * uScale);
        int d = mb.vertex(ax, y1, az, nx, 0, nz, r, g, b, tile, 0,
                (y1 - y0) * uScale);
        mb.quad(a, bb, c, d);
    }

    /** Wall surface with explicit longitudinal UVs, used by walls spanning a whole edge. */
    private static void wall(MeshBuilder mb, float ax, float az, float bx, float bz,
                             float y0, float y1, float nx, float nz,
                             float r, float g, float b, int tile,
                             float u0, float u1, float v0, float v1, float bottomShade) {
        int a = mb.vertex(ax, y0, az, nx, 0, nz, r * bottomShade,
                g * bottomShade, b * bottomShade, tile, u0, v0);
        int bb = mb.vertex(bx, y0, bz, nx, 0, nz, r * bottomShade,
                g * bottomShade, b * bottomShade, tile, u1, v0);
        int c = mb.vertex(bx, y1, bz, nx, 0, nz, r, g, b, tile, u1, v1);
        int d = mb.vertex(ax, y1, az, nx, 0, nz, r, g, b, tile, u0, v1);
        mb.quad(a, bb, c, d);
    }

    /**
     * Adds a quad on a facade, measured along the supplied edge. s0..s1 are metres
     * from the edge start; off pushes it out of the wall to avoid z-fighting.
     */
    private static void facadePanel(ChunkGeometry g, float ax, float az,
                                    float bx, float bz, float nx, float nz,
                                    float s0, float s1, float y0, float y1,
                                    float off, int tile, float r, float gg, float b,
                                    float u0, float u1, float v0, float v1,
                                    float bottomShade) {
        float dx = bx - ax, dz = bz - az;
        float len = (float) Math.hypot(dx, dz);
        if (len < 0.001f) return;
        float tx = dx / len, tz = dz / len;
        float x0 = ax + tx * s0 + nx * off;
        float z0 = az + tz * s0 + nz * off;
        float x1 = ax + tx * s1 + nx * off;
        float z1 = az + tz * s1 + nz * off;
        MeshBuilder mb = g.current();
        int a = mb.vertex(x0, y0, z0, nx, 0, nz, r * bottomShade,
                gg * bottomShade, b * bottomShade, tile, u0, v0);
        int bb = mb.vertex(x1, y0, z1, nx, 0, nz, r * bottomShade,
                gg * bottomShade, b * bottomShade, tile, u1, v0);
        int c = mb.vertex(x1, y1, z1, nx, 0, nz, r, gg, b, tile, u1, v1);
        int d = mb.vertex(x0, y1, z0, nx, 0, nz, r, gg, b, tile, u0, v1);
        mb.quad(a, bb, c, d);
    }

    /** Adds a planar quad and computes an upward-facing normal. */
    private static void surface(MeshBuilder mb,
                                float x0, float y0, float z0,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float x3, float y3, float z3,
                                float r, float g, float b, int tile,
                                float uvScale, boolean roof) {
        float ax = x1 - x0, ay = y1 - y0, az = z1 - z0;
        float bx = x3 - x0, by = y3 - y0, bz = z3 - z0;
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-8f) return;
        nx /= len;
        ny /= len;
        nz /= len;
        if (ny < 0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        float u0 = x0 * uvScale, u1 = x1 * uvScale;
        float v0 = z0 * uvScale, v1 = z3 * uvScale;
        int a = mb.vertex(x0, y0, z0, nx, ny, nz, r, g, b, tile, u0, v0);
        int bb = mb.vertex(x1, y1, z1, nx, ny, nz, r, g, b, tile, u1, v0);
        int c = mb.vertex(x2, y2, z2, nx, ny, nz, r, g, b, tile, u1, v1);
        int d = mb.vertex(x3, y3, z3, nx, ny, nz, r, g, b, tile, u0, v1);
        mb.quad(a, bb, c, d);
    }

    private static void flatBox(MeshBuilder mb, float x, float y, float z,
                                float hx, float hy, float hz,
                                float r, float g, float b, int tile) {
        mb.box(x, y + hy, z, hx, hy, hz, r, g, b, tile, 0.45f);
    }

    private ChunkBuilder() {
    }
}

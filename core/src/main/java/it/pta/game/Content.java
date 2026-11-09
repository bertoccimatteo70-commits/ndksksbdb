package it.pta.game;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import it.pta.world.Geo;
import it.pta.world.MapData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named places in the world. Coordinates come from the real OpenStreetMap landmark
 * extraction (landmarks.json) where available, otherwise from surveyed town centres.
 * Businesses, houses and shop names are fictional and original to PTA.
 */
public final class Content {
    public enum Kind {GARAGE, HOME, SHOP, DEALER, TUNING, FUEL, POLICE, HOSPITAL, BUSINESS, MISSION, LANDMARK}

    public static final class Place {
        public final String id, label;
        public final Kind kind;
        public float x, z;
        public final int price;
        public final int income;
        public final boolean garage;

        Place(String id, String label, Kind kind, float x, float z, int price, int income, boolean garage) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.x = x;
            this.z = z;
            this.price = price;
            this.income = income;
            this.garage = garage;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final Map<String, Place> PLACES = new LinkedHashMap<>();
    private static final Map<String, float[]> LANDMARKS = new LinkedHashMap<>();
    private static final Map<String, float[]> TOWNS = new LinkedHashMap<>();
    public static boolean initialized;

    private static final double[][] TOWN_FALLBACK = {
            {43.93330, 10.91670}, // Pistoia
            {43.90260, 11.00550}, // Agliana
            {43.93530, 11.01570}, // Montale
            {43.85030, 10.97860}, // Quarrata
            {43.87900, 10.99200}, // Casalguidi
            {43.90640, 10.83260}, // Serravalle Pistoiese
            {43.88330, 10.76670}, // Montecatini Terme
            {43.90000, 10.68330}, // Pescia
    };
    private static final String[] TOWN_NAMES = {
            "Pistoia", "Agliana", "Montale", "Quarrata", "Casalguidi",
            "Serravalle Pistoiese", "Montecatini Terme", "Pescia"
    };

    public static void init(MapData map, String landmarksJson) {
        PLACES.clear();
        LANDMARKS.clear();
        TOWNS.clear();
        if (landmarksJson != null) {
            try {
                JsonValue root = new JsonReader().parse(landmarksJson);
                JsonValue towns = root.get("towns");
                if (towns != null) {
                    for (JsonValue t = towns.child; t != null; t = t.next) {
                        String n = t.getString("name", "");
                        if (n.isEmpty()) {
                            continue;
                        }
                        TOWNS.put(n.toLowerCase(), new float[]{t.getFloat("x", 0), t.getFloat("z", 0)});
                    }
                }
                JsonValue lm = root.get("landmarks");
                if (lm != null) {
                    for (JsonValue t = lm.child; t != null; t = t.next) {
                        String n = t.getString("name", "");
                        if (n.isEmpty()) {
                            continue;
                        }
                        String key = n.toLowerCase();
                        if (!LANDMARKS.containsKey(key)) {
                            LANDMARKS.put(key, new float[]{t.getFloat("x", 0), t.getFloat("z", 0), t.getFloat("h", 10)});
                        }
                    }
                }
            } catch (Exception ignored) {
                // fall back to surveyed coordinates
            }
        }

        for (int i = 0; i < TOWN_NAMES.length; i++) {
            float[] p = town(TOWN_NAMES[i], TOWN_FALLBACK[i][0], TOWN_FALLBACK[i][1]);
            TOWNS.putIfAbsent(TOWN_NAMES[i].toLowerCase(), p);
        }

        // Core story locations
        place("duomo", "Piazza del Duomo", Kind.LANDMARK,
                landmarkX("duomo", 43.93340, 10.91720), landmarkZ("duomo", 43.93340, 10.91720), 0, 0, false);
        place("battistero", "Battistero di San Giovanni", Kind.LANDMARK,
                landmarkX("battistero", 43.93360, 10.91670), landmarkZ("battistero", 43.93360, 10.91670),
                0, 0, false);
        place("piazza_sala", "Piazza della Sala", Kind.LANDMARK,
                Geo.x(10.91555), Geo.z(43.93275), 0, 0, false);
        place("fortezza", "Fortezza Santa Barbara", Kind.LANDMARK,
                landmarkX("fortezza", 43.93610, 10.90530), landmarkZ("fortezza", 43.93610, 10.90530),
                0, 0, false);
        place("stazione", "Stazione di Pistoia", Kind.LANDMARK,
                landmarkX("stazione", 43.92680, 10.91450), landmarkZ("stazione", 43.92680, 10.91450),
                0, 0, false);
        place("ospedale", "Ospedale San Jacopo", Kind.HOSPITAL,
                landmarkX("ospedale", 43.94180, 10.93890), landmarkZ("ospedale", 43.94180, 10.93890),
                0, 0, false);
        place("questura", "Questura di Pistoia", Kind.POLICE,
                Geo.x(10.92300), Geo.z(43.93060), 0, 0, false);

        // Safehouses distributed across the province.
        place("garage", "Officina Bellandi", Kind.GARAGE,
                Geo.x(10.91360), Geo.z(43.93430), 0, 0, true);
        place("casa_pistoia", "Casa in centro a Pistoia", Kind.HOME,
                Geo.x(10.91220), Geo.z(43.93480), 165000, 0, true);
        place("casa_casalguidi", "Casa a Casalguidi", Kind.HOME,
                townX("Casalguidi") + 140, townZ("Casalguidi") + 60, 98000, 0, false);
        place("casa_agliana", "Attico ad Agliana", Kind.HOME,
                townX("Agliana") - 180, townZ("Agliana") + 90, 112000, 0, true);
        place("casa_quarrata", "Casa a Quarrata", Kind.HOME,
                townX("Quarrata") + 220, townZ("Quarrata") - 130, 104000, 0, false);
        place("casa_serravalle", "Villa a Serravalle", Kind.HOME,
                townX("Serravalle Pistoiese") - 120, townZ("Serravalle Pistoiese") + 160,
                145000, 0, true);
        place("casa_elisa", "Casa di Elisa", Kind.HOME,
                townX("Casalguidi") - 90, townZ("Casalguidi") + 40, 0, 0, false);

        // Businesses and shops (fictional brands).
        place("concessionaria", "Concessionaria Bureauto", Kind.DEALER,
                Geo.x(10.92950), Geo.z(43.92900), 0, 0, true);
        place("tuning", "Officina Tuning Valdibure", Kind.TUNING,
                Geo.x(10.90880), Geo.z(43.92840), 0, 0, true);
        place("abbigliamento", "Sartoria Pistoiese", Kind.SHOP,
                Geo.x(10.91500), Geo.z(43.93300), 0, 0, false);
        place("supermercato", "Mercato del Bure", Kind.SHOP,
                townX("Agliana") + 60, townZ("Agliana") + 120, 0, 0, false);
        place("bar_bure", "Bar del Bure", Kind.BUSINESS,
                Geo.x(10.91610), Geo.z(43.93390), 85000, 180, false);
        place("officina_business", "Officina Ombrone", Kind.BUSINESS,
                townX("Agliana") - 250, townZ("Agliana") + 200, 120000, 260, false);

        // Mission-only locations.
        place("capannone", "Capannone Valdibure", Kind.MISSION,
                Geo.x(10.94800), Geo.z(43.92300), 0, 0, false);
        place("cantiere", "Cantiere di via Fiorentina", Kind.MISSION,
                Geo.x(10.94200), Geo.z(43.92700), 0, 0, false);
        place("mercato", "Mercato di Piazza della Sala", Kind.MISSION,
                Geo.x(10.91550), Geo.z(43.93270), 0, 0, false);
        place("biblioteca", "Biblioteca Forteguerriana", Kind.MISSION,
                landmarkX("forteguerriana", 43.93380, 10.91790),
                landmarkZ("forteguerriana", 43.93380, 10.91790), 0, 0, false);
        place("serravalle_castello", "Castello di Serravalle", Kind.MISSION,
                landmarkX("serravalle", 43.90640, 10.83260),
                landmarkZ("serravalle", 43.90640, 10.83260), 0, 0, false);
        place("valdibure_hq", "Sede Valdibure", Kind.MISSION,
                Geo.x(10.95300), Geo.z(43.93000), 0, 0, false);
        place("eliporto", "Eliporto di Pistoia", Kind.MISSION,
                Geo.x(10.93800), Geo.z(43.94300), 0, 0, false);

        // Fuel stations taken from the real OSM POIs, renamed with invented brands.
        int n = 0;
        String[] brands = {"Distributore Bure", "Stazione Ombrone", "Benzinai Pistoiesi", "Area Servizio Nievole"};
        for (MapData.Poi p : map.pois) {
            if (p.cat == 0 && Math.abs(p.x) < 12000 && Math.abs(p.z) < 9000) {
                place("fuel_" + n, brands[n % brands.length], Kind.FUEL, p.x, p.z, 0, 0, false);
                n++;
                if (n >= 12) {
                    break;
                }
            }
        }
        initialized = true;
    }

    private static void place(String id, String label, Kind kind, float x, float z,
                              int price, int income, boolean garage) {
        PLACES.put(id, new Place(id, label, kind, x, z, price, income, garage));
    }

    private static float[] landmark(String needle, double fallbackLat, double fallbackLon) {
        String n = needle.toLowerCase();
        for (Map.Entry<String, float[]> e : LANDMARKS.entrySet()) {
            if (e.getKey().contains(n)) {
                return e.getValue();
            }
        }
        return new float[]{Geo.x(fallbackLon), Geo.z(fallbackLat)};
    }

    private static float landmarkX(String needle, double lat, double lon) {
        return landmark(needle, lat, lon)[0];
    }

    private static float landmarkZ(String needle, double lat, double lon) {
        return landmark(needle, lat, lon)[1];
    }

    private static float[] town(String name, double lat, double lon) {
        float[] p = TOWNS.get(name.toLowerCase());
        if (p != null) {
            return p;
        }
        return new float[]{Geo.x(lon), Geo.z(lat)};
    }

    private static float townX(String name) {
        float[] p = TOWNS.get(name.toLowerCase());
        return p != null ? p[0] : Geo.x(10.9167);
    }

    private static float townZ(String name) {
        float[] p = TOWNS.get(name.toLowerCase());
        return p != null ? p[1] : Geo.z(43.9333);
    }

    public static Place get(String id) {
        return PLACES.get(id);
    }

    public static float px(String id) {
        Place p = PLACES.get(id);
        return p == null ? 0 : p.x;
    }

    public static float pz(String id) {
        Place p = PLACES.get(id);
        return p == null ? 0 : p.z;
    }

    public static Collection<Place> all() {
        return PLACES.values();
    }

    public static List<Place> ofKind(Kind kind) {
        List<Place> out = new ArrayList<>();
        for (Place p : PLACES.values()) {
            if (p.kind == kind) {
                out.add(p);
            }
        }
        return out;
    }

    public static List<Place> landmarks() {
        return ofKind(Kind.LANDMARK);
    }

    public static Map<String, float[]> rawLandmarks() {
        return LANDMARKS;
    }

    private Content() {
    }
}

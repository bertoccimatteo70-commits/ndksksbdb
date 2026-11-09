package it.pta.entity;

/** Vehicle catalogue: dimensions, handling and economy values per class. */
public enum VehicleType {
    // label, kind, len, wid, height, topSpeed m/s, accel, brake, steerDeg/s, fuelCap, price, seats, wheelR, wheelbase
    HATCHBACK("Bure 5", Kind.CAR, 3.84f, 1.72f, 1.47f, 36f, 7.2f, 13f, 55f, 42f, 9500, 5, 0.30f, 2.45f),
    SEDAN("Ombrone 160", Kind.CAR, 4.62f, 1.82f, 1.43f, 42f, 8.1f, 13f, 52f, 55f, 16800, 5, 0.32f, 2.70f),
    SPORT("Valdibure GT", Kind.SPORT, 4.40f, 1.92f, 1.19f, 58f, 12.5f, 16f, 48f, 60f, 62000, 2, 0.33f, 2.55f),
    SUV("Nievole 4x4", Kind.CAR, 4.78f, 1.95f, 1.78f, 40f, 7.4f, 14f, 50f, 68f, 34000, 5, 0.37f, 2.80f),
    VAN("Furgone Bure", Kind.VAN, 5.25f, 2.02f, 2.22f, 32f, 5.8f, 11f, 44f, 70f, 24500, 3, 0.35f, 3.10f),
    TRUCK("Camion Ombrone", Kind.TRUCK, 7.20f, 2.45f, 3.05f, 26f, 4.4f, 9f, 36f, 160f, 58000, 2, 0.45f, 4.20f),
    BUS("Autobus Pistoia", Kind.BUS, 10.8f, 2.50f, 3.10f, 24f, 3.6f, 8f, 32f, 200f, 90000, 1, 0.48f, 5.60f),
    MOTORCYCLE("Moto Bure 650", Kind.BIKE, 2.10f, 0.78f, 1.18f, 46f, 9.5f, 12f, 62f, 18f, 6500, 2, 0.31f, 1.45f),
    SCOOTER("Vespa Pistoiese", Kind.SCOOTER, 1.82f, 0.70f, 1.12f, 24f, 4.6f, 10f, 64f, 8f, 2800, 2, 0.24f, 1.25f),
    BICYCLE("Bici del Bure", Kind.BICYCLE, 1.72f, 0.55f, 1.05f, 8f, 2.0f, 7f, 70f, 0f, 480, 1, 0.35f, 1.05f),
    POLICE("Volante Questura", Kind.POLICE, 4.65f, 1.84f, 1.48f, 48f, 9.6f, 15f, 54f, 62f, 0, 4, 0.33f, 2.70f),
    AMBULANCE("Ambulanza Misericordia", Kind.VAN, 5.60f, 2.10f, 2.45f, 36f, 5.9f, 12f, 44f, 80f, 0, 3, 0.36f, 3.20f),
    FIRE_TRUCK("Vigili del Fuoco", Kind.TRUCK, 7.60f, 2.50f, 3.20f, 27f, 4.2f, 9f, 34f, 170f, 0, 2, 0.46f, 4.40f),
    HELICOPTER("Elicottero Valdibure", Kind.HELI, 3.40f, 1.60f, 2.60f, 52f, 7.0f, 12f, 40f, 90f, 450000, 3, 0.35f, 2.10f),
    PLANE("Ultraleggero Bure", Kind.PLANE, 5.20f, 1.30f, 1.60f, 62f, 6.0f, 10f, 30f, 110f, 320000, 2, 0.30f, 3.10f),
    BOAT("Motoscafo Ombrone", Kind.BOAT, 4.60f, 1.80f, 1.10f, 30f, 6.0f, 10f, 45f, 95f, 38000, 4, 0.20f, 2.60f);

    public enum Kind {CAR, SPORT, VAN, TRUCK, BUS, BIKE, SCOOTER, BICYCLE, POLICE, HELI, PLANE, BOAT}

    public final String label;
    public final Kind kind;
    public final float length, width, height;
    public final float topSpeed, accel, brake, steerRate, fuelCapacity;
    public final int price, seats;
    public final float wheelRadius, wheelBase;

    VehicleType(String label, Kind kind, float len, float wid, float h, float top,
                float accel, float brake, float steer, float fuel, int price, int seats,
                float wheelR, float base) {
        this.label = label;
        this.kind = kind;
        this.length = len;
        this.width = wid;
        this.height = h;
        this.topSpeed = top;
        this.accel = accel;
        this.brake = brake;
        this.steerRate = steer;
        this.fuelCapacity = fuel;
        this.price = price;
        this.seats = seats;
        this.wheelRadius = wheelR;
        this.wheelBase = base;
    }

    public boolean twoWheeler() {
        return kind == Kind.BIKE || kind == Kind.SCOOTER || kind == Kind.BICYCLE;
    }

    public boolean buyable() {
        return price > 0;
    }

    public String categoryName() {
        switch (kind) {
            case CAR: return "Automobile";
            case SPORT: return "Auto sportiva";
            case VAN: return "Furgone";
            case TRUCK: return "Camion";
            case BUS: return "Autobus";
            case BIKE: return "Moto";
            case SCOOTER: return "Scooter";
            case BICYCLE: return "Bicicletta";
            case POLICE: return "Polizia";
            case HELI: return "Elicottero";
            case PLANE: return "Aereo";
            case BOAT: return "Barca";
            default: return "Veicolo";
        }
    }
}

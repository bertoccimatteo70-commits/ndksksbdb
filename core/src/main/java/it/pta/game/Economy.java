package it.pta.game;

import it.pta.entity.VehicleType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Player finances and assets. No in-app purchases: everything is earned in game.
 */
public final class Economy {
    public static final class Entry {
        public final String reason;
        public final int amount;
        public final float playTime;

        public Entry(String reason, int amount, float playTime) {
            this.reason = reason;
            this.amount = amount;
            this.playTime = playTime;
        }
    }

    public static final class OwnedVehicle {
        public String typeName;
        public String plate;
        public int paintIndex;
        public int rimIndex;
        public String garageId;
        public float condition = 100f;

        public OwnedVehicle() {
        }

        public OwnedVehicle(VehicleType type, String plate, int paint, String garageId) {
            this.typeName = type.name();
            this.plate = plate;
            this.paintIndex = paint;
            this.garageId = garageId;
        }

        public VehicleType type() {
            try {
                return VehicleType.valueOf(typeName);
            } catch (Exception e) {
                return VehicleType.SEDAN;
            }
        }
    }

    public int cash = 2500;
    public int bank = 0;
    public int totalEarned;
    public int totalSpent;
    public final List<Entry> ledger = new ArrayList<>();
    public final List<OwnedVehicle> vehicles = new ArrayList<>();
    public final List<String> properties = new ArrayList<>();
    public final Map<String, Integer> businessIncome = new LinkedHashMap<>();

    public boolean canAfford(int amount) {
        return cash >= amount;
    }

    public boolean spend(int amount, String reason, float playTime) {
        if (amount <= 0) {
            return true;
        }
        if (cash < amount) {
            return false;
        }
        cash -= amount;
        totalSpent += amount;
        ledger.add(new Entry(reason, -amount, playTime));
        trimLedger();
        return true;
    }

    public void earn(int amount, String reason, float playTime) {
        if (amount <= 0) {
            return;
        }
        cash += amount;
        totalEarned += amount;
        ledger.add(new Entry(reason, amount, playTime));
        trimLedger();
    }

    public boolean buyProperty(Content.Place place, float playTime) {
        if (place == null || properties.contains(place.id)) {
            return false;
        }
        if (!spend(place.price, "Acquisto: " + place.label, playTime)) {
            return false;
        }
        properties.add(place.id);
        if (place.income > 0) {
            businessIncome.put(place.id, place.income);
        }
        return true;
    }

    public boolean owns(String placeId) {
        return properties.contains(placeId);
    }

    public boolean buyVehicle(VehicleType type, int paintIndex, String garageId, float playTime) {
        if (!type.buyable()) {
            return false;
        }
        if (!spend(type.price, "Acquisto veicolo: " + type.label, playTime)) {
            return false;
        }
        OwnedVehicle v = new OwnedVehicle(type, it.pta.entity.Vehicle.plateFor(cash + vehicles.size() * 37),
                paintIndex, garageId);
        vehicles.add(v);
        return true;
    }

    public void deposit(int amount) {
        int move = Math.min(amount, cash);
        cash -= move;
        bank += move;
    }

    public void withdraw(int amount) {
        int move = Math.min(amount, bank);
        bank -= move;
        cash += move;
    }

    public int dailyIncome() {
        int sum = 0;
        for (int v : businessIncome.values()) {
            sum += v;
        }
        return sum;
    }

    public void collectDailyIncome(float playTime) {
        int income = dailyIncome();
        if (income > 0) {
            earn(income, "Incassi attività", playTime);
        }
    }

    private void trimLedger() {
        while (ledger.size() > 60) {
            ledger.remove(0);
        }
    }

    public String money() {
        return "€ " + format(cash);
    }

    public static String format(int amount) {
        StringBuilder sb = new StringBuilder();
        String s = Integer.toString(Math.abs(amount));
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) {
                sb.append('.');
            }
            sb.append(s.charAt(i));
        }
        return (amount < 0 ? "-" : "") + sb;
    }
}

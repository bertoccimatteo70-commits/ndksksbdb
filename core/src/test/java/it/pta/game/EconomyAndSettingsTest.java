package it.pta.game;

import com.badlogic.gdx.files.FileHandle;
import it.pta.entity.VehicleType;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EconomyAndSettingsTest {
    @Test
    public void economyEarnsSpendsAndBuysAssets() {
        Economy e = new Economy();
        assertEquals(2500, e.cash);
        assertTrue(e.spend(500, "test", 0));
        assertEquals(2000, e.cash);
        assertEquals(500, e.totalSpent);
        e.earn(900, "missione", 10);
        assertEquals(2900, e.cash);
        assertEquals(900, e.totalEarned);
        assertFalse(e.spend(999999, "troppo", 1));
        assertEquals(2900, e.cash);
        e.earn(20000, "compenso", 2);
        assertTrue(e.buyVehicle(VehicleType.HATCHBACK, 2, "garage", 1));
        assertEquals(1, e.vehicles.size());
        assertEquals(VehicleType.HATCHBACK, e.vehicles.get(0).type());
        assertFalse(e.buyVehicle(VehicleType.POLICE, 0, "garage", 1));
    }

    @Test
    public void propertyPurchaseAndIncome() {
        Economy e = new Economy();
        Content.Place cheap = new Content.Place("test_home", "Casa test", Content.Kind.HOME,
                0, 0, 1000, 0, false);
        Content.Place business = new Content.Place("test_biz", "Attività test", Content.Kind.BUSINESS,
                0, 0, 500, 120, false);
        assertTrue(e.buyProperty(cheap, 0));
        assertTrue(e.owns("test_home"));
        assertFalse(e.buyProperty(cheap, 0));
        assertTrue(e.buyProperty(business, 0));
        assertEquals(120, e.dailyIncome());
        int before = e.cash;
        e.collectDailyIncome(12);
        assertEquals(before + 120, e.cash);
    }

    @Test
    public void presetsReallyChangeEngineParameters() {
        Settings s = new Settings();
        s.applyPreset(Settings.Preset.BASE);
        assertEquals(480, s.renderHeight);
        assertEquals(30, s.fpsCap);
        assertEquals(620f, s.viewDistance, 0.01f);
        assertEquals(0, s.shadowQuality);
        assertFalse(s.fxaa);

        s.applyPreset(Settings.Preset.ALTA);
        assertEquals(1080, s.renderHeight);
        assertEquals(1300f, s.viewDistance, 0.01f);
        assertEquals(2, s.shadowQuality);
        assertTrue(s.fxaa);

        s.applyPreset(Settings.Preset.ULTRA);
        assertEquals(60, s.fpsCap);
        assertTrue(s.qualitySummary().contains("1080p"));
    }

    @Test
    public void saveManagerRoundTrip() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "pta-test-saves-" + System.nanoTime());
        SaveManager saves = new SaveManager(new FileHandle(dir));
        assertNull(saves.load(1));
        SaveManager.SaveData d = new SaveManager.SaveData();
        d.cash = 4321;
        d.bank = 100;
        d.character = 1;
        d.x = 123.5f;
        d.z = -44.25f;
        d.completedMissions = new String[]{"m01", "m02"};
        d.properties = new String[]{"casa_pistoia"};
        d.vehicles = new Economy.OwnedVehicle[]{new Economy.OwnedVehicle(
                VehicleType.SPORT, "AA 123 BB", 3, "garage")};
        assertTrue(saves.save(1, d));
        SaveManager.SaveData back = saves.load(1);
        assertNotNull(back);
        assertEquals(4321, back.cash);
        assertEquals(1, back.character);
        assertEquals(123.5f, back.x, 0.001f);
        assertEquals(2, back.completedMissions.length);
        assertEquals(VehicleType.SPORT, back.vehicles[0].type());
        assertEquals(1, saves.newestSlot());
        assertTrue(saves.delete(1));
        assertNull(saves.load(1));
    }
}

package it.pta.game;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PerformanceGovernorTest {
    @Test
    public void sustainedLowFpsStepsDownAndStopsAtTheFloor() {
        PerformanceGovernor g = new PerformanceGovernor();
        // 5 s under 21.6 fps at a 30 fps target: one step.
        assertEquals(1, g.update(5f, 9f, 30));
        assertEquals(1, g.step());
        assertEquals(1, g.update(5f, 9f, 30));
        assertEquals(1, g.update(5f, 9f, 30));
        assertEquals(PerformanceGovernor.MAX_STEP, g.step());
        // Already at the floor: no further change.
        assertEquals(0, g.update(60f, 4f, 30));
        assertEquals(3, g.step());
    }

    @Test
    public void briefDipDoesNotChangeQuality() {
        PerformanceGovernor g = new PerformanceGovernor();
        assertEquals(0, g.update(4.9f, 5f, 30));
        assertEquals(0, g.step());
        // A later healthy window resets the low timer instead of accumulating it.
        assertEquals(0, g.update(5f, 30f, 30));
        assertEquals(0, g.update(4.9f, 5f, 30));
        assertEquals(0, g.step());
    }

    @Test
    public void deadZoneKeepsCurrentQuality() {
        PerformanceGovernor g = new PerformanceGovernor();
        for (int i = 0; i < 20; i++) {
            assertEquals(0, g.update(5f, 25f, 30));
        }
        assertEquals(0, g.step());
    }

    @Test
    public void sustainedHeadroomRecoversOneStepAtATime() {
        PerformanceGovernor g = new PerformanceGovernor();
        g.update(5f, 8f, 30);
        g.update(5f, 8f, 30);
        assertEquals(2, g.step());
        assertEquals(0, g.update(29f, 30f, 30));
        assertEquals(-1, g.update(1f, 30f, 30));
        assertEquals(1, g.step());
        // The recovery timer restarts; a single good second is not enough again.
        assertEquals(0, g.update(1f, 30f, 30));
        assertEquals(1, g.step());
    }

    @Test
    public void freeFpsTargetUsesSixtyAsReference() {
        PerformanceGovernor g = new PerformanceGovernor();
        assertEquals(0, g.update(5f, 45f, 0));
        assertEquals(1, g.update(5f, 30f, 0));
        assertEquals(1, g.step());
    }
}

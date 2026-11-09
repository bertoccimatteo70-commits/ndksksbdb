package it.pta.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;

/**
 * Player settings. The four graphics presets change real engine parameters
 * (internal resolution, view distance, shadow quality, traffic density, AA, FPS cap).
 */
public final class Settings {
    public enum Preset {
        BASE("Base", 480, 30, 620f, 0.55f, 0, false, 0.55f),
        MEDIA("Media", 720, 30, 900f, 0.80f, 1, true, 0.75f),
        ALTA("Alta", 1080, 30, 1300f, 1.00f, 2, true, 0.90f),
        ULTRA("Ultra", 1080, 60, 1700f, 1.00f, 2, true, 1.00f);

        public final String label;
        public final int renderHeight;
        public final int fpsCap;
        public final float viewDistance;
        public final float trafficDensity;
        public final int shadowQuality;
        public final boolean fxaa;
        public final float effects;

        Preset(String label, int h, int fps, float view, float traffic, int shadows,
               boolean fxaa, float effects) {
            this.label = label;
            this.renderHeight = h;
            this.fpsCap = fps;
            this.viewDistance = view;
            this.trafficDensity = traffic;
            this.shadowQuality = shadows;
            this.fxaa = fxaa;
            this.effects = effects;
        }
    }

    public Preset preset = Preset.MEDIA;
    public int renderHeight = 720;
    public int fpsCap = 30;
    public float viewDistance = 900f;
    public float trafficDensity = 0.8f;
    public int shadowQuality = 1;
    public boolean fxaa = true;
    public float effects = 0.75f;
    public int textureQuality = 2; // 0 low, 1 medium, 2 high
    public String atmosphere = "Media";
    public boolean postProcessing = true;

    public float musicVolume = 0.65f;
    public float sfxVolume = 0.85f;
    public float ambientVolume = 0.55f;
    public float dialogueVolume = 0.8f;

    public float buttonScale = 1f;
    public float buttonAlpha = 0.82f;
    public boolean leftHanded = false;
    public float cameraSensitivity = 1f;
    public boolean invertY = false;
    public boolean firstPersonDefault = false;
    public boolean showFps = false;

    private Preferences prefs;

    public void applyPreset(Preset p) {
        preset = p;
        renderHeight = p.renderHeight;
        fpsCap = p.fpsCap;
        viewDistance = p.viewDistance;
        trafficDensity = p.trafficDensity;
        shadowQuality = p.shadowQuality;
        fxaa = p.fxaa;
        effects = p.effects;
        postProcessing = true;
        if (p == Preset.BASE) {
            textureQuality = 1;
            atmosphere = "Bassa";
        } else if (p == Preset.MEDIA) {
            textureQuality = 2;
            atmosphere = "Media";
        } else {
            textureQuality = 2;
            atmosphere = "Alta";
        }
    }

    public boolean shadowsEnabled() {
        return shadowQuality > 0;
    }

    public static Settings load() {
        Settings s = new Settings();
        try {
            s.prefs = Gdx.app.getPreferences("pta_settings");
            String presetName = s.prefs.getString("preset", Preset.MEDIA.name());
            try {
                s.preset = Preset.valueOf(presetName);
            } catch (Exception e) {
                s.preset = Preset.MEDIA;
            }
            s.renderHeight = s.prefs.getInteger("renderHeight", s.preset.renderHeight);
            s.fpsCap = s.prefs.getInteger("fpsCap", s.preset.fpsCap);
            s.viewDistance = s.prefs.getFloat("viewDistance", s.preset.viewDistance);
            s.trafficDensity = s.prefs.getFloat("traffic", s.preset.trafficDensity);
            s.shadowQuality = s.prefs.getInteger("shadows", s.preset.shadowQuality);
            s.fxaa = s.prefs.getBoolean("fxaa", s.preset.fxaa);
            s.effects = s.prefs.getFloat("effects", s.preset.effects);
            s.textureQuality = s.prefs.getInteger("textures", 2);
            s.atmosphere = s.prefs.getString("atmosphere", "Media");
            s.postProcessing = s.prefs.getBoolean("post", true);
            s.musicVolume = s.prefs.getFloat("music", 0.65f);
            s.sfxVolume = s.prefs.getFloat("sfx", 0.85f);
            s.ambientVolume = s.prefs.getFloat("ambient", 0.55f);
            s.dialogueVolume = s.prefs.getFloat("dialogue", 0.8f);
            s.buttonScale = s.prefs.getFloat("buttonScale", 1f);
            s.buttonAlpha = s.prefs.getFloat("buttonAlpha", 0.82f);
            s.leftHanded = s.prefs.getBoolean("leftHanded", false);
            s.cameraSensitivity = s.prefs.getFloat("sensitivity", 1f);
            s.invertY = s.prefs.getBoolean("invertY", false);
            s.firstPersonDefault = s.prefs.getBoolean("firstPerson", false);
            s.showFps = s.prefs.getBoolean("showFps", false);
        } catch (Exception ignored) {
            s.applyPreset(Preset.MEDIA);
        }
        return s;
    }

    public void save() {
        if (prefs == null) {
            try {
                prefs = Gdx.app.getPreferences("pta_settings");
            } catch (Exception e) {
                return;
            }
        }
        prefs.putString("preset", preset.name());
        prefs.putInteger("renderHeight", renderHeight);
        prefs.putInteger("fpsCap", fpsCap);
        prefs.putFloat("viewDistance", viewDistance);
        prefs.putFloat("traffic", trafficDensity);
        prefs.putInteger("shadows", shadowQuality);
        prefs.putBoolean("fxaa", fxaa);
        prefs.putFloat("effects", effects);
        prefs.putInteger("textures", textureQuality);
        prefs.putString("atmosphere", atmosphere);
        prefs.putBoolean("post", postProcessing);
        prefs.putFloat("music", musicVolume);
        prefs.putFloat("sfx", sfxVolume);
        prefs.putFloat("ambient", ambientVolume);
        prefs.putFloat("dialogue", dialogueVolume);
        prefs.putFloat("buttonScale", buttonScale);
        prefs.putFloat("buttonAlpha", buttonAlpha);
        prefs.putBoolean("leftHanded", leftHanded);
        prefs.putFloat("sensitivity", cameraSensitivity);
        prefs.putBoolean("invertY", invertY);
        prefs.putBoolean("firstPerson", firstPersonDefault);
        prefs.putBoolean("showFps", showFps);
        prefs.flush();
    }

    public String qualitySummary() {
        return preset.label + " · " + renderHeight + "p · " + (fpsCap == 0 ? "FPS libero" : fpsCap + " FPS");
    }
}

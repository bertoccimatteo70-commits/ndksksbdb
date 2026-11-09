package it.pta.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.AudioDevice;
import com.badlogic.gdx.utils.Disposable;

import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Fully procedural audio: original music loops, engine and siren synthesis, ambient
 * beds and interface/mission effects. Nothing is sampled from copyrighted material.
 */
public final class AudioEngine implements Disposable {
    private static final int RATE = 22050;
    private static final int BLOCK = 1024;

    private AudioDevice device;
    private Thread mixThread;
    private volatile boolean running;
    private volatile boolean enabled;

    private final float[][] tracks = new float[5][];
    public static final int MENU = 0, EXPLORE = 1, CHASE = 2, MISSION = 3, NIGHT = 4;
    private volatile int track = EXPLORE;
    private long musicPos;

    private volatile float musicGain = 0.55f;
    private volatile float sfxGain = 0.85f;
    private volatile float ambientGain = 0.5f;
    private volatile float dialogueGain = 0.8f;

    private volatile float engineLoad;
    private volatile float engineRpm;
    private volatile boolean engineOn;
    private volatile boolean siren;
    private volatile float rainAmount;
    private volatile boolean indoors;

    private final ConcurrentLinkedQueue<float[]> oneShots = new ConcurrentLinkedQueue<>();
    private final Random rnd = new Random(9911);
    private float ambientFilter;
    private float sirenPhase;
    private float enginePhase;

    public AudioEngine() {
        try {
            device = Gdx.audio.newAudioDevice(RATE, true);
            enabled = true;
        } catch (Exception e) {
            Gdx.app.error("PTA", "audio device unavailable", e);
            enabled = false;
        }
        for (int i = 0; i < tracks.length; i++) {
            tracks[i] = renderTrack(i);
        }
        if (enabled) {
            running = true;
            mixThread = new Thread(this::mixLoop, "pta-audio");
            mixThread.setDaemon(true);
            mixThread.start();
        }
    }

    // ------------------------------------------------------------------ music

    private float[] renderTrack(int kind) {
        float bpm;
        int[][] chords;
        boolean drums;
        switch (kind) {
            case MENU:
                bpm = 68;
                chords = new int[][]{{57, 60, 64}, {53, 57, 60}, {55, 59, 62}, {52, 55, 59}};
                drums = false;
                break;
            case CHASE:
                bpm = 142;
                chords = new int[][]{{50, 53, 57}, {46, 50, 53}, {48, 52, 55}, {45, 48, 52}};
                drums = true;
                break;
            case MISSION:
                bpm = 112;
                chords = new int[][]{{55, 59, 62}, {52, 55, 59}, {57, 60, 64}, {53, 57, 60}};
                drums = true;
                break;
            case NIGHT:
                bpm = 60;
                chords = new int[][]{{45, 48, 52}, {41, 45, 48}, {43, 47, 50}, {40, 43, 47}};
                drums = false;
                break;
            default:
                bpm = 92;
                chords = new int[][]{{48, 52, 55}, {45, 48, 52}, {53, 57, 60}, {50, 53, 57}};
                drums = true;
                break;
        }
        int beat = (int) (RATE * 60f / bpm);
        int bars = 4;
        int total = beat * 4 * bars;
        float[] buf = new float[total];
        for (int bar = 0; bar < bars; bar++) {
            int[] chord = chords[bar % chords.length];
            int barStart = bar * beat * 4;
            // warm pad
            for (int note : chord) {
                addOsc(buf, barStart, beat * 4, freq(note - 12), 0.10f, 0, beat / 3, beat);
                addOsc(buf, barStart, beat * 4, freq(note), 0.055f, 0, beat / 3, beat);
            }
            // bass
            addOsc(buf, barStart, beat, freq(chord[0] - 24), 0.22f, 1, 40, 120);
            addOsc(buf, barStart + beat * 2, beat, freq(chord[0] - 24), 0.18f, 1, 40, 160);
            // plucked arpeggio
            int[][] pattern = {{0, 1, 2, 1}, {2, 1, 0, 2}, {1, 2, 1, 0}, {0, 2, 1, 2}};
            int[] pat = pattern[bar % pattern.length];
            for (int step = 0; step < 8; step++) {
                int start = barStart + step * beat / 2;
                int note = chord[pat[step % pat.length]] + 12;
                addOsc(buf, start, beat / 2, freq(note), 0.06f + (kind == CHASE ? 0.02f : 0f), 2, 8, beat / 2);
                if (drums && kind == CHASE && step % 2 == 1) {
                    addNoise(buf, start, 80, 0.05f, 0.7f);
                }
            }
            if (drums) {
                for (int b = 0; b < 4; b++) {
                    addKick(buf, barStart + b * beat, (b % 2 == 0) ? 0.32f : 0.16f);
                }
                addNoise(buf, barStart + beat, 120, 0.09f, 0.6f);
                addNoise(buf, barStart + beat * 3, 120, 0.09f, 0.6f);
            }
            if (kind == MENU) {
                int start = barStart + beat * 3;
                addOsc(buf, start, beat, freq(chord[1] + 12), 0.07f, 2, 60, beat);
            }
        }
        // soft saturation and normalisation
        float peak = 0;
        for (int i = 0; i < total; i++) {
            float v = (float) Math.tanh(buf[i] * 1.4f);
            buf[i] = v;
            peak = Math.max(peak, Math.abs(v));
        }
        if (peak > 0.9f) {
            for (int i = 0; i < total; i++) {
                buf[i] *= 0.9f / peak;
            }
        }
        return buf;
    }

    private static float freq(int midi) {
        return (float) (440.0 * Math.pow(2.0, (midi - 69) / 12.0));
    }

    /** wave: 0 sine, 1 saw, 2 pluck (saw with fast decay), 3 triangle */
    private static void addOsc(float[] buf, int start, int length, float f, float amp,
                               int wave, int attack, int release) {
        int n = Math.min(length, buf.length - start);
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            float env = 1f;
            if (i < attack) {
                env = i / (float) Math.max(1, attack);
            }
            int remain = n - i;
            if (remain < release) {
                env *= remain / (float) Math.max(1, release);
            }
            float phase = (t * f) % 1f;
            float v;
            switch (wave) {
                case 1:
                    v = 2f * phase - 1f;
                    break;
                case 2:
                    v = (2f * phase - 1f) * (float) Math.exp(-4.5f * t * 3f);
                    break;
                case 3:
                    v = 1f - 4f * Math.abs(phase - 0.5f);
                    break;
                default:
                    v = (float) Math.sin(phase * Math.PI * 2);
            }
            if (start + i >= 0 && start + i < buf.length) {
                buf[start + i] += v * amp * env;
            }
        }
    }

    private static void addNoise(float[] buf, int start, int length, float amp, float decay) {
        Random r = new Random(start);
        for (int i = 0; i < length && start + i < buf.length; i++) {
            float env = (float) Math.exp(-decay * i / (length * 0.35f));
            buf[start + i] += (r.nextFloat() * 2 - 1) * amp * env;
        }
    }

    private static void addKick(float[] buf, int start, float amp) {
        int len = (int) (RATE * 0.20f);
        for (int i = 0; i < len && start + i < buf.length; i++) {
            float t = i / (float) RATE;
            float f = 110f * (float) Math.exp(-t * 18f) + 42f;
            float env = (float) Math.exp(-t * 12f);
            buf[start + i] += (float) Math.sin(t * f * Math.PI * 2) * amp * env;
        }
    }

    // ------------------------------------------------------------------ mixing

    private void mixLoop() {
        float[] block = new float[BLOCK];
        float[] music = tracks[EXPLORE];
        int lastTrack = -1;
        while (running) {
            int t = track;
            if (t != lastTrack) {
                music = tracks[t];
                musicPos = 0;
                lastTrack = t;
            }
            for (int i = 0; i < BLOCK; i++) {
                int pos = (int) ((musicPos + i) % music.length);
                block[i] = music[pos] * musicGain;
            }
            // ambient bed: filtered noise + occasional birds
            for (int i = 0; i < BLOCK; i++) {
                float white = (rnd.nextFloat() * 2 - 1);
                ambientFilter += (white - ambientFilter) * 0.02f;
                float amb = ambientFilter * (indoors ? 0.06f : 0.12f) * ambientGain;
                if (rainAmount > 0.05f) {
                    amb += white * 0.05f * rainAmount * ambientGain;
                }
                block[i] += amb;
            }
            if (!indoors && rnd.nextInt(BLOCK * 300) < 2) {
                enqueue(birdChirp());
            }
            // engine
            if (engineOn) {
                float f = 42f + engineRpm * 120f;
                for (int i = 0; i < BLOCK; i++) {
                    enginePhase += f / RATE;
                    if (enginePhase > 1f) {
                        enginePhase -= 1f;
                    }
                    float saw = (2f * enginePhase - 1f) * 0.5f;
                    saw += (float) Math.sin(enginePhase * Math.PI * 4) * 0.3f;
                    block[i] += saw * 0.20f * engineLoad * sfxGain;
                }
            }
            // siren
            if (siren) {
                for (int i = 0; i < BLOCK; i++) {
                    sirenPhase += (0.0006f + 0.0004f * (float) Math.sin(System.nanoTime() * 2e-9)) * 10f;
                    float v = (float) Math.sin(sirenPhase * Math.PI * 2) * 0.05f * sfxGain;
                    block[i] += v;
                }
            }
            float[] shot;
            while ((shot = oneShots.poll()) != null) {
                for (int i = 0; i < BLOCK; i++) {
                    if (i < shot.length) {
                        block[i] += shot[i];
                    }
                }
            }
            for (int i = 0; i < BLOCK; i++) {
                block[i] = Math.max(-1f, Math.min(1f, block[i]));
            }
            try {
                device.writeSamples(block, 0, BLOCK);
            } catch (Exception e) {
                running = false;
            }
            musicPos += BLOCK;
        }
    }

    private void enqueue(float[] samples) {
        if (oneShots.size() < 24) {
            oneShots.add(samples);
        }
    }

    private float[] birdChirp() {
        float[] b = new float[(int) (RATE * 0.18f)];
        float f = 2200 + rnd.nextFloat() * 900;
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) RATE;
            float env = (float) Math.sin(Math.PI * i / b.length);
            b[i] = (float) Math.sin(t * f * Math.PI * 2 + Math.sin(t * 40) * 2) * 0.05f * env;
        }
        return b;
    }

    private float[] noiseBurst(float duration, float strength) {
        int n = (int) (RATE * duration);
        float[] b = new float[n];
        for (int i = 0; i < n; i++) {
            float env = (float) Math.exp(-6f * i / n);
            b[i] = (rnd.nextFloat() * 2 - 1) * env * 0.5f * strength * sfxGain;
        }
        return b;
    }

    private float[] tone(float duration, float f0, float f1, float amp, int wave) {
        int n = (int) (RATE * duration);
        float[] b = new float[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) n;
            float f = f0 + (f1 - f0) * t;
            phase += f / RATE;
            float v = wave == 0 ? (float) Math.sin(phase * Math.PI * 2) : (float) (2 * (phase % 1) - 1);
            float env = (float) Math.min(1, t * 20f) * (float) Math.exp(-2.4f * t);
            b[i] = v * env * amp;
        }
        return b;
    }

    // ------------------------------------------------------------------- API

    public void setTrack(int t) {
        if (t >= 0 && t < tracks.length) {
            track = t;
        }
    }

    public void setVolumes(float music, float sfx, float ambient, float dialogue) {
        musicGain = music;
        sfxGain = sfx;
        ambientGain = ambient;
        dialogueGain = dialogue;
    }

    public void setEngine(boolean on, float load, float rpm) {
        engineOn = on;
        engineLoad = Math.max(0f, Math.min(1f, load));
        engineRpm = Math.max(0f, Math.min(1f, rpm));
    }

    public void setSiren(boolean on) {
        siren = on;
    }

    public void setRain(float amount) {
        rainAmount = amount;
    }

    public void setIndoors(boolean indoors) {
        this.indoors = indoors;
    }

    public void uiClick() {
        enqueue(tone(0.06f, 900, 1200, 0.16f, 0));
    }

    public void cash() {
        enqueue(tone(0.10f, 1200, 1700, 0.16f, 0));
        enqueue(tone(0.14f, 1700, 2200, 0.12f, 0));
    }

    public void pickup() {
        enqueue(tone(0.10f, 700, 1300, 0.18f, 3));
    }

    public void checkpoint() {
        enqueue(tone(0.12f, 600, 900, 0.18f, 3));
    }

    public void objective() {
        enqueue(tone(0.16f, 520, 780, 0.20f, 3));
        enqueue(tone(0.22f, 780, 1040, 0.14f, 0));
    }

    public void missionComplete() {
        enqueue(tone(0.18f, 523, 523, 0.18f, 3));
        enqueue(tone(0.34f, 659, 659, 0.16f, 3));
        enqueue(tone(0.52f, 784, 784, 0.14f, 3));
    }

    public void fail() {
        enqueue(tone(0.30f, 320, 180, 0.20f, 1));
        enqueue(tone(0.44f, 220, 130, 0.18f, 1));
    }

    public void crash(float strength) {
        if (strength > 1f) {
            enqueue(noiseBurst(0.22f, Math.min(1f, strength / 20f)));
        }
    }

    public void footstep() {
        enqueue(noiseBurst(0.06f, 0.10f));
    }

    public void punch() {
        enqueue(noiseBurst(0.05f, 0.25f));
        enqueue(tone(0.08f, 160, 90, 0.12f, 1));
    }

    public void dialogueBlip() {
        enqueue(tone(0.05f, 600 + rnd.nextInt(240), 700 + rnd.nextInt(240), 0.10f * dialogueGain, 3));
    }

    public void horn() {
        enqueue(tone(0.36f, 392, 392, 0.18f, 1));
        enqueue(tone(0.36f, 494, 494, 0.12f, 1));
    }

    public void refuel() {
        enqueue(tone(0.7f, 220, 380, 0.10f, 0));
    }

    @Override
    public void dispose() {
        running = false;
        if (mixThread != null) {
            try {
                mixThread.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (device != null) {
            device.dispose();
        }
    }
}

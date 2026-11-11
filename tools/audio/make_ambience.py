#!/usr/bin/env python3
"""Generates the ambient audio beds shipped in the APK.

Original synthesis, no third-party samples: filtered noise, low rumble and
sparse events, with the tail crossfaded into the head so the loop seam is
inaudible. Mono 22.05 kHz 16-bit WAV, streamed at runtime by libGDX music, so
the files never sit in RAM.

    python3 tools/audio/make_ambience.py
"""
import math
import os
import random
import struct
import wave

RATE = 22050
SECONDS = 40
OUT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                   "..", "..", "app", "src", "main", "assets", "audio"))


def white(n, rnd):
    return [rnd.uniform(-1.0, 1.0) for _ in range(n)]


def lowpass(samples, alpha):
    out = [0.0] * len(samples)
    acc = 0.0
    for i, s in enumerate(samples):
        acc += (s - acc) * alpha
        out[i] = acc
    return out


def loopify(samples, fade=int(RATE * 0.8)):
    """Crossfades the tail into the head: the file loops without a click."""
    n = len(samples)
    for i in range(fade):
        t = i / (fade - 1)
        samples[i] = samples[i] * t + samples[n - fade + i] * (1.0 - t)
    return samples[:n - fade]


def write(name, samples, peak=0.72):
    m = max(1e-6, max(abs(s) for s in samples))
    g = peak / m
    path = os.path.join(OUT, name)
    frames = bytearray()
    for s in samples:
        v = max(-1.0, min(1.0, s * g))
        frames += struct.pack("<h", int(v * 32767))
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(bytes(frames))
    print("%-16s %8d bytes  %5.1f s" % (name, os.path.getsize(path), len(samples) / RATE))


def rain(rnd, n):
    noise = white(n, rnd)
    hiss = lowpass(noise, 0.45)
    body = lowpass(noise, 0.06)
    out = [0.0] * n
    for i in range(n):
        # Slow storm swells over the steady hiss, plus sparse droplets.
        swell = 0.72 + 0.28 * math.sin(2 * math.pi * i / (RATE * 17.0))
        out[i] = (hiss[i] * 0.55 + body[i] * 0.9) * swell
    drops = int(SECONDS * 9)
    for _ in range(drops):
        start = rnd.randrange(0, n - int(RATE * 0.08))
        length = int(RATE * (0.02 + rnd.random() * 0.05))
        amp = rnd.uniform(0.15, 0.5)
        for k in range(length):
            env = math.exp(-k / (length * 0.25))
            out[start + k] += rnd.uniform(-amp, amp) * env
    return loopify(out)


def wind(rnd, n):
    noise = white(n, rnd)
    air = lowpass(noise, 0.02)
    body = lowpass(noise, 0.004)
    out = [0.0] * n
    for i in range(n):
        t = i / RATE
        gust = 0.55 + 0.45 * math.sin(2 * math.pi * t / 19.0) * math.sin(2 * math.pi * t / 7.3)
        whistle = 0.06 * math.sin(2 * math.pi * 190.0 * t + 3.0 * math.sin(2 * math.pi * t / 11.0))
        out[i] = air[i] * 1.5 * gust + body[i] * 1.2 + whistle * max(0.0, gust)
    return loopify(out)


def city(rnd, n):
    noise = white(n, rnd)
    rumble = lowpass(noise, 0.01)
    mid = lowpass(noise, 0.12)
    out = [0.0] * n
    for i in range(n):
        t = i / RATE
        hum = 0.10 * math.sin(2 * math.pi * 50.0 * t) + 0.05 * math.sin(2 * math.pi * 100.0 * t)
        out[i] = rumble[i] * 1.4 + mid[i] * 0.20 + hum
    # Distant voices and scooters: short band-limited bursts, never intelligible.
    for _ in range(int(SECONDS * 2.2)):
        start = rnd.randrange(0, n - int(RATE * 1.2))
        length = int(RATE * (0.35 + rnd.random() * 0.8))
        freq = rnd.uniform(120.0, 420.0)
        amp = rnd.uniform(0.04, 0.13)
        for k in range(length):
            env = math.sin(math.pi * k / length) ** 2
            out[start + k] += amp * env * math.sin(2 * math.pi * freq * k / RATE) * (
                0.5 + 0.5 * math.sin(2 * math.pi * 3.1 * k / RATE))
    return loopify(out)


def main():
    os.makedirs(OUT, exist_ok=True)
    n = int(RATE * SECONDS)
    write("rain.wav", rain(random.Random(11), n))
    write("wind.wav", wind(random.Random(22), n))
    write("city.wav", city(random.Random(33), n))
    print("total", sum(os.path.getsize(os.path.join(OUT, f))
                       for f in ("rain.wav", "wind.wav", "city.wav")), "bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

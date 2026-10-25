"""Measure the production Rubber Band pipeline on owned stereo audio, using host FFmpeg and Java 17."""
import array
import cmath
import hashlib
import io
import json
import math
import pathlib
import statistics
import subprocess
import sys
import tempfile
import wave
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
RATE, SECONDS = 48000, 6


def dominant_frequency(samples):
    # Pitch shifting can add low-level partials, so zero crossings are not a
    # reliable fundamental estimator. Use the dominant Hann-windowed FFT peak.
    size = 65536
    spectrum = [complex(samples[RATE + i] * (.5 - .5 * math.cos(2 * math.pi * i / (size - 1)))) for i in range(size)]
    j = 0
    for i in range(1, size):
        bit = size >> 1
        while j & bit:
            j ^= bit; bit >>= 1
        j ^= bit
        if i < j: spectrum[i], spectrum[j] = spectrum[j], spectrum[i]
    width = 2
    while width <= size:
        step = cmath.exp(-2j * math.pi / width)
        for start in range(0, size, width):
            factor = 1
            for k in range(start, start + width // 2):
                even, odd = spectrum[k], spectrum[k + width // 2] * factor
                spectrum[k], spectrum[k + width // 2] = even + odd, even - odd
                factor *= step
        width *= 2
    peak = max(range(2, size // 2 - 1), key=lambda i: abs(spectrum[i]))
    a, b, c = [math.log(max(abs(spectrum[i]), 1e-30)) for i in (peak - 1, peak, peak + 1)]
    offset = .5 * (a - c) / (a - 2 * b + c)
    return (peak + offset) * RATE / size


def beat_period(samples):
    window = RATE // 100
    envelope = [sum(v * v for v in samples[i:i + window]) / window for i in range(0, len(samples) - window, window)]
    peaks = []
    for beat in range(2, 10):
        center = beat * 50
        index = max(range(center - 12, center + 13), key=lambda i: envelope[i])
        peaks.append((index + .5) / 100)
    return statistics.median(b - a for a, b in zip(peaks, peaks[1:]))


with tempfile.TemporaryDirectory(prefix="tonekeep-pitch-") as temp:
    work = pathlib.Path(temp)
    source = work / "original.wav"
    frames = bytearray()
    for i in range(RATE * SECONDS):
        t = i / RATE
        distance = min(t % .5, .5 - t % .5)
        envelope = .20 + .75 * math.exp(-.5 * (distance / .025) ** 2)
        for frequency in (440, 660):
            value = round(math.sin(2 * math.pi * frequency * t) * envelope * ((1 << 23) - 1))
            frames.extend(value.to_bytes(3, "little", signed=True))
    with wave.open(str(source), "wb") as output:
        output.setnchannels(2); output.setsampwidth(3); output.setframerate(RATE); output.writeframes(frames)
    original_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    classes = work / "classes"
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(classes),
                    str(ROOT / "app/src/main/java/jp/tonekeep/app/PitchSettings.java"), str(ROOT / "scripts/PitchFixtureRunner.java")], check=True, timeout=60)
    results = []
    for key, formants, extension in [(3, True, "flac"), (-5, False, "wav"), (12, True, "flac")]:
        target = work / f"key-{key}.{extension}"
        process = subprocess.run(["java", "-cp", str(classes), "PitchFixtureRunner", str(source), str(target), str(key), str(formants).lower(), extension], capture_output=True, text=True, check=True, timeout=180)
        settings = json.loads(process.stdout)
        probe = json.loads(subprocess.run(["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(target)], capture_output=True, text=True, check=True).stdout)
        stream = probe["streams"][0]
        assert stream["channels"] == 2 and int(stream["sample_rate"]) == RATE, stream
        assert int(stream.get("bits_per_raw_sample") or stream.get("bits_per_sample")) == 24, stream
        duration = float(probe["format"]["duration"])
        assert abs(duration - SECONDS) < .08, duration
        raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(target), "-f", "f32le", "-"], capture_output=True, check=True).stdout
        samples = array.array("f"); samples.frombytes(raw)
        if sys.byteorder != "little": samples.byteswap()
        left, right = samples[::2], samples[1::2]
        frequencies = [dominant_frequency(left), dominant_frequency(right)]
        for actual, original in zip(frequencies, (440, 660)):
            assert abs(actual / (original * settings["ratio"]) - 1) < .005, frequencies
        period = beat_period(left)
        assert abs(period - .5) <= .025, period
        peak = max(abs(v) for v in samples)
        assert peak <= 10 ** (-.1 / 20) + .0002, peak
        results.append({"key": key, "format": extension, "duration": duration, "frequencies": [round(f, 2) for f in frequencies], "beat_period": period, "gain": settings["gain"]})
    assert hashlib.sha256(source.read_bytes()).hexdigest() == original_hash, "Original was modified"
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    verified = []
    if apk.is_file():
        with zipfile.ZipFile(apk) as archive:
            for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
                with zipfile.ZipFile(io.BytesIO(archive.read(f"lib/{abi}/libffmpeg.zip.so"))) as runtime:
                    filters = [n for n in runtime.namelist() if "libavfilter.so." in n]
                    assert any(b"--enable-librubberband" in runtime.read(n) for n in filters), abi
                    assert any("librubberband.so" in n for n in runtime.namelist()), abi
                    verified.append(abi)
    print(json.dumps({"result": "PASS", "audio_checks": results, "apk_rubberband_abis": verified}))

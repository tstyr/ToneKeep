"""Exercise the production two-pass preview with owned audio; no Android device required."""
import array
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import time
import wave

ROOT = pathlib.Path(__file__).resolve().parents[1]


def run(*args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise RuntimeError(f"{args[0]} failed ({result.returncode}): {result.stderr}")
    return result.stdout


with tempfile.TemporaryDirectory(prefix="tonekeep-preview-") as temp:
    work = pathlib.Path(temp)
    classes = work / "classes"
    run("javac", "-encoding", "UTF-8", "-d", str(classes),
        str(ROOT / "app/src/main/java/jp/tonekeep/app/PitchSettings.java"),
        str(ROOT / "scripts/PitchFixtureRunner.java"))
    results = []
    for rate, channels, seconds, start, key, silence in [
        (44100, 2, 24, 3, 3, False), (96000, 6, 24, 3, -5, False),
        (48000, 2, 4, 3, 12, False), (48000, 2, 2, 0, 3, True),
    ]:
        source, output = work / "source.wav", work / "preview.wav"
        # The first two seconds have a different pitch: this also verifies input seeking.
        expressions = [f"0.98*sin(2*PI*if(lt(t,2),220,{f})*t)" for f in (440, 660)]
        expressions += ["0"] * (channels - 2)
        if silence:
            expressions = ["0"] * channels
        run("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
            f"aevalsrc='{'|'.join(expressions)}':s={rate}:d={seconds}", "-c:a", "pcm_f32le", str(source))
        original = hashlib.sha256(source.read_bytes()).hexdigest()
        before = time.monotonic()
        settings = json.loads(run("java", "-cp", str(classes), "PitchFixtureRunner",
                                  str(source), str(output), str(key), "true", "wav", str(start)))
        elapsed = time.monotonic() - before
        with wave.open(str(output), "rb") as audio:
            assert (audio.getframerate(), audio.getnchannels(), audio.getsampwidth()) == (48000, 2, 2)
            duration = audio.getnframes() / 48000
            assert abs(duration - min(20, seconds - start)) < .03, duration
            samples = array.array("h", audio.readframes(audio.getnframes()))
        if sys.byteorder != "little":
            samples.byteswap()
        peak = max(abs(v) for v in samples) / 32768
        assert peak <= 10 ** (-.1 / 20) + 2 / 32768, peak
        if silence:
            assert peak == 0 and settings["gain"] == 1
        else:
            # Ignore Rubber Band's leading/trailing transients when measuring the pure tone.
            for channel, original_frequency in enumerate((440, 660)):
                values = samples[channel::2][12000:36000]
                crossings = sum(a <= 0 < b for a, b in zip(values, values[1:]))
                frequency = crossings * 48000 / len(values)
                assert abs(frequency / (original_frequency * settings["ratio"]) - 1) < .03, frequency
        assert hashlib.sha256(source.read_bytes()).hexdigest() == original
        results.append(dict(rate=rate, channels=channels, start=start, key=key, silence=silence,
                            duration=duration, peak=round(peak, 5), elapsed=round(elapsed, 3)))
    print(json.dumps({"result": "PASS", "previews": results}))

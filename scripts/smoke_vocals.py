"""Verify the pinned vocal model and run the Android inference pipeline on owned speech/music.

Requires JDK 17 and host FFmpeg (flite). Downloads the model once into .tools/.
"""
import array
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/UVR-MDX-NET-Voc_FT.onnx"
SHA = "e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4"
MODEL = ROOT / ".tools/vocals.onnx"
QUALITY = sys.argv[1] if len(sys.argv) > 1 else "standard"
assert QUALITY in ("standard", "high")


def run(*args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=600)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


if not MODEL.is_file() or digest(MODEL) != SHA:
    MODEL.parent.mkdir(exist_ok=True)
    temporary = MODEL.with_suffix(".part")
    try:
        with urllib.request.urlopen(URL, timeout=30) as response, temporary.open("wb") as output:
            while block := response.read(65536):
                output.write(block)
        assert digest(temporary) == SHA, "Vocal model checksum mismatch"
        temporary.replace(MODEL)
    finally:
        temporary.unlink(missing_ok=True)

with tempfile.TemporaryDirectory(prefix="tonekeep-vocals-") as temp:
    work = pathlib.Path(temp)
    speech, mixture, vocals = work / "speech.wav", work / "mixture.f32", work / "vocals.f32"
    run("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
        "flite=text='Hello Tone Keep. This is a test of vocal separation. Keep the voice and reduce the backing music.':voice=slt",
        "-ar", "44100", "-ac", "2", str(speech))
    run("ffmpeg", "-v", "error", "-y", "-i", str(speech), "-f", "lavfi", "-i",
        "aevalsrc='0.12*sin(2*PI*220*t)|0.12*sin(2*PI*330*t)':s=44100:d=12",
        "-filter_complex", "[0:a][1:a]amix=inputs=2:duration=longest:normalize=0",
        "-c:a", "pcm_f32le", "-f", "f32le", str(mixture))
    original = digest(mixture)
    result = run("bash", str(ROOT / "gradlew"), "-p", str(ROOT / "scripts/vocal-smoke"), "run", "--no-daemon", "--max-workers=2",
                 "-Pmodel=" + str(MODEL), "-Pinput=" + str(mixture), "-Poutput=" + str(vocals), "-Pquality=" + QUALITY)
    assert "Vocal inference, STFT round trip, duration, progress and cancellation: PASS" in result, result
    before = array.array("f", mixture.read_bytes())
    after = array.array("f", vocals.read_bytes())
    if sys.byteorder != "little":
        before.byteswap(); after.byteswap()
    assert len(before) == len(after)
    assert all(abs(v) < 100 for v in after), "Non-finite/invalid output"
    energy = sum(v * v for v in after)
    assert energy > 1e-5, "Separation is silent"
    assert sum((a - b) ** 2 for a, b in zip(before, after)) > 1, "Model returned unchanged mixture"
    # The last seconds contain only the synthetic accompaniment: it should be suppressed.
    tail_before = sum(v * v for v in before[-44100 * 2:])
    tail_after = sum(v * v for v in after[-44100 * 2:])
    assert tail_after < .5 * tail_before, (tail_before, tail_after)
    assert digest(mixture) == original, "Original was modified"
    print(json.dumps({"result": "PASS", "quality": QUALITY, "duration": len(after) / (44100 * 2),
                      "accompaniment_tail_energy_ratio": round(tail_after / tail_before, 5),
                      "model_sha256": SHA}))

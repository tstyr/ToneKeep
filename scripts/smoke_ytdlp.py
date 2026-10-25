"""Exercise the exact bundled yt-dlp zipapp against an owned local audio fixture.

Requires Python >=3.10 plus ffmpeg/ffprobe on PATH. No external media is downloaded.
This validates the extractor/request/FFmpeg flow on the host, not the Android runtime.
"""
import functools
import hashlib
import http.server
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import threading
import wave

ROOT = pathlib.Path(__file__).resolve().parents[1]
ENGINE = ROOT / "app/src/main/res/raw/ytdlp"
EXPECTED_SHA256 = "b7af77f686e95f7845ce8d09d21957718febb8b48b30555adb9dd2292ad7bf14"
assert hashlib.sha256(ENGINE.read_bytes()).hexdigest() == EXPECTED_SHA256, "Bundled engine checksum mismatch"
assert shutil.which("ffmpeg") and shutil.which("ffprobe"), "ffmpeg and ffprobe are required"


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_args):
        pass


with tempfile.TemporaryDirectory(prefix="tonekeep-test-") as temp:
    workspace = pathlib.Path(temp)
    fixture = workspace / "fixture.wav"
    with wave.open(str(fixture), "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(44100)
        audio.writeframes(b"\x00\x00" * 44100)
    handler = functools.partial(QuietHandler, directory=str(workspace))
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{server.server_port}/fixture.wav"
    base = [sys.executable, str(ENGINE), "--ignore-config", "--no-playlist", "--socket-timeout", "20", "--no-warnings"]
    try:
        raw = subprocess.run(base + ["--dump-single-json", "--skip-download", url], capture_output=True, text=True, check=True, timeout=60)
        info = json.loads(raw.stdout)
        assert info["title"] and info["formats"], info
        target = workspace / "out" / "audio.%(ext)s"
        subprocess.run(base + ["-f", "bestaudio/best", "--extract-audio", "--audio-format", "best", "--no-mtime", "--no-overwrites", "--match-filters", "!is_live", "-o", str(target), url], capture_output=True, text=True, check=True, timeout=60)
        outputs = list(target.parent.glob("audio.*"))
        assert len(outputs) == 1, outputs
        probe = subprocess.run(["ffprobe", "-v", "error", "-show_streams", "-of", "json", str(outputs[0])], capture_output=True, text=True, check=True, timeout=20)
        stream = json.loads(probe.stdout)["streams"][0]
        assert stream["codec_type"] == "audio" and stream["codec_name"] == "pcm_s16le", stream
        assert int(stream["sample_rate"]) == 44100, stream
        print(json.dumps({"bundled_version": "2026.10.21", "extractor": info["extractor"], "output": outputs[0].name, "codec": stream["codec_name"], "sample_rate": stream["sample_rate"], "result": "PASS"}))
    finally:
        server.shutdown()
        server.server_close()

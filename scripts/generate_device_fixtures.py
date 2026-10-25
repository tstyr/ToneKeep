"""Generate owned audio fixtures for Android instrumentation tests (no network media)."""
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
assets = root / 'app/src/androidTest/assets'
assets.mkdir(parents=True, exist_ok=True)
for extension, codec in [('wav', 'pcm_s16le'), ('m4a', 'aac'), ('opus', 'libopus')]:
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i',
                    'aevalsrc=0.5*sin(2*PI*440*t)|0.5*sin(2*PI*660*t):s=48000:d=6',
                    '-c:a', codec, str(assets / ('fixture.' + extension))], check=True, timeout=60)
print('Created WAV, AAC/M4A and Opus stereo fixtures')

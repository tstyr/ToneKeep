"""Fetch a pinned model ONLY into ignored androidTest assets, never the app APK."""
import argparse
import hashlib
from pathlib import Path
import urllib.request

REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
MODELS = {
    "tiny": (77691713, "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"),
    "small": (487601967, "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b"),
}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", choices=MODELS, default="tiny")
    args = parser.parse_args()
    target = Path(__file__).resolve().parents[1] / ".tools/lyrics-test-assets/whisper-smoke.bin"
    target.parent.mkdir(parents=True, exist_ok=True)
    size, expected = MODELS[args.model]
    def valid(path):
        if not path.exists() or path.stat().st_size != size:
            return False
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        return digest.hexdigest() == expected
    if not valid(target):
        partial = target.with_suffix(".part")
        urllib.request.urlretrieve(f"https://huggingface.co/ggerganov/whisper.cpp/resolve/{REVISION}/ggml-{args.model}.bin", partial)
        if not valid(partial):
            partial.unlink(missing_ok=True)
            raise RuntimeError("Model SHA-256 or size mismatch")
        partial.replace(target)
    print(f"Verified {args.model} model: {size} bytes ({expected})")

if __name__ == "__main__":
    main()

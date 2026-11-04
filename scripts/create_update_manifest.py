"""Create the public update feed from the actual built APK (never guessed version metadata)."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=pathlib.Path)
parser.add_argument("--output", type=pathlib.Path, required=True)
parser.add_argument("--download-url", required=True)
args = parser.parse_args()
sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
if not sdk:
    raise SystemExit("ANDROID_HOME is required")
aapt = pathlib.Path(sdk) / "build-tools/35.0.0/aapt"
metadata = subprocess.run([str(aapt), "dump", "badging", str(args.apk)], check=True, capture_output=True, text=True).stdout
package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", metadata)
if not package:
    raise SystemExit("Unable to read APK package/version metadata")
if not args.download_url.startswith("https://github.com/tstyr/ToneKeep/releases/download/"):
    raise SystemExit("Unexpected update distribution URL")
with args.apk.open("rb") as source:
    checksum = hashlib.file_digest(source, "sha256").hexdigest()
manifest = dict(packageName=package[1], versionCode=int(package[2]), versionName=package[3],
                url=args.download_url, sha256=checksum, size=args.apk.stat().st_size)
args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Update manifest: {manifest['packageName']} {manifest['versionName']} ({manifest['versionCode']})")

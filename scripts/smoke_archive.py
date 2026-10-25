"""Read-only integration probe for the live catalog, metadata and download endpoint."""
import json
import urllib.parse
import urllib.request


def get(url):
    request = urllib.request.Request(url, headers={"User-Agent": "ToneKeep/1.0 (integration test)"})
    with urllib.request.urlopen(request, timeout=40) as response:
        return json.load(response)


query = 'mediatype:audio AND licenseurl:(*creativecommons.org*) AND (title:"Moonlight" OR creator:"Moonlight" OR description:"Moonlight")'
params = {"q": query, "output": "json", "rows": 5, "fl[]": ["identifier", "title", "licenseurl"]}
search = get("https://archive.org/advancedsearch.php?" + urllib.parse.urlencode(params, doseq=True))["response"]
assert search["numFound"] > 0, search
for item in search["docs"]:
    metadata = get("https://archive.org/metadata/" + item["identifier"])
    audio = [f for f in metadata.get("files", []) if f.get("name", "").lower().endswith((".flac", ".mp3", ".ogg", ".wav")) and not f.get("private")]
    if not audio:
        continue
    source = audio[0]
    url = "https://archive.org/download/" + urllib.parse.quote(item["identifier"]) + "/" + urllib.parse.quote(source["name"])
    request = urllib.request.Request(url, headers={"Range": "bytes=0-1023", "User-Agent": "ToneKeep/1.0"})
    with urllib.request.urlopen(request, timeout=40) as response:
        chunk = response.read(1024)
        assert chunk and "audio" in response.headers.get("Content-Type", ""), response.headers
        print(json.dumps({"matches": search["numFound"], "identifier": item["identifier"], "license": metadata["metadata"].get("licenseurl"), "file": source["name"], "http_status": response.status, "bytes_read": len(chunk)}, ensure_ascii=False))
    break
else:
    raise AssertionError("No downloadable audio in first five catalog entries")

"""Verify catalogs and a small real file; does not download model weights."""
import hashlib
import json
import urllib.parse
import urllib.request
from pathlib import Path

def get(url, headers=None):
    request = urllib.request.Request(url, headers={"Accept-Encoding": "identity", **(headers or {})})
    with urllib.request.urlopen(request, timeout=40) as response:
        chunks = []
        while chunk := response.read(65536):
            chunks.append(chunk)
        return response.status, dict(response.headers), b"".join(chunks)

results = []
for repo in ["dr3334/PP-DocLayoutV3-mnn", "dr3334/ovrics-ocrv2_mnn"]:
    api = "https://modelscope.cn/api/v1/models/" + repo
    _, _, data = get(api + "/repo/files?Revision=master&Recursive=true")
    files = [f for f in json.loads(data)["Data"]["Files"] if f["Type"] == "blob" and not f["Path"].startswith(".") and not f["Path"].endswith(".md")]
    assert files and all(len(f["Sha256"]) == 64 for f in files)
    file = min(files, key=lambda f: f["Size"])
    url = api + "/repo?" + urllib.parse.urlencode({"Revision": file["Revision"], "FilePath": file["Path"]})
    _, _, data = get(url)
    assert len(data) == file["Size"] and hashlib.sha256(data).hexdigest() == file["Sha256"]
    offset = max(1, len(data) // 2)
    status, headers, resumed = get(url, {"Range": f"bytes={offset}-"})
    assert status in (200, 206)
    if status == 206:
        assert resumed == data[offset:]
    else:
        # Match the app: discard ambiguous 200 Range responses and restart without Range.
        _, _, restarted = get(url)
        assert restarted == data
    result = {"repo": repo, "files": [{k: f[k] for k in ["Path", "Size", "Sha256", "Revision"]} for f in files], "totalBytes": sum(f["Size"] for f in files), "verifiedSmallFile": file["Path"], "verifiedBytes": len(data), "sha256Verified": True, "rangeHTTP": status, "resumeStrategy": "append" if status == 206 else "restart-without-range", "rangeHeaders": {k: v for k, v in headers.items() if k.lower() in ("content-range", "content-length")}}
    results.append(result)
    print(repo, len(files), "files;", result["totalBytes"], "bytes; verified", file["Path"], "Range HTTP", status, flush=True)
Path("verification").mkdir(exist_ok=True)
Path("verification/modelscope-live.json").write_text(json.dumps(results, indent=2, ensure_ascii=False))

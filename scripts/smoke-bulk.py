"""Local-only bulk acceptance test. Explicit mock providers; never calls paid generation.

Run --submit, then --verify once batches are terminal. State/ZIP evidence is stored in
frontend/test-results/bulk/. Re-running --submit reuses the saved project and keys.
"""
import argparse
import hashlib
import io
import json
import struct
import urllib.request
import uuid
import zipfile
import zlib
from pathlib import Path

BASE = "http://localhost:3000/api"
OUT = Path(__file__).resolve().parent.parent / "frontend/test-results/bulk"
OUT.mkdir(parents=True, exist_ok=True)
STATE = OUT / "state.json"


def request(path, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    return json.loads(urllib.request.urlopen(urllib.request.Request(
        BASE + path, data=data, headers={"Content-Type": "application/json", **(headers or {})}), timeout=180).read())


def png():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress((b"\0" + b"\x50\x80\xa0" * 64) * 64)) + chunk(b"IEND", b"")


def archive(video=False):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_STORED) as zip:
        for n in range(2 if video else 125):
            zip.writestr(f"task-{n:03d}/task.md", f"Mock acceptance fixture {n}: a mountain road at dawn")
            if video:
                zip.writestr(f"task-{n:03d}/reference.png", png())
        if not video:
            zip.writestr("invalid/missing-prompt.txt", "Deliberately invalid task")
    return output.getvalue()


def upload(state, kind):
    if kind in state.get("batches", {}):
        return request("/v1/bulk/batches/" + state["batches"][kind])
    video = kind == "GEMINI_VIDEO"
    # ZIP timestamps are part of the checksum. Persist exact bytes before submission,
    # so a lost response resumes with the identical archive rather than rebuilding it.
    input_file = OUT / (kind + "-input.zip")
    if not input_file.exists():
        input_file.write_bytes(archive(video))
    options = {"width": 320, "height": 240, "durationSeconds": 2} if video else {"width": 64, "height": 64}
    payload = {"projectId": state["projectId"], "name": "Bulk acceptance " + kind,
               "kind": kind, "provider": "mock-video" if video else "mock",
               "model": "deterministic-motion-v1" if video else "studio-mock-v1",
               "options": options, "authorizePaid": False}
    boundary = "bulk-" + str(uuid.uuid4())
    parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="request"\r\nContent-Type: application/json\r\n\r\n'.encode(),
             json.dumps(payload).encode(),
             f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="archive"; filename="tasks.zip"\r\nContent-Type: application/zip\r\n\r\n'.encode(),
             input_file.read_bytes(), f"\r\n--{boundary}--\r\n".encode()]
    req = urllib.request.Request(BASE + "/v1/bulk/batches", data=b"".join(parts), headers={
        "Content-Type": "multipart/form-data; boundary=" + boundary, "Idempotency-Key": state["keys"][kind]})
    return json.loads(urllib.request.urlopen(req, timeout=180).read())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--submit", action="store_true")
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    state = json.loads(STATE.read_text(encoding="utf-8")) if STATE.exists() else {}
    if args.submit:
        if not state:
            state = {"projectId": request("/projects", {"name": "Bulk acceptance · mock only"})["id"],
                     "keys": {k: str(uuid.uuid4()) for k in ("GPT_IMAGE", "GEMINI_VIDEO")}, "batches": {}}
            STATE.write_text(json.dumps(state, indent=2), encoding="utf-8")
        for kind in state["keys"]:
            batch = upload(state, kind)
            state["batches"][kind] = batch["id"]
            STATE.write_text(json.dumps(state, indent=2), encoding="utf-8")
            print(json.dumps({"kind": kind, "id": batch["id"], "counts": batch["counts"]}), flush=True)
    if args.verify:
        reports = {}
        for kind, batch_id in state["batches"].items():
            batch = request("/v1/bulk/batches/" + batch_id)
            print(json.dumps({"kind": kind, "counts": batch["counts"]}), flush=True)
            expected = 125 if kind == "GPT_IMAGE" else 2
            assert batch["counts"]["COMPLETED"] == expected, "Batch not completed; rerun --verify later"
            assert batch["counts"]["FAILED"] == (1 if kind == "GPT_IMAGE" else 0)
            result = urllib.request.urlopen(BASE + "/v1/bulk/batches/" + batch_id + "/results.zip", timeout=180).read()
            (OUT / (kind + ".zip")).write_bytes(result)
            with zipfile.ZipFile(io.BytesIO(result)) as zip:
                manifest = json.loads(zip.read("manifest.json"))["tasks"]
                assert len(zip.namelist()) == expected + 1
                for entry in manifest:
                    if entry["status"] == "COMPLETED":
                        assert hashlib.sha256(zip.read(entry["file"])).hexdigest() == entry["sha256"]
            assert all(cost["estimated_cost"] is not None and float(cost["estimated_cost"]) == 0 and cost["actual_cost"] is not None and float(cost["actual_cost"]) == 0 for cost in batch["costs"])
            reports[kind] = batch
        (OUT / "verification.json").write_text(json.dumps(reports, indent=2), encoding="utf-8")
        print("PASS: 127 mock outputs, invalid-task isolation, export checksums and zero provider cost", flush=True)


if __name__ == "__main__":
    main()

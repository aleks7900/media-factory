"""Explicit provisioning only. Runtime never downloads weights."""
import hashlib
import json
import os
from pathlib import Path
import urllib.request

root = Path(os.getenv("MODEL_DIR", "/models"))
root.mkdir(parents=True, exist_ok=True)
for model in json.loads(Path(__file__).with_name("models.json").read_text()):
    destination = root / model["file"]
    if destination.exists() and hashlib.sha256(destination.read_bytes()).hexdigest() == model["sha256"]:
        print(model["id"], "verified")
        continue
    temporary = destination.with_suffix(".download")
    try:
        urllib.request.urlretrieve(model["url"], temporary)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != model["sha256"]:
            raise RuntimeError("MODEL_CHECKSUM_MISMATCH: " + model["id"])
        temporary.replace(destination)
        print(model["id"], "provisioned")
    finally:
        temporary.unlink(missing_ok=True)

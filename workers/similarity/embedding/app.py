"""Pinned, long-lived CLIP image/text inference. No user URLs or paid APIs."""
import base64
import io
import logging
import os
import threading
import time
from contextlib import asynccontextmanager

import numpy as np
import torch
from fastapi import FastAPI, HTTPException
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, Field
from transformers import CLIPModel, CLIPProcessor

MODEL = os.getenv("EMBEDDING_MODEL", "openai/clip-vit-base-patch32")
REVISION = os.getenv("EMBEDDING_REVISION", "3d74acf9a28c67741b2f4f2ea7635f0aaf6f0268")
DEVICE_REQUEST = os.getenv("EMBEDDING_DEVICE", "AUTO").upper()
if DEVICE_REQUEST not in {"AUTO", "CPU", "CUDA"}:
    raise ValueError("EMBEDDING_DEVICE must be AUTO, CPU or CUDA")
DEVICE = "cuda" if DEVICE_REQUEST != "CPU" and torch.cuda.is_available() else "cpu"
Image.MAX_IMAGE_PIXELS = 20_000_000
lock = threading.Lock()
model = processor = None


@asynccontextmanager
async def lifespan(app):
    global model, processor
    torch.set_num_threads(max(1, int(os.getenv("TORCH_THREADS", "4"))))
    processor = CLIPProcessor.from_pretrained(MODEL, revision=REVISION, use_fast=False)
    model = CLIPModel.from_pretrained(MODEL, revision=REVISION, use_safetensors=False).eval().to(DEVICE)
    logging.warning("Embedding model=%s revision=%s device=%s", MODEL, REVISION, DEVICE)
    yield


app = FastAPI(lifespan=lifespan)


class BodyLimit:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        size = 0
        async def bounded_receive():
            nonlocal size
            message = await receive()
            size += len(message.get("body", b""))
            if size > 64 * 1024 * 1024:
                raise HTTPException(413, "Batch body exceeds 64 MiB")
            return message
        await self.app(scope, bounded_receive, send)


app.add_middleware(BodyLimit)


class ImageInput(BaseModel):
    id: str = Field(max_length=100)
    data: str = Field(max_length=35_000_000)


class Batch(BaseModel):
    images: list[ImageInput] = Field(min_length=1, max_length=16)


class TextBatch(BaseModel):
    texts: list[str] = Field(min_length=1, max_length=16)


def metadata():
    return {"provider": "local-clip", "model": MODEL, "version": REVISION,
            "dimension": model.config.projection_dim, "device": DEVICE,
            "preprocessing": "RGB/bicubic-shortest224/center224/rescale255/CLIP-mean-std",
            "normalized": True}


@app.get("/health")
def health():
    return {"status": "UP", **metadata()}


def result(features, started):
    vectors = torch.nn.functional.normalize(features.float(), p=2, dim=-1).cpu().numpy()
    if not np.isfinite(vectors).all() or not np.allclose(np.linalg.norm(vectors, axis=1), 1, atol=1e-5):
        raise HTTPException(500, "Invalid model output")
    return {**metadata(), "vectors": vectors.tolist(), "durationMs": round((time.monotonic()-started)*1000),
            "externalApiCost": 0, "computeMonetaryCost": None}


@app.post("/v1/images/embed")
def images(batch: Batch):
    decoded = []
    try:
        for item in batch.images:
            raw = base64.b64decode(item.data, validate=True)
            if len(raw) > 25 * 1024 * 1024:
                raise ValueError("Image too large")
            with Image.open(io.BytesIO(raw)) as image:
                if image.width * image.height > Image.MAX_IMAGE_PIXELS:
                    raise ValueError("Image pixel limit exceeded")
                image.load()
                decoded.append(image.convert("RGB"))
    except (ValueError, OSError, UnidentifiedImageError, Image.DecompressionBombError):
        raise HTTPException(422, "Invalid or oversized image") from None
    started = time.monotonic()
    with lock, torch.inference_mode():
        inputs = processor(images=decoded, return_tensors="pt").to(DEVICE)
        return result(model.get_image_features(**inputs), started)


@app.post("/v1/texts/embed")
def texts(batch: TextBatch):
    if any(not text.strip() or len(text) > 16000 for text in batch.texts):
        raise HTTPException(422, "Text must contain 1–16000 characters")
    started = time.monotonic()
    with lock, torch.inference_mode():
        inputs = processor(text=batch.texts, return_tensors="pt", padding=True,
                           truncation=True, max_length=77).to(DEVICE)
        return {**result(model.get_text_features(**inputs), started), "textTokenLimit": 77}

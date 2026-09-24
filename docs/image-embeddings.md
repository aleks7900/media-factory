# Reproducible local embeddings

| Property | Value |
|---|---|
| Provider | `local-clip` |
| Implementation | Hugging Face Transformers 4.53.3 `CLIPModel` / `CLIPProcessor`, PyTorch 2.8.0 |
| Model | `openai/clip-vit-base-patch32` |
| Pinned revision | `3d74acf9a28c67741b2f4f2ea7635f0aaf6f0268` |
| Projection dimension | 512 for images and text |
| Decode | Pillow 11.3.0, RGB conversion, maximum 20 million pixels and 25 MiB/image |
| Resize/crop | Bicubic shortest edge 224, center crop 224 × 224 |
| Rescale | Divide pixel values by 255 |
| Mean | `[0.48145466, 0.4578275, 0.40821073]` |
| Standard deviation | `[0.26862954, 0.26130258, 0.27577711]` |
| Normalization | Float32 projection, L2 unit vector; finite/norm/dimension validation in worker, Java, and database |
| Text | Model tokenizer, explicit truncation at 77 tokens |
| Distance | Cosine `<=>`; similarity `1 - cosineDistance` |

The pinned [preprocessor configuration](https://huggingface.co/openai/clip-vit-base-patch32/blob/3d74acf9a28c67741b2f4f2ea7635f0aaf6f0268/preprocessor_config.json), [model card](https://huggingface.co/openai/clip-vit-base-patch32), [Transformers CLIP API](https://huggingface.co/docs/transformers/model_doc/clip), and [pgvector documentation](https://github.com/pgvector/pgvector) are the implementation references.

`docker compose up -d --build` starts the long-lived worker. The first start downloads the pinned open-source model into the `embedding_models` named volume; subsequent starts reuse it. Readiness is available at `http://localhost:8001/health` after model loading. This download needs internet access and is unrelated to paid OpenAI API credentials. The application never requests paid embedding inference.

The default image installs CPU PyTorch wheels and works without a GPU. `EMBEDDING_DEVICE=AUTO` chooses CUDA when the installed runtime and hardware support it, otherwise CPU. `CPU` forces CPU; a requested but unavailable `CUDA` also falls back to CPU and the actual device is reported. GPU use requires rebuilding with a compatible CUDA PyTorch index via the Docker `TORCH_INDEX` build argument and granting the container GPU access. This deployment's CPU path was exercised; GPU throughput is not claimed. `TORCH_THREADS` defaults to four.

The worker serves `POST /v1/images/embed` with `{images:[{id,data:base64}]}` and `POST /v1/texts/embed` with `{texts:[...]}`. It accepts at most sixteen items, limits the request body to 64 MiB, serializes model inference with a process lock, and runs one Uvicorn process. Backend output validation checks exact model identity and never logs vectors. Worker requests contain bytes, never user-supplied URLs.

## Model lifecycle

1. Deploy a worker with the desired pinned `EMBEDDING_MODEL` and `EMBEDDING_REVISION`, retaining the historical model cache.
2. Discover it with `POST /api/v1/embedding-models {"provider":"local-clip"}`. This registers immutable metadata and creates its partial HNSW index. Reusing an identity with a different dimension/preprocessing is rejected.
3. Enqueue `REINDEX` with the returned `modelId` and an `Idempotency-Key`. Every original receives a separate `(asset, model)` embedding; historical vectors remain unchanged.
4. Resolve failed jobs and wait for complete coverage. Activation refuses missing/failed analysis.
5. Activate the model. Queries, clustering, and publication checks use only the active identity. Roll back by activating a previously complete model; if new originals arrived, backfill that historical model first using a compatible worker.

One configured local worker endpoint serves one model revision at a time. During an upgrade, new jobs targeting a different revision fail explicitly rather than storing mislabeled vectors. Schedule a maintenance window or add a separately configured provider endpoint before zero-downtime upgrades. Index creation is synchronous and suitable for a newly registered model with no vectors; do not use it as an online rebuild of a large populated index.

`mock-embedding/deterministic-test/v1` uses seeded unit vectors only for explicit tests. It makes no semantic claims and is never selected automatically in Compose. Backend CI uses fixed vectors and this mock; it needs Docker/PostgreSQL but no GPU, model download, or paid API. Local CLIP's semantic behavior is verified separately.

CLIP can associate meaning while missing fine visual differences, counting details, or small text. English prompts and the training distribution influence results. Similarity is retrieval evidence, not proof of identity, ownership, safety, or visual quality.

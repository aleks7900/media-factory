# Local image processing worker

One FastAPI process serializes executions. Model loading is lazy and checksum-verified; it never downloads models. `/health` reports CUDA, VRAM, model availability and active jobs. Missing weights degrade upscaling without disabling deterministic processing. The HTTP service must stay on the private Compose network or loopback; it has no public authentication boundary.

## Run

From the repository root on Windows:

```powershell
./scripts/provision-processing.ps1
docker compose build processing
docker compose up -d processing
```

CUDA 12.8 / PyTorch 2.8 build and GPU allocation:

```powershell
docker compose -f compose.yaml -f compose.gpu.yaml build processing
docker compose -f compose.yaml -f compose.gpu.yaml up -d processing
```

The default image installs CPU PyTorch. GPU passthrough alone does not change a CPU wheel. Use the override when building **and** starting GPU mode. NVIDIA Container Toolkit/WSL GPU support is required. A native Python 3.12 environment can use the same requirements, pinned torch/torchvision and `uvicorn app:app --host 127.0.0.1 --port 8002 --workers 1`; set `MODEL_DIR` to explicitly provisioned files and `PROCESSING_TMP` to a writable directory.

Tests, no models or GPU required:

```sh
docker compose run --rm --no-deps processing python -m unittest discover -s tests -v
```

Actual model benchmark is explicitly opt-in:

```sh
docker compose exec processing python benchmark.py /tmp/input.png --scale 2 --output /tmp/result.png
```

Supply an input with `docker compose cp input.png processing:/tmp/input.png`. The benchmark uses exclusive output creation and reports measured duration, dimensions, output bytes, device and CUDA peak allocation (CPU peak allocation is unavailable). There are no default-CI model downloads.

## Limits and configuration

`PROCESSING_DEVICE=AUTO|CPU`, `CPU_FALLBACK_ENABLED=true`, `UPSCALE_TILE_SIZE=256`, `TORCH_THREADS=4`, `MODEL_DIR=/models`, `PROCESSING_TMP=/tmp/processing`, `MIN_FREE_DISK_BYTES=1073741824`, `MAX_INPUT_PIXELS=64000000` (worker accepts trusted intermediates; backend master limit is 20 MP). Output dimensions are capped at 8192 per axis and 64 million pixels. Compose caps worker RAM at 6 GB and CPU allocation at four cores. One worker instance supports one active execution; horizontal GPU scheduling is not implemented.

Temporary directories are per execution and removed on both success and failure. Processing uses memory rather than temporary image files. Durable lossless upscale artifacts are kept in MediaStorage for retries; they are not temporary files. See the lineage/retention policy in `docs/processing-lineage.md`.

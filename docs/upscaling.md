# Local super-resolution

The registry lives in `workers/image-processing/models.json`. Provision explicitly with `scripts/provision-processing.ps1` (Windows) or `MODEL_DIR=... python provision.py`. Runtime rejects missing/corrupt weights and never downloads them.

| ID | Official model | Release | Native scale | SHA-256 |
|---|---|---|---|---|
| general-2x | RealESRGAN_x2plus | v0.2.1 | 2 | `49fafd45f8fd7aa8d31ab2a22d14d91b536c34494a5cfe31eb5d89c2fa266abb` |
| general-4x | realesr-general-x4v3 | v0.2.5.0 | 4 | `8dc7edb9ac80ccdc30c3a5dca6616509367f05fbc184ad95b731f05bece96292` |

Both are official [Real-ESRGAN release assets](https://github.com/xinntao/Real-ESRGAN/releases), distributed from the project's [BSD-3-Clause repository](https://github.com/xinntao/Real-ESRGAN/blob/master/LICENSE). The [model zoo](https://github.com/xinntao/Real-ESRGAN/blob/master/docs/model_zoo.md) describes the smaller 4× general model. Attribution is retained in `workers/image-processing/LICENSE.Real-ESRGAN`. Spandrel 0.4.1 loads the architectures without the older BasicSR/torchvision import incompatibility. No face-enhancement model is used.

The planner selects skip/2×/4× based on unrounded pixel requirements. A fan-out request shares the scale needed by its largest branch; small previews bypass the neural node. General/photo/illustration are registry content classes; anime-specific models are not provisioned. Model choice is frozen in each plan and returned identity must match before registration.

Inference uses float32 PyTorch 2.8, CUDA 12.8 in the optional GPU image, CPU otherwise. `requireGpu=true` never falls back to CPU. `CPU_FALLBACK_ENABLED=false` disables CPU upscale. CPU fallback applies to unavailable GPU; it does not silently recover an OOM by switching devices.

Tiles default to 256 pixels with a 16-pixel context margin. Only the central prediction is assembled into the output, following the padded-context approach of the [upstream helper](https://github.com/xinntao/Real-ESRGAN/blob/master/realesrgan/utils.py). Full output remains in host memory. CUDA OOM clears the allocator cache and retries once at half tile size, then returns `GPU_OOM`. Model/crop content can still produce artifacts; tiling is not a promise of perceptual perfection.

The worker reports available VRAM and measured peak CUDA allocation. Live execution results and limitations are recorded in `task-06-report.md`; capability detection alone is not counted as a GPU benchmark.

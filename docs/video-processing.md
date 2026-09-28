# Immutable video processing

`workers/video` performs FFmpeg work outside the Java scheduler. Requests contain bounded media bytes, validated numeric/enumerated settings and a run UUID. Paths and arbitrary shell/filter strings are not accepted. Temporary files are removed on success, error and cancellation.

The graph is raw → processed video → loop master → variants → three-repeat preview → poster/thumbnail. Transcoding records the complete validated settings and filter graph; loop operations record their strategy. Every output has SHA-256, size, actual metadata, parent variant and run/version. Completed runs and history cannot be updated. Storage uses create-only semantics, with exact-checksum replay allowed. Raw files are never overwritten.

The configurable transcode includes trim, focal crop or fit/padding, resize, FPS normalization, optional `deshake`, `minterpolate`, `hqdn3d`, `unsharp`, saturation, yuv420p, square pixels, BT.709 metadata and faststart encoding. Stabilization/interpolation are off by default. Focal coordinates are explicit; this baseline does not claim AI-aware subject tracking. Stabilizing an intentional pan is usually inappropriate.

Supported encodings are H.264, H.265 and experimental AV1. CPU CRF quality defaults to 18 for masters. Bitrate mode is available with bounded target/maxrate/buffer. Hardware encoding uses a bounded bitrate instead of pretending CPU CRF has identical semantics. Variants preserve the full loop master's duration, including ping-pong expansion.

Worker limits: 128 MiB input/combined output, 4096 dimension ceiling, 9 MP, 60 FPS, profile maximum duration 30 seconds, 1800 analysis frames, two CPU tasks and one GPU task. Local downloads have two slots per backend instance. Backend leases and worker admission protect different resources. One worker process owns the semaphores; scaling worker replicas requires dividing or externally coordinating GPU capacity.

The processor records FFmpeg version, encoder/device, operation wall time, total processing wall time and input/output byte counts. These are elapsed durations, not sampled CPU utilization or GPU utilization. All persisted graph parameters support reprocessing; different codec builds/hardware may produce different bytes.

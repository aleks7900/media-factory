# FFmpeg worker

The Debian Bookworm worker pins package `7:5.1.9-0+deb12u1` (FFmpeg 5.1.9). See the [Debian package](https://packages.debian.org/bookworm/ffmpeg) and [official filter reference](https://ffmpeg.org/ffmpeg-filters.html). Startup probes both executables, available encoders/decoders and actual NVENC/QSV encoder initialization. An encoder appearing in a list does not prove the hardware is usable.

`GET http://localhost:8003/health` exposes versions, encoder/decoder capabilities, hardware-probe results, active jobs and CPU/GPU limits. CPU H.264 is the portable default. `GPU` requires a working encoder unless `allowCpuFallback` is explicitly enabled. `AUTO` uses available hardware and otherwise CPU. AV1 uses the CPU encoder in this baseline.

NVIDIA: use `compose.gpu.yaml` and a functioning NVIDIA container runtime. The overlay exposes video driver capabilities and retains the existing image-processing GPU service. Intel QSV is probed but device forwarding is deployment-specific; the default Compose file does not assume an Intel device exists. GPU support can remain unavailable on Docker Desktop despite a GPU being visible to CUDA applications.

Commands use argument arrays with `shell=False`, `-nostdin`, output no-overwrite, bounded log files, deadline/cancellation checks and limited encoder/filter threads. ffprobe restricts input containers and network protocols; full decoding validates integrity before processing. FFmpeg errors return bounded diagnostics; no process stderr is used as executable input.

Troubleshooting: check worker health and container logs first. `GPU_ENCODER_UNAVAILABLE` means the runtime probe failed; use CPU or repair runtime/device forwarding. `BUSY` is backpressure and is deferred by the backend. `PROCESS_TIMEOUT`, codec errors and validation failures retain the raw asset for inspection. Do not delete MinIO originals to retry processing.

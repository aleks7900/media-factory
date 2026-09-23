# Mock video fixture

`mock-video.webm` is an original FFmpeg-generated, one-second 128×128 test-pattern animation at 8 fps, encoded as VP9/WebM. It contains no downloaded media or third-party creative content.

Reproduce using FFmpeg:

```sh
ffmpeg -f lavfi -i testsrc=size=128x128:rate=8:duration=1 -c:v libvpx-vp9 -pix_fmt yuv420p mock-video.webm
```

The video provider returns this fixed clip as a contract-test mock. Image generation remains the active production queue pipeline.

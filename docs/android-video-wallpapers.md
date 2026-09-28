# Android video wallpapers

The seeded WALLPAPER_LOOP profile requests a 720×1280, 5-second source with subtle motion, then produces a high-quality master and derived delivery files. Provider duration supports 5/10 seconds for the real adapter; local crossfade may shorten a master and ping-pong may nearly double it.

| Family | Dimensions | FPS | Default encoder/quality |
|---|---|---|---|
| Android FHD | 1080×1920 | 30 | H.264 / CRF 22 |
| Android generic fallback | 720×1280 | 24 | H.264 / CRF 24 |
| Optional Android QHD | caller-configured even dimensions, e.g. 1440×2560 | configured ≤60 | H.264 default |
| Social vertical | 1080×1920 | 30 | H.264 / CRF 22 |
| Social horizontal | 1920×1080 | 30 | H.264 / CRF 22 |
| Social square | 1080×1080 | 30 | H.264 / CRF 22 |
| Preview | 360×640 | 24 | H.264 / CRF 28 |

Output is MP4/yuv420p, square pixels, faststart and silent by default. CRF controls variable bitrate; BITRATE mode defaults to a 6 Mbps target and can be configured. Larger delivery dimensions are resampling, not new AI detail. FILL crops around configurable focal coordinates; FIT preserves the whole source with padding. Inspect composition on tall devices before release.

H.264 is the baseline assumption, not a universal Android compatibility certification. H.265 and AV1 are optional and require device/client testing. Playback smoothness, decoder limits, battery use and actual gapless looping require validation in the future Android wallpaper player. The generic H.264 variant is the fallback; poster and thumbnail provide static discovery artwork.

TASK-07's future external Android backend remains separate. This task adds video media families and lineage but does not invent Android endpoints or access its database. There is no live Android publication integration without a supplied backend/player contract. The existing wallpaper publication boundary remains the place to map these media references when that contract supports video.

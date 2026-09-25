# Android wallpaper variants

The registry describes resolution families, not individual phone models. All binaries reuse TASK-06 AssetVariant, immutable storage and checksummed artifact lineage.

| Profile | Target bounds | Aspect | Format / quality | Crop |
|---|---|---|---|---|
| WALLPAPER_MASTER | Minimum 2160 × 4800 | Preserve source | PNG / lossless | PRESERVE |
| ANDROID_FHD_PORTRAIT | 1080 × 2400 | 9:20 | JPEG / 95 | SMART_FILL |
| ANDROID_QHD_PORTRAIT | 1440 × 3200 | 9:20 | JPEG / 95 | SMART_FILL |
| ANDROID_TALL_PORTRAIT | 1080 × 2520 | 3:7 | JPEG / 95 | SMART_FILL |
| ANDROID_GENERIC_PORTRAIT | 1080 × 1920 | 9:16 | JPEG / 95 | SMART_FILL |
| ANDROID_LOCK_SCREEN | 1440 × 3200 | 9:20 | JPEG / 95 | SMART_FILL |
| ANDROID_HOME_SCREEN | 1440 × 3200 | 9:20 | JPEG / 95 | SMART_FILL |
| ANDROID_PREVIEW | Within 540 × 1200 | Preserve source | WebP / 85 | FIT |
| ANDROID_PREVIEW_SMALL | Within 270 × 600 | Preserve source | WebP / 80 | FIT |
| ANDROID_THUMBNAIL | Within 180 × 400 | Preserve source | WebP / 80 | FIT |

JPEG quality may decrease to 80 within the configured byte budget. Preview/thumbnail FIT dimensions are bounds, not a promise of an exact aspect ratio. The verified 2:1 master produced 540 × 1080 previews and 180 × 360 thumbnails. The master uses the smallest supported neural scale meeting its minimum dimensions; a 1024 × 2048 input produced a 4096 × 8192 master with preserved geometry. It is encoded losslessly before all selected downstream branches, preventing repeated lossy encoding.

ANDROID_STANDARD and ANDROID_AMOLED select master, FHD, QHD, generic, preview and thumbnail. ANDROID_PREMIUM adds tall portrait. LOCK_SCREEN and HOME_SCREEN profiles add their corresponding safe-zone variant.

## Safe zones and crop

ANDROID_HOME reserves top/bottom fractions .12/.12. ANDROID_LOCK reserves .28/.10. These are configurable composition heuristics; Android launchers, clocks, notification layouts and user widgets vary. No launcher UI is burned into output pixels.

TASK-06 supplies QA focal regions where present, face/saliency detection, crop candidates, preservation evidence and manual overrides. An unsafe automatic crop uses explicit black letterboxing rather than silently cutting important subjects. The crop editor records a new processing run. Return to wallpaper review and attach that run; all required profiles must be present and the original must match. Existing variants/manifests remain immutable.

## Client selection

`WallpaperVariantSelector` accepts physical screen width/height and optional quality tier. It excludes preview/thumbnail profiles, filters to variants meeting both dimensions and within 6% relative aspect-ratio difference, then chooses the smallest pixel area. If none qualifies, it returns ANDROID_GENERIC_PORTRAIT. Do not multiply already-physical pixel dimensions by Android density. Quality-tier matching is explicit; it is not a reason to download the huge master.

The future backend must map descriptors to its real delivery URLs and access policy. The current internal storage references are not public URLs. See the [proposed contract](android-wallpaper-backend-contract.md).

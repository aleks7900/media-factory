# Processing profiles

Eight version-1 presets are seeded by Flyway: STOCK_STANDARD, STOCK_4K, WALLPAPER_ANDROID, WALLPAPER_PHONE, SOCIAL_PORTRAIT, SOCIAL_SQUARE, THUMBNAIL, PREVIEW. Defaults use conservative 0.15 sharpening, no denoise, sRGB, public metadata stripping and explicit black JPEG background.

`GET /api/v1/processing-profiles` lists versions. `POST /api/v1/processing-profiles/{KEY}/drafts` accepts a definition. Publish with `POST /api/v1/processing-profile-versions/{id}/publish`, deprecate with `/deprecate`. Published definitions cannot be changed/deleted by SQL. A newer published version affects new plans only.

Example definition:

```json
{"width":2400,"height":2400,"mode":"PRESERVE","format":"JPEG","minimumMegapixels":4,"minimumWidth":2400,"minimumHeight":2400,"quality":95,"minimumQuality":88,"maxBytes":20971520,"progressive":true,"subsampling":0,"background":"BLACK","denoise":0,"sharpen":0.15,"sharpenRadius":1,"sharpenThreshold":3,"colorSpace":"sRGB","metadataPolicy":"PUBLIC_STRIP","requireGpu":false,"visualQa":false}
```

JPEG supports quality/progressive/subsampling 0,1,2; PNG supports compressionLevel 0–9 and retains alpha; WebP supports quality/lossless. Compression obeys target/minimum quality and the maximum byte size. Impossible constraints fail with OUTPUT_TOO_LARGE. RGB/RGBA are supported; unsupported color modes fail rather than silently dropping color information. EXIF orientation is applied before cropping. Embedded ICC is converted through Pillow/LittleCMS; invalid ICC fails, untagged input is explicitly recorded as assumed sRGB.

Wallpaper targets are data: GET/PUT `/api/v1/wallpaper-targets/{KEY}` (GET collection at `/wallpaper-targets`). Create a frozen draft from a target using POST `/processing-profiles/{KEY}/from-target/{TARGET}` and publish it. Editing a target does not alter previously published profile versions.

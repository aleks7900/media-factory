# Wallpaper processing

Initial target registry: ANDROID_1440_3200, PHONE_1290_2796, ANDROID_1080_2400. Initial published presets include the first two; create/publish a profile from the third or a new target without changing Java code.

Wallpaper profiles default to SMART_FILL, 15% top / 10% bottom safe zones, 15% focal padding and explicit black letterboxing for unsafe crops. The crop solver tries to keep important subjects away from clock/status areas. It does not assume a specific physical phone model.

Select multiple profiles in the Processing page or one process API request. Branches share the highest necessary neural intermediate but resize directly from that high-quality ancestor, never another lossy derivative. Preview/thumbnail branches can use the original when large enough. Per-branch validation, crop geometry and final dimensions are visible in the run record. Manual override preserves the automatic crop and human-adjusted final rectangle.

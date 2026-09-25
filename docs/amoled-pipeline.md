# AMOLED analysis

ANDROID_AMOLED is a structured production profile with deterministic analysis, not a label inferred from a collection title. The analyzer measures the approved original and processed lossless master, retaining immutable evidence for each checksum. It does not change pixels.

For an 8-bit sRGB channel `s = channel / 255`, linearize with `s / 12.92` when `s <= 0.04045`, otherwise `((s + 0.055) / 1.055)^2.4`. Luminance is `0.2126 R + 0.7152 G + 0.0722 B` in linear light. Java ImageIO exposes RGB values through the image color model. Input must be opaque; ambiguous transparency is rejected. Decode is bounded to 64 MiB and 64 million pixels, with dimensions inspected before full decoding.

Default policy, frozen with production:

| Measurement | Threshold |
|---|---|
| Black pixel | Luminance <= .003 |
| Near-black pixel | Luminance <= .015, including black pixels |
| Bright pixel / highlight | Luminance >= .60 |
| Required black fraction | >= .50 |
| Required near-black fraction | >= .70 |
| Maximum bright fraction | <= .15 |
| Minimum highlight coverage | >= .005 |

The result includes pixel count, black/near-black/bright fractions, mean luminance, a 256-bin histogram, median approximated to its histogram bin's lower edge, algorithm identity, full policy and warnings. `highlightCoverage` uses the same bright-pixel threshold; it is not a claim of semantic subject detection.

AMOLED_SUITABLE requires all four ratio checks. Otherwise AMOLED_BORDERLINE requires near-black fraction >= 80% of its minimum and bright fraction <= 125% of its maximum. Remaining images are NOT_AMOLED. Borderline and failed images stop automatic wallpaper acceptance; a uniformly black image does not qualify simply because its mean is low. Thresholds can be changed in a versioned wallpaper profile for new productions.

TASK-04 still evaluates anatomy, unwanted text, artifacts and composition. Pixel statistics cannot prove meaningful highlights, subject separation, absence of crushed details or device battery savings. Review difficult images visually. No aggressive black-point/contrast adjustment is enabled; neural models may still change near-black detail, which is why the processed master is analyzed again.

The live mock AMOLED fixture qualified after GPU processing with black fraction 0.956431, near-black 0.956463, mean luminance 0.034254 and bright fraction 0.032683. These are fixture measurements, not calibrated acceptance rates for real artwork or Android screens.

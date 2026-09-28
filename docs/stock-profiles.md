# Stock profiles

All values below are local version-1 configuration, not universal marketplace requirements. Historical productions/exports reference exact immutable versions. Creating a later version never changes an existing production.

| Profile | Minimum MP | Dimensions | Format / color | Max file | Processing | Enabled |
|---|---:|---|---|---:|---|---|
| STOCK_GENERIC | 4 | 1000–8192 px each side; max 64 MP | JPEG / sRGB / opaque | 50 MiB | STOCK_MASTER, quality 95 | Yes |
| STOCK_HIGH_QUALITY | 8 | Same | Same | 50 MiB | STOCK_MASTER_HQ, quality 98, floor 95 | Yes |
| STOCK_CUSTOM | 4 | Same | Same | 50 MiB | STOCK_MASTER, quality 95 | Yes |
| STOCK_ADOBE | 4 provisional | Same provisional defaults | Same | 50 MiB | STOCK_MASTER | **No: requires platform review** |

STOCK_ADOBE is an explicitly disabled configuration placeholder. No Adobe-specific uploader or CSV adapter is claimed. Review current platform requirements and publish a reviewed profile/adapter before use.

All seeded profiles: title 5–180 characters; description 10–1000; 10–49 ranked unique keywords; English; category required; explicit AI disclosure; default content classification UNDETERMINED; stock QA policy and STOCK_STRICT similarity. Internal taxonomy: ANIMALS, TECHNOLOGY, NATURE, BUSINESS, PEOPLE, ABSTRACT, FOOD, TRAVEL, SCIENCE.

Generation is seeded at 2048 × 2048. TASK-06 preserves composition and chooses the smallest necessary neural factor (1, 2 or 4). Generic sources already at 4.194304 MP skip neural upscale. High-quality 8 MP minimum from that source selects 2×; that produces 16.777216 MP without unnecessary cropping. Larger-than-supported targets fail explicitly.

StockPlatformRequirements validates future versions within implementation bounds (JPEG/sRGB, 64 MP decode bound, 64 MiB per-image storage bound, 8192 px per side). These safety bounds are implementation limits; profile values may be stricter. Processing-profile versions are resolved/frozen at production start alongside QA policy and prompt version references.

Metadata/processing decisions must be inspected for content suitability; numeric thresholds alone do not establish commercial rights or acceptance.

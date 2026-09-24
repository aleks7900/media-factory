# Stock-oriented processing

STOCK_STANDARD preserves composition and requires at least 4,000,000 output pixels. STOCK_4K additionally requires both dimensions to be at least 2400. The latter name denotes the preset family, not an exact 4096-pixel canvas. Both select native neural upscale only when needed, preserve aspect, emit high-quality JPEG with sRGB ICC, and enforce a 20 MiB size limit with quality at least 88.

Validation uses integer pixel products against the configured threshold: 2000×2000 is valid at 4 MP; 1999×2000 is invalid. Display rounding never controls acceptance. Decoding, format, dimensions, channels, ICC presence, byte size and checksum are recorded for each derivative.

These are technical output checks, not a guarantee of acceptance by any stock platform. They do not establish licensing, release forms, commercial suitability or visual quality. Optional postprocessing visual QA can use the existing TASK-04 engine when explicitly enabled.

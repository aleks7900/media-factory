# Stock technical validation

StockTechnicalValidator decodes the final TASK-06 derivative using ImageIO and returns individual PASS/WARNING/FAIL checks. It verifies SHA-256, file size, supported format, exact dimensions, unrounded megapixels (`width * height / 1,000,000`), orientation, decoded sRGB color model, alpha policy, readable pixels and JPEG end-of-image marker. Decode is bounded to 64 million pixels and 8192 px per side.

A 2000 × 2000 image is exactly 4 MP; 1999 × 2000 is below 4 MP and fails a 4 MP profile. Display rounding never controls eligibility.

Compression quality is an encoder provenance value, not a quantity that can be recovered exactly from decoded pixels. Missing encoder quality produces a warning. A value below the preferred quality produces a warning; unacceptable format/integrity/dimensions are failures. Profile constraints are independent of marketplace branding.

Technical evidence is append-only and references the exact stock variant and profile version. Export re-decodes and rechecks the frozen binary/checksum. Originals are never rewritten. Visual defects, anatomy, text/logo/watermark concerns remain TASK-04's responsibility; this component does not duplicate Vision QA or claim to establish IP clearance.

Stock visual QA keeps its human gate. The processing profile preserves geometry, strips public metadata and uses sRGB; technical checks do not prove semantic quality or legal suitability. Source AI status and provider/model provenance are retained in manifests.

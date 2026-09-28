# Stock metadata

`STOCK_METADATA` template v1 is stored in the TASK-03 Prompt Engine (version UUID `00000000-0000-0000-0000-000000000802`). The `stock-metadata` pipeline adds no image-composition constraints. Variables include language, final-image observations/QA, concept context and frozen stock requirements. Resolution records preserve template/version, any eligible experiment/variant, canonical prompt and assignment key.

Final processed JPEG bytes are authoritative. The current free Vision adapter decodes those bytes and computes sampled mean RGB, dominant color and orientation. It labels its output as a mock, with no claim of object recognition. Existing source QA findings are included as review evidence. Checksum/provider/model keyed observation caching prevents repeated equivalent calls after metadata edits. Text cache identity includes final checksum, resolved prompt/version/context, profile, scope and provider/model identity.

The mock text provider produces deterministic abstract titles/descriptions, ordered keywords, ABSTRACT category, AI disclosure and UNDETERMINED classification. These fixtures exercise the full workflow without cost; they are not professional semantic captions for arbitrary real images. Real semantic metadata quality requires a suitable provider implementation and evaluation. Provider-specific response handling stays in the provider layer.

StockMetadataVersion is append-only. GENERATED, EDITED and APPROVED are stored creation types. Earlier versions are presented as SUPERSEDED when they are no longer current, without altering their historical contents. Human changes record actor, time and previous version. Partial regeneration merges only TITLE, DESCRIPTION or KEYWORDS; ALL replaces generated metadata. Each request creates a new version even when inference is reused from cache.

Metadata validation exposes title/description bounds, counts, uniqueness, lexical relevance warnings, category validity, forbidden/unsupported claim terms, classification and explicit AI disclosure. Duplicate-title/near-title/identical-keyword-set warnings compare current metadata within the collection. Warnings require explicit acknowledgement before approval; failures block it.

IP flags (for example visible logo, trademark-like text, copyright risk, unknown IP risk) are review signals, not legal conclusions. Human edits preserve existing flags. Content with unresolved flags cannot be automatically marked COMMERCIAL. Default classification is UNDETERMINED.

Each Vision/text attempt writes existing generation_costs with operation, provider/model, usage, estimated cost/currency and outcome. Failed/unknown operations remain visible; cache hits do not fabricate new provider usage. Current mock operations have zero estimated USD cost. Source generation, visual QA and external processing costs remain attributable to their production/collection.

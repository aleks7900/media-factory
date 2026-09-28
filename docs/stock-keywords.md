# Ranked stock keywords

Keywords retain the relevance order returned by the metadata provider or supplied by the reviewer. The implementation does not sort alphabetically or pad the list to its maximum. Every saved keyword has a one-based rank, original display value, normalized value, source and optional confidence.

Normalization uses Unicode NFKC, lower casing, whitespace collapse and punctuation cleanup. Useful multi-word phrases remain intact. Deduplication retains the first ranked occurrence. A small explicit alias set handles `wolves/wolf`, `people/person` and `children/child`; this is not a general linguistic stemming system. Duplicate removal precedes the configured maximum-count truncation. All initial profiles require 10–49 keywords.

The review editor supports drag/drop, move up/down, add, remove and manual edits. Saving appends a metadata version and requires fresh approval. Keyword provenance distinguishes VISION, LLM, CONCEPT, PROMPT and MANUAL; manually altered keywords are marked MANUAL. Regenerating KEYWORDS preserves the existing title, description and classification.

Metadata QA exposes count, uniqueness and basic observation relevance issues individually. Collection-level identical keyword sets produce a review warning. Pixel-only mock observations cannot establish semantic relevance, identify people or assess intellectual-property rights. Mock metadata is explicitly flagged for human review; production quality needs a suitable Vision/Text provider behind the existing ports and measured evaluations.

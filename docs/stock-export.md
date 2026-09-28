# Immutable stock export

An export request freezes the export profile version, production IDs, derivative IDs, metadata versions, filenames and provenance. Selection may be explicit or collection-based. Incremental collection export omits derivative/metadata pairs already included in a completed export. Requests require an idempotency key; reusing it with changed input conflicts.

The request only queues work. A leased background worker rechecks visual QA, current strict similarity, derivative validation, exact approved metadata and real file checksums. STRICT fails the entire batch when any item is ineligible. VALID_ONLY records skipped items and exports the eligible remainder; an empty remainder always fails. TASK-05 blocks unresolved exact/perceptual/near duplicates; the batch also rejects repeated source checksums.

Initial profiles cap a package at 500 images and 512 MiB of image data. ZIP/manifest overhead has a separate 8 MiB allowance. The worker decodes one image at a time, writes its ZIP in a dedicated temporary directory, and removes temporary files on success or failure. The current MediaStorage byte-array interface requires the bounded ZIP to fit in heap when uploaded/downloaded. A future streaming storage interface should replace this for substantially larger packages.

Package structure:

```text
metadata.csv
manifest.json
validation-report.json
images/<title-slug>-<production-uuid>-<metadata-uuid>.jpg
```

Filenames use an ASCII slug with complete production and metadata UUIDs. The CSV filename maps exactly to the basename under `images/`. Manifest schema `media-factory-stock-export/1` records frozen profiles, metadata versions, generation/processing lineage and prompt snapshots. Every image has SHA-256; manifest records CSV and validation-report SHA-256. Database records retain package and manifest checksums, with the package checksum outside the ZIP to avoid a self-reference.

Successful packages use unique storage keys and database immutability protections. Editing metadata resets approval; rebuilding creates a new export with a parent export reference. Earlier ZIP bytes remain downloadable unchanged. A queued export whose frozen metadata is no longer current fails rather than silently switching versions.

Workers recover expired leases with bounded attempts. Storage/lease failures can be retried; eligibility changes require a new export. An interrupted upload may leave an unreferenced immutable storage object, which is not published as a READY package. Storage retention/garbage collection is a future operational feature.

EXPORTED means package prepared, not submitted to or accepted by any marketplace. No direct marketplace upload or paid API call is made by export. Future marketplace submission belongs in a separate adapter.

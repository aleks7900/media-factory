# CSV imports

The generic adapter accepts UTF-8 CSV text with quoted delimiters and newlines. The API's column mapping profile decouples source headers from the domain. Required mapped keys: `externalId`, `type`, `value`, `occurredAt`. Optional: `currency`, `eventId`. A BOM is accepted. Limits are 2,000,000 input characters and 10,000 records per batch.

```json
{
  "source": "CSV_IMPORT",
  "platform": "MY_PLATFORM",
  "filename": "metrics.csv",
  "createdBy": "local-user",
  "columns": {"externalId":"asset","type":"metric","value":"amount","occurredAt":"time","currency":"currency","eventId":"event"},
  "csv": "asset,metric,amount,time,currency,event\nremote-123,REVENUE,2.50,2026-01-02T12:00:00Z,USD,sale-1\n"
}
```

`POST /imports` stages and validates without adding measurement facts. It returns mapped/valid, unmapped, invalid and duplicate row states plus expected valid revenue by original currency. `POST /imports/{id}/commit` imports valid rows independently; bad rows do not roll back valid rows. `POST /imports/{id}/retry` reattempts unresolved rows after mapping. GET `/imports` and `/imports/{id}` expose diagnostics.

Batch identity is source + platform + SHA-256 of the input text. Reusing a file with a different mapping is rejected. Row deduplication hashes platform, external ID, metric type, canonical decimal, normalized timestamp, currency and optional source event ID. Thus whitespace/numeric-format differences do not double a row, even in a different file. Source + semantic key is unique in PostgreSQL.

If two genuinely distinct events have equal asset/type/time/value, provide distinct `eventId` values. Without those IDs, equal records are intentionally considered duplicates. Prefer a stable source event identifier and consistent source name across repeated imports. Do not import overlapping cumulative reports as event increments; use `/snapshots` instead.

Unmapped rows remain staged for retry. Invalid dates/currencies/numbers are reported. Row errors do not log the complete CSV or credentials. Imports append facts and never modify original media or checksums. File-name, actor and source fields are user-supplied audit metadata in the local deployment.

The dashboard includes a generic fixed-header CSV editor and external-ID mapping form. Custom source header maps are available through the API. Platform-specific downloadable report profiles and automated remote polling are not bundled.

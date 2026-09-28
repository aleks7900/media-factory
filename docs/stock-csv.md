# CSV adapters

`StockExportAdapter` isolates platform formatting. `GenericStockExportAdapter` uses [Apache Commons CSV](https://commons.apache.org/proper/commons-csv/) to quote and escape fields; CSV is never assembled by concatenating rows.

Both seeded export profiles use UTF-8, CRLF records and these columns in order:

```text
filename,title,description,keywords,category,ai_generated,content_type
```

GENERIC_CSV uses comma-delimited columns and comma-space-delimited keywords inside one properly quoted field. CUSTOM_CSV uses semicolon columns and a vertical-bar keyword separator. The versioned profile can choose allowed columns, comma/semicolon/tab delimiter, keyword separator and internal-to-platform category mapping. `filename` is mandatory and headers cannot repeat. Initial category mapping is identity mapping through `StockCategoryMapper`.

Values containing commas, quotes, line breaks or Unicode round-trip through the parser. Spreadsheet formula prefixes are neutralized before CSV writing. AI generation is an explicit boolean field; content classification remains COMMERCIAL, EDITORIAL or UNDETERMINED, never inferred as legal clearance.

These are Media Factory schemas. They do not claim compatibility with a particular marketplace's current upload format. STOCK_ADOBE is disabled pending platform-requirement review; implementing an Adobe adapter requires confirming its actual accepted schema and disclosure requirements.

Component tests round-trip 50 rows with commas, quotes, Unicode and line breaks, validate category mapping and formula handling. Integration tests verify every CSV filename resolves to an exported JPEG and each binary checksum matches the manifest.

package com.mediafactory.stock;

import static com.mediafactory.processing.ProcessingJson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Component;

@Component
public class GenericStockExportAdapter implements StockExportAdapter {
  public String platformId() {
    return "GENERIC_CSV";
  }

  public byte[] csv(List<Map<String, Object>> items, Map<String, Object> profile) {
    String delimiter = profile.getOrDefault("delimiter", ",").toString();
    if (delimiter.length() != 1 || !Set.of(",", ";", "\t").contains(delimiter))
      throw new IllegalArgumentException("Unsupported CSV delimiter");
    var columns = ((List<?>) profile.get("columns")).stream().map(Object::toString).toList();
    if (!columns.contains("filename")
        || new HashSet<>(columns).size() != columns.size()
        || !Set.of(
                "filename",
                "title",
                "description",
                "keywords",
                "category",
                "ai_generated",
                "content_type")
            .containsAll(columns)) throw new IllegalArgumentException("Invalid CSV columns");
    try (var writer = new StringWriter();
        var csv =
            new CSVPrinter(
                writer,
                CSVFormat.RFC4180
                    .builder()
                    .setDelimiter(delimiter.charAt(0))
                    .setHeader(columns.toArray(String[]::new))
                    .get())) {
      for (var item : items) {
        var data = map(item.get("metadata"));
        var row = new LinkedHashMap<String, Object>();
        row.put("filename", item.get("filename"));
        row.put("title", data.get("title"));
        row.put("description", data.getOrDefault("description", ""));
        row.put(
            "keywords",
            String.join(
                profile.getOrDefault("keywordSeparator", ", ").toString(),
                ((List<Map<String, Object>>) data.get("keywords"))
                    .stream().map(k -> k.get("value").toString()).toList()));
        row.put(
            "category",
            String.join(
                "|",
                ((List<?>) data.get("categories"))
                    .stream()
                        .map(
                            c ->
                                StockCategoryMapper.map(
                                    c.toString(),
                                    map(profile.getOrDefault("categoryMapping", Map.of()))))
                        .toList()));
        row.put("ai_generated", data.get("aiGenerated"));
        row.put("content_type", data.get("contentType"));
        csv.printRecord(columns.stream().map(c -> safe(Objects.toString(row.get(c), ""))).toList());
      }
      csv.flush();
      return writer.toString().getBytes(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String safe(String s) {
    return s.stripLeading().matches("^[=+@\\-].*") ? "'" + s : s;
  }
}

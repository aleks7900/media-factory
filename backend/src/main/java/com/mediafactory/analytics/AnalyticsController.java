package com.mediafactory.analytics;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

  private final AnalyticsAggregationService aggregation;
  private final AnalyticsIngestionService ingestion;
  private final AnalyticsImportService imports;
  private final CostAttributionPolicy attribution;
  private final AnalyticsDetailService details;
  private final AnalyticsJobs jobs;
  private final AnalyticsExportService exports;
  private final AnalyticsDataQualityService quality;

  public AnalyticsController(
      AnalyticsAggregationService aggregation,
      AnalyticsIngestionService ingestion,
      AnalyticsImportService imports,
      CostAttributionPolicy attribution,
      AnalyticsDetailService details,
      AnalyticsJobs jobs,
      AnalyticsExportService exports,
      AnalyticsDataQualityService quality) {
    this.aggregation = aggregation;
    this.ingestion = ingestion;
    this.imports = imports;
    this.attribution = attribution;
    this.details = details;
    this.jobs = jobs;
    this.exports = exports;
    this.quality = quality;
  }

  @GetMapping({
      "/overview",
      "/assets",
      "/collections",
      "/prompts",
      "/providers",
      "/platforms",
      "/costs",
      "/revenue",
      "/timeseries",
      "/experiments"
  })
  public Object query(
      @RequestParam Map<String, String> p, jakarta.servlet.http.HttpServletRequest request) {
    String route = request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1);
    String dimension =
        switch (route) {
          case "overview", "costs", "revenue" -> "overview";
          case "collections" -> "collection";
          case "prompts" -> "prompt";
          case "providers" -> "provider";
          case "platforms" -> "platform";
          case "timeseries" -> "date";
          case "experiments" -> "variant";
          default -> "asset";
        };
    return aggregation.query(queryModel(p, dimension));
  }

  private AnalyticsQuery queryModel(Map<String, String> p, String dimension) {
    return new AnalyticsQuery(
        uuid(p, "projectId"),
        uuid(p, "collectionId"),
        uuid(p, "assetId"),
        p.get("provider"),
        p.get("model"),
        p.get("platform"),
        instant(p, "from"),
        instant(p, "to"),
        p.get("currency"),
        p.get("timezone"),
        p.getOrDefault("groupBy", dimension),
        p.getOrDefault("sort", "cost"),
        !"asc".equals(p.get("direction")),
        Integer.parseInt(p.getOrDefault("page", "0")),
        Integer.parseInt(p.getOrDefault("size", "50")),
        p);
  }

  @GetMapping(value = "/export", produces = "text/csv")
  public org.springframework.http.ResponseEntity<String> export(
      @RequestParam Map<String, String> p) {
    return org.springframework.http.ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=media-factory-analytics.csv")
        .body(exports.export(queryModel(p, "asset")));
  }

  private UUID uuid(Map<String, String> p, String k) {
    return p.containsKey(k) ? UUID.fromString(p.get(k)) : null;
  }

  private Instant instant(Map<String, String> p, String k) {
    return p.containsKey(k) ? Instant.parse(p.get(k)) : null;
  }

  @PostMapping("/events")
  public Object event(@RequestBody AnalyticsIngestionService.Event e) {
    return ingestion.event(e);
  }

  @PostMapping("/references")
  public Object reference(@RequestBody AnalyticsIngestionService.Reference r) {
    return ingestion.reference(r);
  }

  @PostMapping("/snapshots")
  public Object snapshot(@RequestBody AnalyticsIngestionService.Snapshot s) {
    return ingestion.snapshot(s);
  }

  @PostMapping("/currency-rates")
  public Object rate(@RequestBody AnalyticsIngestionService.Rate r) {
    return ingestion.rate(r);
  }

  @PostMapping("/cost-allocations")
  public Object allocate(@RequestBody CostAttributionPolicy.Allocation r) {
    return attribution.allocate(r);
  }

  @GetMapping("/assets/{id}")
  public Object asset(@PathVariable UUID id, @RequestParam(defaultValue = "USD") String currency) {
    return details.asset(id, currency);
  }

  @GetMapping("/provider-operations")
  public Object providerOperations() {
    return details.providerOperations();
  }

  @GetMapping("/processing")
  public Object processing() {
    return details.processing();
  }

  @GetMapping("/jobs")
  public Object jobs() {
    return jobs.list();
  }

  @PostMapping("/jobs/{type}")
  public Object enqueue(
      @PathVariable String type,
      @RequestBody Map<String, Object> payload,
      @RequestHeader("Idempotency-Key") String key) {
    return jobs.enqueue(type, payload, key);
  }

  @GetMapping("/diagnostics")
  public Object diagnostics() {
    return aggregation.diagnostics();
  }

  @GetMapping("/data-quality")
  public Object quality() {
    return quality.report();
  }

  @PostMapping("/rebuild")
  public Object rebuild(@RequestBody Map<String, String> r) {
    return aggregation.rebuild(r.get("reason"), r.get("createdBy"), AnalyticsRebuildScope.parse(r));
  }

  @PostMapping("/imports")
  public Object preview(@RequestBody AnalyticsImportService.Request r) {
    return imports.preview(r);
  }

  @GetMapping("/imports")
  public Object imports() {
    return imports.list();
  }

  @GetMapping("/imports/{id}")
  public Object detail(@PathVariable UUID id) {
    return imports.detail(id);
  }

  @PostMapping({"/imports/{id}/commit", "/imports/{id}/retry"})
  public Object commit(@PathVariable UUID id) {
    return imports.commit(id);
  }
}

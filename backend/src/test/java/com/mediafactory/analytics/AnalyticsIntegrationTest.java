package com.mediafactory.analytics;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
@SpringBootTest(
    properties = "media.worker.enabled=false",
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalyticsIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
  @Autowired
  JdbcClient db;
  @Autowired
  AnalyticsIngestionService ingestion;
  @Autowired
  AnalyticsAggregationService aggregation;
  @Autowired
  AnalyticsImportService imports;
  @Autowired
  CostAttributionPolicy attribution;
  @Autowired
  AnalyticsJobs jobs;
  @Autowired
  AnalyticsDetailService details;
  @Autowired
  AnalyticsExportService exports;
  @org.springframework.boot.test.web.server.LocalServerPort
  int port;
  UUID asset, generation, collection;
  Instant at = Instant.parse("2026-01-02T12:00:00Z");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void setup() {
    db.sql(
            "truncate"
                + " projects,analytics_import_batches,analytics_currency_rates,analytics_rebuild_runs,analytics_jobs"
                + " cascade")
        .update();
    UUID project = UUID.randomUUID(), concept = UUID.randomUUID();
    collection = UUID.randomUUID();
    generation = UUID.randomUUID();
    asset = UUID.randomUUID();
    db.sql("insert into projects(id,name) values(?,'Analytics')").param(project).update();
    db.sql("insert into collections(id,project_id,name) values(?,?,'Economics')")
        .params(collection, project)
        .update();
    db.sql("insert into concepts(id,collection_id,name,prompt) values(?,?,'Fixture','Fixture')")
        .params(concept, collection)
        .update();
    db.sql(
            "insert into generations(id,concept_id,status,prompt,width,height,created_at)"
                + " values(?,?,'APPROVED','Fixture',64,64,'2026-01-01')")
        .params(generation, concept)
        .update();
    db.sql(
            "insert into"
                + " assets(id,generation_id,storage_key,sha256,media_type,size_bytes,width,height)"
                + " values(?,?,?,?,'image/png',1,64,64)")
        .params(asset, generation, asset.toString(), "0".repeat(64))
        .update();
    ingestion.reference(
        new AnalyticsIngestionService.Reference(asset, null, null, "TEST", "fixture-source", null));
    db.sql(
            "insert into"
                + " generation_costs(id,generation_id,attempt,provider,model,operation,input_usage,output_usage,estimated_cost,currency,created_at)"
                + " values(?,?,1,'mock','mock','IMAGE',0,0,0.20,'USD','2026-01-02')")
        .params(UUID.randomUUID(), generation)
        .update();
    aggregation.rebuild("test reset", "test");
  }

  AnalyticsIngestionService.Event event(String key, String type, String value, String currency) {
    return new AnalyticsIngestionService.Event(
        asset,
        null,
        null,
        "TEST",
        type,
        new BigDecimal(value),
        currency,
        at,
        "API",
        key,
        null,
        "Test fixture",
        "test",
        Map.of());
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> overview() {
    return ((List<Map<String, Object>>)
        aggregation
            .query(
                new AnalyticsQuery(
                    null,
                    collection,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "USD",
                    "UTC",
                    "overview",
                    "cost",
                    true,
                    0,
                    50))
            .get("rows"))
        .getFirst();
  }

  @Test
  void duplicatesCorrectionsAndRebuildPreserveExactEconomics() {
    var e = event("sale", "REVENUE", "0.30", "USD");
    var first = ingestion.event(e);
    assertThat(ingestion.event(e).get("duplicate")).isEqualTo(true);
    assertThatThrownBy(() -> ingestion.event(event("sale", "REVENUE", "0.31", "USD")))
        .isInstanceOf(IllegalArgumentException.class);
    ingestion.event(
        new AnalyticsIngestionService.Event(
            asset,
            null,
            null,
            "TEST",
            "REVENUE",
            new BigDecimal("-0.05"),
            "USD",
            at,
            "API",
            "correction",
            (UUID) first.get("id"),
            "Partial correction",
            "test",
            Map.of()));
    aggregation.rebuild("fixture", "test");
    var one = overview();
    assertThat((BigDecimal) one.get("revenue")).isEqualByComparingTo("0.25");
    assertThat((BigDecimal) one.get("profit")).isEqualByComparingTo("0.05");
    aggregation.rebuild("repeat", "test");
    assertThat(overview()).isEqualTo(one);
    assertThatThrownBy(() -> db.sql("update performance_metrics set value=0").update())
        .hasMessageContaining("immutable");
  }

  @Test
  void missingRevenueAndExchangeRateAreUnknown() {
    assertThat(overview().get("revenue")).isNull();
    ingestion.event(event("euro", "REVENUE", "1", "EUR"));
    aggregation.rebuild("fixture", "test");
    assertThat(overview().get("profit")).isNull();
    assertThat(((Number) overview().get("missing_money_facts")).intValue()).isEqualTo(1);
    ingestion.rate(
        new AnalyticsIngestionService.Rate(
            "EUR",
            "USD",
            java.time.LocalDate.parse("2026-01-02"),
            new BigDecimal("1.1"),
            "manual fixture",
            "test"));
    assertThat((BigDecimal) overview().get("profit")).isEqualByComparingTo("0.9");
  }

  @Test
  void outOfOrderSnapshotsAndResetsDoNotFabricateNegativeEngagement() {
    UUID ref =
        (UUID)
            ingestion
                .reference(
                    new AnalyticsIngestionService.Reference(
                        asset, null, null, "TEST", "remote", null))
                .get("id");
    snapshot(ref, 0, "100");
    snapshot(ref, 2, "150");
    snapshot(ref, 1, "120");
    snapshot(ref, 3, "10");
    assertThat(
        db.sql("select sum(delta) from analytics_snapshot_deltas")
            .query(BigDecimal.class)
            .single())
        .isEqualByComparingTo("50");
    assertThat(
        db.sql("select count(*) from analytics_snapshot_deltas where quality='COUNTER_RESET'")
            .query(Long.class)
            .single())
        .isEqualTo(1L);
    assertThatThrownBy(() -> ingestion.event(event("mixed", "DOWNLOAD", "1", null)))
        .hasMessageContaining("snapshot stream");
  }

  void snapshot(UUID ref, int day, String value) {
    ingestion.snapshot(
        new AnalyticsIngestionService.Snapshot(
            ref,
            "DOWNLOAD",
            new BigDecimal(value),
            null,
            at.plusSeconds(day * 86400L),
            "API",
            "snap" + day,
            "UNKNOWN",
            "Counter fixture",
            "test"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void quotedCsvDryRunDeduplicationAndUnmappedRetry() {
    var request =
        new AnalyticsImportService.Request(
            "CSV_IMPORT",
            "TEST",
            "測定.csv",
            "external,type,value,time,currency\n"
                + "\"remote,one\",REVENUE,2.50,2026-01-02T12:00:00Z,USD\n",
            Map.of(
                "externalId",
                "external",
                "type",
                "type",
                "value",
                "value",
                "occurredAt",
                "time",
                "currency",
                "currency"),
            "test");
    var preview = (Map<String, Object>) imports.preview(request);
    UUID batch = (UUID) ((Map<String, Object>) preview.get("batch")).get("id");
    assertThat(db.sql("select count(*) from performance_metrics").query(Long.class).single())
        .isZero();
    imports.commit(batch);
    assertThat(db.sql("select status from analytics_import_rows").query(String.class).single())
        .isEqualTo("UNMAPPED");
    ingestion.reference(
        new AnalyticsIngestionService.Reference(asset, null, null, "TEST", "remote,one", null));
    imports.commit(batch);
    imports.commit(batch);
    imports.preview(request);
    assertThat(
        db.sql("select sum(value) from performance_metrics").query(BigDecimal.class).single())
        .isEqualByComparingTo("2.50");
    assertThat(db.sql("select count(*) from analytics_import_batches").query(Long.class).single())
        .isEqualTo(1L);
  }

  @Test
  void failedGenerationCostStaysInCollection() {
    UUID failed = UUID.randomUUID();
    db.sql(
            "insert into generations(id,concept_id,status,prompt,width,height) select"
                + " ?,concept_id,'FAILED',prompt,64,64 from generations where id=?")
        .params(failed, generation)
        .update();
    db.sql(
            "insert into"
                + " generation_costs(id,generation_id,attempt,provider,model,operation,input_usage,output_usage,estimated_cost,currency)"
                + " values(?,?,1,'mock','mock','IMAGE',0,0,0.80,'USD')")
        .params(UUID.randomUUID(), failed)
        .update();
    assertThat((BigDecimal) overview().get("cost_per_approved")).isEqualByComparingTo("1.00");
  }

  @Test
  void allocationPreservesTotalAndIsIdempotent() {
    UUID cost = db.sql("select id from generation_costs").query(UUID.class).single();
    db.sql("update generation_costs set outcome='SUCCEEDED' where id=?").param(cost).update();
    var request =
        new CostAttributionPolicy.Allocation(
            cost, "DIRECT", Map.of(asset, BigDecimal.ONE), "Direct attribution", "test");
    attribution.allocate(request);
    attribution.allocate(request);
    assertThat(
        db.sql("select sum(amount) from analytics_attributed_costs")
            .query(BigDecimal.class)
            .single())
        .isEqualByComparingTo("0.20");
    assertThat(overview().get("cost")).isEqualTo(new BigDecimal("0.200000000000"));
  }

  @Test
  void jobReplayIsIdempotentAndHttpFiltersAreValidated() throws Exception {
    jobs.enqueue(
        "REBUILD", Map.of("reason", "Fixture rebuild", "createdBy", "test"), "rebuild-one");
    jobs.enqueue(
        "REBUILD", Map.of("reason", "Fixture rebuild", "createdBy", "test"), "rebuild-one");
    jobs.runOne();
    jobs.runOne();
    assertThat(db.sql("select status from analytics_jobs").query(String.class).single())
        .isEqualTo("SUCCEEDED");
    var client = java.net.http.HttpClient.newHttpClient();
    var response =
        client.send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://localhost:" + port + "/api/v1/analytics/overview"))
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("cost_per_approved");
    response =
        client.send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(
                        "http://localhost:" + port + "/api/v1/analytics/overview?grain=invalid"))
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void exportsAndAssetDetailsExposeTraceableFacts() {
    ingestion.event(event("revenue", "REVENUE", "2.50", "USD"));
    aggregation.rebuild("Fixture", "test");
    assertThat(details.asset(asset).toString()).contains("lineage", "cohorts", "costs");
    var csv =
        exports.export(
            new AnalyticsQuery(
                null,
                collection,
                null,
                null,
                null,
                null,
                null,
                null,
                "USD",
                "UTC",
                "asset",
                "cost",
                true,
                0,
                50));
    assertThat(csv)
        .contains("generatedAt", "baseCurrency,USD", "fromInclusive", asset.toString(), "2.50");
    assertThat(AnalyticsExportService.safe("=HYPERLINK(\"x\")")).isEqualTo("'=HYPERLINK(\"x\")");
  }

  @Test
  void multiPlatformMetricsRollUpOnceAndDoNotRepeatCosts() {
    ingestion.reference(
        new AnalyticsIngestionService.Reference(
            asset, null, null, "SECOND", "fixture-source", null));
    ingestion.event(event("platform-one", "REVENUE", "2", "USD"));
    ingestion.event(
        new AnalyticsIngestionService.Event(
            asset,
            null,
            null,
            "SECOND",
            "REVENUE",
            new BigDecimal("3"),
            "USD",
            at,
            "API",
            "platform-two",
            null,
            "Second platform",
            "test",
            Map.of()));
    aggregation.rebuild("Fixture", "test");
    assertThat((BigDecimal) overview().get("revenue")).isEqualByComparingTo("5");
    assertThat((BigDecimal) overview().get("cost")).isEqualByComparingTo("0.20");
    var platform =
        aggregation.query(
            new AnalyticsQuery(
                null,
                collection,
                null,
                null,
                null,
                "SECOND",
                null,
                null,
                "USD",
                "UTC",
                "platform",
                "cost",
                true,
                0,
                50));
    assertThat(platform.toString()).contains("cost=null", "revenue=3");
  }

  @Test
  void halfOpenTimezoneBoundariesExcludeNextDay() {
    ingestion.event(event("boundary", "VIEW", "7", null));
    aggregation.rebuild("Fixture", "test");
    var q =
        new AnalyticsQuery(
            null,
            collection,
            null,
            null,
            null,
            null,
            at.minusSeconds(1),
            at,
            "USD",
            "Europe/Bucharest",
            "overview",
            "cost",
            true,
            0,
            50);
    assertThat(aggregation.query(q).get("rows").toString()).doesNotContain("views=7");
    q =
        new AnalyticsQuery(
            null,
            collection,
            null,
            null,
            null,
            null,
            at,
            at.plusSeconds(1),
            "USD",
            "Europe/Bucharest",
            "overview",
            "cost",
            true,
            0,
            50);
    assertThat(aggregation.query(q).get("rows").toString()).contains("views=7");
  }

  @Test
  @SuppressWarnings("unchecked")
  void hundredAssetAcceptanceFixtureAndImmutableExperimentAttribution() {
    UUID experiment = UUID.randomUUID(), variantA = UUID.randomUUID(), variantB = UUID.randomUUID();
    db.sql(
            "insert into prompt_experiments(id,name,scope,collection_id) values(?,'Analytics"
                + " A/B','COLLECTION',?)")
        .params(experiment, collection)
        .update();
    db.sql(
            "insert into"
                + " prompt_experiment_variants(id,experiment_id,key,name,prompt_version_id,weight)"
                + " values(?,?,'A','A','00000000-0000-0000-0000-000000000802',5000),(?,?,'B','B','00000000-0000-0000-0000-000000000804',5000)")
        .params(variantA, experiment, variantB, experiment)
        .update();
    db.sql(
            """
                insert into generations(id,concept_id,status,prompt,width,height,created_at,final_provider,model,prompt_version_id,experiment_id,experiment_variant_id)
                select md5('acceptance-gen-'||n)::uuid,concept_id,case when n<=70 then 'APPROVED' else 'REJECTED' end,'Acceptance',64,64,'2026-01-01',
                 'ProviderA','ModelB',case when n<=50 then '00000000-0000-0000-0000-000000000802'::uuid else '00000000-0000-0000-0000-000000000804'::uuid end,
                 cast(? as uuid),case when n<=50 then cast(? as uuid) else cast(? as uuid) end
                from generate_series(1,100) n cross join (select concept_id from generations limit 1) c
                """)
        .params(experiment, variantA, variantB)
        .update();
    db.sql(
            "insert into"
                + " assets(id,generation_id,storage_key,sha256,media_type,size_bytes,width,height)"
                + " select"
                + " md5('acceptance-asset-'||n)::uuid,md5('acceptance-gen-'||n)::uuid,'acceptance/'||n,repeat('0',64),'image/png',1,64,64"
                + " from generate_series(1,100) n")
        .update();
    db.sql(
            "insert into quality_reviews(id,asset_id,generation_id,kind,decision,final_decision)"
                + " select"
                + " md5('acceptance-review-'||n)::uuid,md5('acceptance-asset-'||n)::uuid,md5('acceptance-gen-'||n)::uuid,'HUMAN',case"
                + " when n<=70 then 'APPROVED' else 'REJECTED' end,case when n<=70 then 'APPROVED'"
                + " else 'REJECTED' end from generate_series(1,100) n")
        .update();
    db.sql(
            "update assets a set current_review_id=r.id from quality_reviews r where"
                + " r.asset_id=a.id")
        .update();
    db.sql(
            "insert into"
                + " quality_findings(id,review_id,category,code,severity,confidence,detected,source,evidence)"
                + " select gen_random_uuid(),md5('acceptance-review-'||n)::uuid,case when n>90 then"
                + " 'DUPLICATE' else 'LOW_QUALITY' end,case when n>90 then 'EXACT_DUPLICATE' else"
                + " 'ARTIFACT' end,'MAJOR',1,true,'HUMAN','Acceptance fixture' from"
                + " generate_series(71,100) n")
        .update();
    db.sql(
            "insert into"
                + " generation_costs(id,generation_id,attempt,provider,model,operation,input_usage,output_usage,estimated_cost,currency,created_at)"
                + " select"
                + " gen_random_uuid(),md5('acceptance-gen-'||n)::uuid,1,'ProviderA','ModelB','IMAGE',0,0,0.1,'USD','2026-01-01'"
                + " from generate_series(1,100) n")
        .update();
    db.sql(
            "insert into publications(id,asset_id,channel,published_at) select"
                + " md5('acceptance-pub-'||n)::uuid,md5('acceptance-asset-'||n)::uuid,'TEST_PLATFORM','2026-01-02'"
                + " from generate_series(1,50) n")
        .update();
    db.sql(
            "insert into"
                + " performance_metrics(id,asset_id,publication_id,platform,name,value,currency,measured_at,source,deduplication_key,reason,created_by)"
                + " select"
                + " gen_random_uuid(),md5('acceptance-asset-'||n)::uuid,md5('acceptance-pub-'||n)::uuid,'TEST_PLATFORM','REVENUE',1,'USD','2026-01-03','TEST_FIXTURE',n::text,'Acceptance"
                + " fixture','test' from generate_series(1,50) n")
        .update();
    db.sql(
            "update prompt_experiment_variants set weight=case when key='A' then 9000 else 1000 end"
                + " where experiment_id=?")
        .param(experiment)
        .update();
    aggregation.rebuild("Acceptance fixture", "test");
    var q =
        new AnalyticsQuery(
            null,
            collection,
            null,
            "ProviderA",
            null,
            null,
            null,
            null,
            "USD",
            "UTC",
            "overview",
            "cost",
            true,
            0,
            50);
    var row = ((List<Map<String, Object>>) aggregation.query(q).get("rows")).getFirst();
    assertThat(((Number) row.get("generated")).intValue()).isEqualTo(100);
    assertThat(((Number) row.get("approved")).intValue()).isEqualTo(70);
    assertThat(((Number) row.get("published")).intValue()).isEqualTo(50);
    assertThat((BigDecimal) row.get("cost")).isEqualByComparingTo("10");
    assertThat((BigDecimal) row.get("revenue")).isEqualByComparingTo("50");
    assertThat((BigDecimal) row.get("profit")).isEqualByComparingTo("40");
    assertThat((BigDecimal) row.get("rejected_cost")).isEqualByComparingTo("3");
    assertThat((BigDecimal) row.get("duplicate_rejected_cost")).isEqualByComparingTo("1");
    q =
        new AnalyticsQuery(
            null,
            collection,
            null,
            "ProviderA",
            null,
            null,
            null,
            null,
            "USD",
            "UTC",
            "variant",
            "cost",
            true,
            0,
            50);
    var variants = (List<Map<String, Object>>) aggregation.query(q).get("rows");
    assertThat(variants).hasSize(2);
    assertThat(variants).allMatch(v -> ((Number) v.get("generated")).intValue() == 50);
    UUID firstAsset = db.sql("select md5('acceptance-asset-1')::uuid").query(UUID.class).single();
    var evidence = (Map<String, Object>) details.asset(firstAsset, "USD");
    assertThat(
        (BigDecimal)
            ((Map<String, Object>) evidence.get("cohorts")).get("revenue_first_30_days"))
        .isEqualByComparingTo("1");
  }

  @Test
  void scopedRebuildPreservesOtherDatesUntilTheirRefresh() {
    ingestion.event(event("day-one", "VIEW", "1", null));
    ingestion.event(
        new AnalyticsIngestionService.Event(
            asset,
            null,
            null,
            "TEST",
            "VIEW",
            new BigDecimal("2"),
            null,
            at.plusSeconds(86400),
            "API",
            "day-two",
            null,
            "Fixture",
            "test",
            Map.of()));
    aggregation.rebuild("All", "test");
    ingestion.event(event("day-one-extra", "VIEW", "3", null));
    ingestion.event(
        new AnalyticsIngestionService.Event(
            asset,
            null,
            null,
            "TEST",
            "VIEW",
            new BigDecimal("4"),
            null,
            at.plusSeconds(86400),
            "API",
            "day-two-extra",
            null,
            "Fixture",
            "test",
            Map.of()));
    aggregation.rebuild(
        "Only first date",
        "test",
        new AnalyticsRebuildScope(
            asset,
            null,
            java.time.LocalDate.parse("2026-01-02"),
            java.time.LocalDate.parse("2026-01-03")));
    assertThat(
        db.sql("select sum(value) from analytics_daily_aggregate where day='2026-01-02'")
            .query(BigDecimal.class)
            .single())
        .isEqualByComparingTo("4");
    assertThat(
        db.sql("select sum(value) from analytics_daily_aggregate where day='2026-01-03'")
            .query(BigDecimal.class)
            .single())
        .isEqualByComparingTo("2");
    aggregation.rebuild(
        "Collection refresh", "test", new AnalyticsRebuildScope(null, collection, null, null));
    assertThat(
        db.sql("select sum(value) from analytics_daily_aggregate")
            .query(BigDecimal.class)
            .single())
        .isEqualByComparingTo("10");
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
      named = "ANALYTICS_BENCHMARK",
      matches = "true")
  void representativeVolumeBenchmark() throws Exception {
    db.sql(
            """
                insert into generations(id,concept_id,status,prompt,width,height,created_at,final_provider,model)
                select md5('bench-generation-'||n)::uuid,concept_id,'APPROVED','Benchmark',64,64,'2026-01-01','mock','fixture'
                from generate_series(1,10000) n cross join (select concept_id from generations limit 1) c
                """)
        .update();
    db.sql(
            """
                insert into assets(id,generation_id,storage_key,sha256,media_type,size_bytes,width,height)
                select md5('bench-asset-'||n)::uuid,md5('bench-generation-'||n)::uuid,'bench/'||n,repeat('0',64),'image/png',1,64,64
                from generate_series(1,10000) n
                """)
        .update();
    long ingest = System.nanoTime();
    db.sql(
            """
                insert into performance_metrics(id,asset_id,platform,name,value,measured_at,source,deduplication_key,reason,created_by)
                select md5('bench-event-'||n)::uuid,md5('bench-asset-'||((n-1)%10000+1))::uuid,'BENCHMARK','VIEW',1,
                 '2026-01-01'::timestamptz+((n-1)%30)*interval '1 day','BENCHMARK',n::text,'Synthetic load test','test'
                from generate_series(1,1000000) n
                """)
        .update();
    var report = new LinkedHashMap<String, Object>();
    report.put("assets", 10000);
    report.put("events", 1000000);
    report.put("ingestMs", (System.nanoTime() - ingest) / 1_000_000);
    long start = System.nanoTime();
    aggregation.rebuild("Benchmark", "test");
    report.put("rebuildMs", (System.nanoTime() - start) / 1_000_000);
    for (String group : List.of("overview", "asset", "collection", "date", "provider")) {
      start = System.nanoTime();
      var result =
          aggregation.query(
              new AnalyticsQuery(
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  Instant.parse("2026-01-01T00:00:00Z"),
                  Instant.parse("2026-01-31T00:00:00Z"),
                  "USD",
                  "UTC",
                  group,
                  "views",
                  true,
                  0,
                  50));
      report.put(group + "Ms", (System.nanoTime() - start) / 1_000_000);
      assertThat(result.get("rows")).isNotNull();
    }
    java.nio.file.Files.createDirectories(java.nio.file.Path.of("build"));
    java.nio.file.Files.writeString(
        java.nio.file.Path.of("build/task10-benchmark.json"),
        com.mediafactory.processing.ProcessingJson.write(report));
    assertThat(
        db.sql("select sum(value) from analytics_daily_aggregate where platform='BENCHMARK'")
            .query(BigDecimal.class)
            .single())
        .isEqualByComparingTo("1000000");
  }
}

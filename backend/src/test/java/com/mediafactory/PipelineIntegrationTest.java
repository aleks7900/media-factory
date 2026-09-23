package com.mediafactory;
import com.mediafactory.service.*;
import com.mediafactory.provider.*;
import com.mediafactory.storage.*;
import com.mediafactory.quality.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.util.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"media.worker.enabled=false"})
class PipelineIntegrationTest {
 @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
   r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
   r.add("media.storage.root",()->"build/test-media/"+UUID.randomUUID());
 }
 @Autowired FactoryService service; @Autowired JdbcClient db; @Autowired TransactionTemplate tx; @Autowired MediaStorage storage; @Autowired TechnicalQa qa;
 @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
 @Autowired ImageGenerationProperties properties;
 @Autowired com.mediafactory.provider.resilience.ProviderRateLimiter limiter;
 @Autowired com.mediafactory.provider.resilience.RetryDecisionService retry;
 @Autowired GenerationAttemptRepository attempts;
 @Autowired ProviderObservability telemetry;
 GenerationWorker worker(ImageGenerationProvider provider) { return new GenerationWorker(db,tx,service,new com.mediafactory.provider.routing.ImageProviderRouter(List.of(provider),properties),properties,limiter,retry,attempts,telemetry,storage,qa); }
 UUID concept() {
   var p=service.project("Integration studio","");var c=service.collection((UUID)p.get("id"),"Test collection");
   return (UUID)service.concept((UUID)c.get("id"),"Test concept","A landscape").get("id");
 }
 @BeforeEach void clear() { db.sql("truncate projects,provider_runtime,provider_request_events cascade").update(); }
 @Test void completePipelineIdempotencyReviewAndRegeneration() {
   UUID concept=concept();String key=UUID.randomUUID().toString();
   var g=service.generate(concept,"Test",128,128,key,null);
   assertThat(service.generate(concept,"Test",128,128,key,null).get("id")).isEqualTo(g.get("id"));
   assertThatThrownBy(()->service.generate(concept,"Changed",128,128,key,null)).hasMessageContaining("different request");
   var w=worker(new MockProviders());var job=w.claim();assertThat(w.claim()).isNull();w.execute(job);
   assertThat(service.one("generations",(UUID)g.get("id")).get("status")).isEqualTo("QA_PENDING");
   var asset=service.list("assets").getFirst();assertThat(storage.read((String)asset.get("storage_key"))).isNotEmpty();
   assertThat(service.list("generation_costs")).hasSize(1);
   service.review((UUID)asset.get("id"),"APPROVED","Good");
   assertThatThrownBy(()->service.review((UUID)asset.get("id"),"REJECTED","Late")).hasMessageContaining("Cannot transition");
   var regenerated=service.regenerate((UUID)asset.get("id"),UUID.randomUUID().toString());
   assertThat(regenerated.get("parent_id")).isEqualTo(g.get("id"));assertThat(service.list("assets")).hasSize(1);
 }
 @Test void retriesExhaustAndManualRetryPreservesAttemptHistory() {
   var g=service.generate(concept(),"Test",128,128,UUID.randomUUID().toString(),null);
   var w=worker(new MockProviders() { @Override public ProviderTypes.Result<ProviderTypes.Media> generate(ProviderTypes.Request r) { throw new IllegalStateException("Provider unavailable"); } });
   UUID jobId=null;
   for(int i=0;i<3;i++) { var j=w.claim();jobId=(UUID)j.get("id");w.execute(j);db.sql("update jobs set available_at=now() where id=?").param(jobId).update(); }
   assertThat(service.one("jobs",jobId)).containsEntry("status","FAILED").containsEntry("attempts",3);
   assertThat(service.list("generation_costs")).hasSize(3).allSatisfy(cost->assertThat(cost.get("outcome")).isEqualTo("FAILED"));
   assertThat(service.one("generations",(UUID)g.get("id")).get("status")).isEqualTo("FAILED");
   assertThat(service.retry(jobId)).containsEntry("status","QUEUED").containsEntry("attempts",3).containsEntry("max_attempts",6);
 }
 @Test void duplicateAssetsAreRejected() {
   var mock=new MockProviders(); var constant=mock.generate(new ProviderTypes.Request("constant","Test",128,128));
   var w=worker(new MockProviders() { @Override public ProviderTypes.Result<ProviderTypes.Media> generate(ProviderTypes.Request r) { return constant; } });UUID c=concept();
   service.generate(c,"One",128,128,UUID.randomUUID().toString(),null);w.execute(w.claim());
   var second=service.generate(c,"Two",128,128,UUID.randomUUID().toString(),null);w.execute(w.claim());
   assertThat(service.one("generations",(UUID)second.get("id")).get("status")).isEqualTo("REJECTED");
   assertThat(service.list("quality_reviews").toString()).contains("Duplicate SHA-256");
 }
 @Test void staleWorkerCannotFinishRecoveredJob() {
   var g=service.generate(concept(),"Test",128,128,UUID.randomUUID().toString(),null);
   var w=worker(new MockProviders());var stale=w.claim();
   db.sql("update jobs set locked_at=now()-interval '10 minutes' where id=?").param(stale.get("id")).update();w.recover();w.execute(stale);
   assertThat(service.one("generations",(UUID)g.get("id")).get("status")).isEqualTo("QUEUED");assertThat(service.list("assets")).isEmpty();
 }
 @Test void httpValidationAndMissingResources() throws Exception {
   var client=java.net.http.HttpClient.newHttpClient();
   var invalid=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/projects"))
     .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"name\":\"\"}")).build();
   assertThat(client.send(invalid,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
   var missing=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/assets/"+UUID.randomUUID())).GET().build();
   assertThat(client.send(missing,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
   var absentKey=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/generations"))
     .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"conceptId\":\""+concept()+"\",\"prompt\":\"Test\",\"width\":128,\"height\":128}")).build();
   assertThat(client.send(absentKey,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
 }
}

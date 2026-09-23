package com.mediafactory;

import com.mediafactory.provider.*;
import com.mediafactory.provider.ProviderTypes.*;
import com.mediafactory.provider.routing.*;
import com.mediafactory.provider.resilience.*;
import com.mediafactory.service.*;
import com.mediafactory.storage.MediaStorage;
import com.mediafactory.quality.TechnicalQa;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import okhttp3.mockwebserver.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.net.http.*;
import java.net.URI;
import java.time.Duration;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Tag("integration") @Testcontainers @ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"media.worker.enabled=false"})
class RealProviderIntegrationTest {
 static final String SECRET="test-provider-secret-DO-NOT-EXPOSE";
 static final MockWebServer server=new MockWebServer();
 static final AtomicInteger calls=new AtomicInteger();
 static final AtomicReference<Function<RecordedRequest,MockResponse>> response=new AtomicReference<>();
 static final String PNG=Base64.getEncoder().encodeToString(new MockProviders().generate(new Request("fixture","Test",1024,1024)).output().bytes());
 static {
  try {server.start(java.net.InetAddress.getByName("127.0.0.1"),0);} catch(java.io.IOException e) {throw new ExceptionInInitializerError(e);}
  server.setDispatcher(new Dispatcher() {public MockResponse dispatch(RecordedRequest request) {calls.incrementAndGet();return response.get().apply(request);}});
 }
 @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
 @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
  r.add("media.storage.root",()->"build/provider-test-media");r.add("media-factory.openai.api-key",()->SECRET);
  r.add("media-factory.openai.endpoint",()->"http://127.0.0.1:"+server.getPort()+"/v1/images/generations");r.add("media-factory.openai.allow-local-http",()->true);
  r.add("media-factory.image-generation.providers.openai.enabled",()->true);
  r.add("media-factory.image-generation.providers.openai.timeout.request",()->"2s");
  r.add("media-factory.image-generation.providers.openai.retry.retry-ambiguous",()->true);
  r.add("media-factory.image-generation.providers.openai.retry.max-attempts",()->2);
  r.add("media-factory.image-generation.providers.openai.retry.initial-delay",()->"10ms");
  r.add("media-factory.image-generation.providers.openai.rate-limit.requests-per-minute",()->100);
  r.add("media-factory.image-generation.providers.openai.rate-limit.concurrent-requests",()->2);
  r.add("media-factory.image-generation.providers.openai.circuit.failure-threshold",()->10);
 }
 @Autowired FactoryService service;@Autowired JdbcClient db;@Autowired TransactionTemplate tx;@Autowired ImageProviderRouter router;
 @Autowired ImageGenerationProperties properties;@Autowired ProviderRateLimiter limiter;@Autowired RetryDecisionService retry;
 @Autowired GenerationAttemptRepository attempts;@Autowired ProviderObservability telemetry;@Autowired MediaStorage storage;@Autowired TechnicalQa qa;
 @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
 GenerationWorker worker;
 @BeforeEach void setup() {
  db.sql("truncate projects,provider_runtime,provider_request_events cascade").update();calls.set(0);response.set(r->success());
  worker=new GenerationWorker(db,tx,service,router,properties,limiter,retry,attempts,telemetry,storage,qa);
 }
 @AfterEach void cleanup() {worker.close();}
 @AfterAll static void stop() throws Exception {server.shutdown();}
 static MockResponse success() {return new MockResponse().setHeader("Content-Type","application/json").setHeader("x-request-id","req_fixture")
  .setBody("{\"created\":1790193600,\"data\":[{\"b64_json\":\""+PNG+"\",\"revised_prompt\":\"A studio\"}],\"usage\":{\"input_tokens\":100,\"output_tokens\":1000,\"input_tokens_details\":{\"text_tokens\":100,\"image_tokens\":0}}}");}
 Map<String,Object> generation() {
  var p=service.project("Provider test","");var c=service.collection((UUID)p.get("id"),"Tests");var concept=service.concept((UUID)c.get("id"),"Concept","A studio");
  return service.generateImage((UUID)concept.get("id"),"A studio",1024,1024,UUID.randomUUID().toString(),null,
   new ImageOptions("openai",null,ImageOptions.AspectRatio.SQUARE,ImageOptions.Quality.LOW,ImageOptions.Format.PNG,null,null,null,false,1));
 }
 void run() {worker.execute(worker.claim());}
 @Test void minimalHttpRequestDefaultsOptionalParametersAndReplaysIdempotently() throws Exception {
  var p=service.project("HTTP defaults","");var c=service.collection((UUID)p.get("id"),"Tests");var concept=service.concept((UUID)c.get("id"),"Concept","A studio");
  var body=JsonMapper.builder().build().writeValueAsString(Map.of("conceptId",concept.get("id"),"prompt","A studio","provider","mock"));
  var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/generations/images"))
   .header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(body)).build();
  var client=HttpClient.newHttpClient();var first=client.send(request,HttpResponse.BodyHandlers.ofString());
  assertThat(first.statusCode()).isEqualTo(202);
  var replay=client.send(request,HttpResponse.BodyHandlers.ofString());assertThat(replay.statusCode()).isEqualTo(202);assertThat(replay.body()).isEqualTo(first.body());
  run();assertThat(service.list("generations")).hasSize(1);assertThat(service.list("generations").getFirst().get("status")).isEqualTo("QA_PENDING");assertThat(calls.get()).isZero();
 }
 void available() {db.sql("update jobs set available_at=now() where status='QUEUED'").update();}
 void fallback(UUID generation) {db.sql("update generations set provider_route=cast(? as jsonb) where id=?")
  .params("[{\"provider\":\"openai\",\"model\":\"gpt-image-2\"},{\"provider\":\"mock\",\"model\":\"studio-mock-v1\"}]",generation).update();}
 @Test void successMapsRequestPersistsMediaAttemptUsageAndEstimate() {
  var observed=new AtomicReference<String>();response.set(r->{assertThat(r.getHeader("Authorization")).isEqualTo("Bearer "+SECRET);observed.set(r.getBody().readUtf8());return success();});
  var g=generation();run();assertThat(service.one("generations",(UUID)g.get("id"))).containsEntry("status","QA_PENDING").containsEntry("final_provider","openai");
  assertThat(observed.get()).contains("\"model\":\"gpt-image-2\"","\"quality\":\"low\"").doesNotContain("response_format",SECRET);
  assertThat(db.sql("select status from generation_attempts").query(String.class).single()).isEqualTo("SUCCEEDED");
  var cost=service.list("generation_costs").getFirst();assertThat((java.math.BigDecimal)cost.get("estimated_cost")).isEqualByComparingTo("0.01525");assertThat(cost.get("actual_cost")).isNull();assertThat(cost.get("pricing_version")).isEqualTo("2026-09-24");
  var asset=service.list("assets").getFirst();assertThat(TechnicalQa.checksum(storage.read((String)asset.get("storage_key")))).isEqualTo(asset.get("sha256"));
 }
 @Test void transientFailureRetriesSameGenerationAndPreservesUnknownFailedCost() {
  response.set(r->calls.get()==1?new MockResponse().setResponseCode(503).setBody("private failure"):success());var g=generation();run();
  assertThat(service.one("generations",(UUID)g.get("id")).get("status")).isEqualTo("QUEUED");available();run();
  assertThat(calls.get()).isEqualTo(2);assertThat(service.one("generations",(UUID)g.get("id")).get("status")).isEqualTo("QA_PENDING");
  assertThat(db.sql("select count(*) from generation_costs where estimated_cost is null and outcome='FAILED'").query(Integer.class).single()).isEqualTo(1);
 }
 @Test void rateLimitHonorsRetryAfterWithoutMakingEarlyCall() {
  response.set(r->calls.get()==1?new MockResponse().setResponseCode(429).setHeader("Retry-After","7"):success());generation();run();
  assertThat(worker.claim()).isNull();assertThat(db.sql("select extract(epoch from(available_at-now())) from jobs").query(Double.class).single()).isGreaterThan(5);
  assertThat(db.sql("select status from generation_attempts").query(String.class).single()).isEqualTo("RATE_LIMITED");available();run();assertThat(calls.get()).isEqualTo(2);
 }
 @Test void badRequestAndAuthenticationDoNotRetryOrLeakSecrets(CapturedOutput output) throws Exception {
  for(int code:List.of(400,401)) {
   db.sql("truncate projects,provider_runtime,provider_request_events cascade").update();calls.set(0);
   response.set(r->new MockResponse().setResponseCode(code).setBody("{\"error\":{\"message\":\""+SECRET+"\"}}"));var g=generation();run();
   assertThat(service.one("generations",(UUID)g.get("id")).get("status")).isEqualTo("FAILED");assertThat(worker.claim()).isNull();assertThat(calls.get()).isEqualTo(1);
   var detail=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/generations/"+g.get("id"))).GET().build(),HttpResponse.BodyHandlers.ofString());
   assertThat(detail.body()).doesNotContain(SECRET);assertThat(service.list("generation_costs").toString()).doesNotContain(SECRET);
  }
  var providers=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/providers/image")).GET().build(),HttpResponse.BodyHandlers.ofString());
  assertThat(providers.body()).doesNotContain(SECRET,"apiKey","Authorization");assertThat(output.getAll()).doesNotContain(SECRET);
 }
 @Test void timeoutRetriesOnlyWithExplicitAmbiguousRetryPolicy() {
  response.set(r->calls.get()==1?success().setBodyDelay(3,TimeUnit.SECONDS):success());generation();run();
  assertThat(db.sql("select status from generation_attempts").query(String.class).single()).isEqualTo("TIMED_OUT");available();run();assertThat(calls.get()).isEqualTo(2);
 }
 @Test void fallbackPreservesHistoryAndSameGeneration() {
  response.set(r->new MockResponse().setResponseCode(401));var g=generation();fallback((UUID)g.get("id"));run();available();run();
  assertThat(service.one("generations",(UUID)g.get("id"))).containsEntry("status","QA_PENDING").containsEntry("final_provider","mock");
  assertThat(db.sql("select provider from generation_attempts order by attempt_number").query(String.class).list()).containsExactly("openai","mock");assertThat(service.list("generations")).hasSize(1);
  assertThat(db.sql("select count(distinct prompt_snapshot_id) from generation_attempts").query(Integer.class).single()).isEqualTo(2);
  assertThat(db.sql("select count(distinct canonical_positive_prompt) from rendered_prompt_snapshots where generation_id=?").param(g.get("id")).query(Integer.class).single()).isEqualTo(1);
 }
 @Test void fallbackKeepsCanonicalNegativeAndDistinctFrozenProviderAdaptations() {
  response.set(r->new MockResponse().setResponseCode(401));var p=service.project("Fallback prompts","");var c=service.collection((UUID)p.get("id"),"Tests");var concept=service.concept((UUID)c.get("id"),"Concept","Wolf");
  var options=new ImageOptions("openai",null,ImageOptions.AspectRatio.SQUARE,ImageOptions.Quality.LOW,ImageOptions.Format.PNG,"watermark",null,null,false,1);
  var g=service.generateImage((UUID)concept.get("id"),"A wolf",1024,1024,"fallback-prompt",null,options);fallback((UUID)g.get("id"));run();available();run();
  var snapshots=db.sql("select * from rendered_prompt_snapshots where generation_id=? order by provider").param(g.get("id")).query().listOfRows();
  assertThat(snapshots).hasSize(2);assertThat(snapshots).extracting(s->s.get("canonical_positive_prompt")).containsOnly("A wolf");assertThat(snapshots).extracting(s->s.get("canonical_negative_prompt")).containsOnly("watermark");
  assertThat(snapshots.get(0).get("adapted_positive_prompt")).isNotEqualTo(snapshots.get(1).get("adapted_positive_prompt"));assertThat(db.sql("select count(distinct prompt_snapshot_id) from generation_attempts").query(Integer.class).single()).isEqualTo(2);
 }
 @Test void allProvidersFailThenGenerationIsTerminal() {
  response.set(r->new MockResponse().setResponseCode(401));var g=generation();fallback((UUID)g.get("id"));run();
  var failing=new MockProviders(){@Override public Result<Media> generate(Request r){throw new ImageGenerationException(ImageGenerationException.Type.INVALID_REQUEST,"Permanent mock failure");}};
  var failingRouter=new ImageProviderRouter(List.of(router.provider("openai"),failing),properties);
  try(var ignored=new WorkerResource(new GenerationWorker(db,tx,service,failingRouter,properties,limiter,retry,attempts,telemetry,storage,qa))) {available();ignored.worker.execute(ignored.worker.claim());}
  assertThat(service.one("generations",(UUID)g.get("id")).get("status")).isEqualTo("FAILED");assertThat(db.sql("select count(*) from generation_attempts").query(Integer.class).single()).isEqualTo(2);
 }
 @Test void duplicateDispatchDoesNotCallProviderTwice() throws Exception {
  generation();var job=worker.claim();try(var pool=Executors.newFixedThreadPool(2)) {var first=pool.submit(()->worker.execute(job));var second=pool.submit(()->worker.execute(job));first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);}
  worker.execute(job);assertThat(calls.get()).isEqualTo(1);assertThat(service.list("assets")).hasSize(1);
 }
 @Test void tenJobsNeverExceedTwoConcurrentCalls() throws Exception {
  var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var active=new AtomicInteger();var maximum=new AtomicInteger();
  response.set(r->{int n=active.incrementAndGet();maximum.accumulateAndGet(n,Math::max);entered.countDown();try {release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{active.decrementAndGet();}return success();});
  for(int i=0;i<10;i++) generation();var jobs=new ArrayList<Map<String,Object>>();for(int i=0;i<10;i++) jobs.add(worker.claim());
  try(var pool=Executors.newFixedThreadPool(10)) {
   var futures=jobs.stream().map(j->pool.submit(()->worker.execute(j))).toList();assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
   assertThat(db.sql("select count(*) from provider_permits").query(Integer.class).single()).isLessThanOrEqualTo(2);release.countDown();for(var f:futures) f.get(10,TimeUnit.SECONDS);
  }
  for(int i=0;i<10;i++) {available();var j=worker.claim();if(j==null) break;worker.execute(j);}
  assertThat(maximum.get()).isEqualTo(2);assertThat(calls.get()).isEqualTo(10);assertThat(db.sql("select count(*) from jobs where status='SUCCEEDED'").query(Integer.class).single()).isEqualTo(10);
 }
 @Test void stalePaidAttemptRequiresManualReconciliation() {
  var g=generation();var job=worker.claim();attempts.start(job,"openai","gpt-image-2");db.sql("update jobs set locked_at=now()-interval '10 minutes' where id=?").param(job.get("id")).update();worker.recover();worker.execute(job);
  assertThat(calls.get()).isZero();assertThat(service.one("jobs",(UUID)job.get("id"))).containsEntry("recovery_required",true).containsEntry("status","FAILED");
  assertThatThrownBy(()->service.retry((UUID)job.get("id"))).hasMessageContaining("acknowledgeDuplicateRisk");
 }
 @Test void rollingQuotaIsSharedAndDoesNotConsumeAnAttempt() {
  var g=generation();var job=worker.claim();
  for(int i=0;i<100;i++) db.sql("insert into provider_request_events(id,provider) values(?,'openai')").param(UUID.randomUUID()).update();
  worker.execute(job);assertThat(calls.get()).isZero();assertThat(db.sql("select count(*) from generation_attempts").query(Integer.class).single()).isZero();
  assertThat(service.one("generations",(UUID)g.get("id")).get("status")).isEqualTo("QUEUED");assertThat(worker.claim()).isNull();
 }
 @Test void circuitOpensThenOnlyOneHalfOpenProbeIsAdmitted() {
  for(int i=0;i<10;i++) limiter.observe("openai",new ImageGenerationException(ImageGenerationException.Type.UNAVAILABLE,"Safe failure"));
  var first=generation();var second=generation();var job1=worker.claim();var job2=worker.claim();
  assertThat(limiter.acquire("openai",(UUID)job1.get("id")).circuitOpen()).isTrue();
  db.sql("update provider_runtime set open_until=now()-interval '1 second' where provider='openai'").update();
  var permit=limiter.acquire("openai",(UUID)job1.get("id"));assertThat(permit.acquired()).isTrue();
  assertThat(limiter.acquire("openai",(UUID)job2.get("id")).acquired()).isFalse();limiter.release(permit.permit());
  limiter.observe("openai",null);assertThat(db.sql("select health from provider_runtime where provider='openai'").query(String.class).single()).isEqualTo("HEALTHY");
 }
 @Test void productionRoutingNeverAddsMockUnlessExplicitlyAllowed() {
  var config=new ImageGenerationProperties("openai","production",4,Duration.ofMinutes(5),new ImageGenerationProperties.Routing(true,false,List.of("mock")),properties.providers());
  var strategy=new DefaultImageProviderRoutingStrategy(config,new ImageProviderRouter(new ArrayList<>(router.all()),config));
  assertThat(strategy.resolve(new Request("id","Test",1024,1024)).providers()).extracting(ProviderRoute.Hop::provider).containsExactly("openai");
 }
 @Test void missingPricingOrUsageRemainsUnknown() {
  var pricing=new PricingService(properties);
  assertThat(pricing.quote("openai","gpt-image-1.5",Map.of("textInputTokens","100","imageInputTokens","0","outputTokens","1000")).pricingStatus()).isEqualTo("UNKNOWN");
  assertThat(pricing.quote("openai","gpt-image-2",Map.of()).estimatedCost()).isNull();
 }
 record WorkerResource(GenerationWorker worker) implements AutoCloseable {public void close(){worker.close();}}
}

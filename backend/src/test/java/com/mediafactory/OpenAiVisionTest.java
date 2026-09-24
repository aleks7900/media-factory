package com.mediafactory;
import com.mediafactory.quality.*;
import com.mediafactory.provider.resilience.ImageGenerationException;
import org.springframework.mock.env.MockEnvironment;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Loopback HTTP contract tests only. No paid endpoint or real credential is used. */
class OpenAiVisionTest {
 static final JsonMapper JSON=JsonMapper.builder().build();
 MockWebServer server;OpenAiVisionQualityProvider provider;
 @BeforeEach void setup()throws Exception{server=new MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0);var env=new MockEnvironment().withProperty("media.qa.openai.enabled","true").withProperty("media.qa.openai.api-key","test-secret").withProperty("media.qa.openai.endpoint","http://127.0.0.1:"+server.getPort()+"/v1/responses").withProperty("media.qa.openai.timeout-seconds","1");provider=new OpenAiVisionQualityProvider(env,new QaConfiguration(env));}
 @AfterEach void cleanup()throws Exception{provider.close();server.shutdown();}
 VisionQualityProvider.Request request(){return new VisionQualityProvider.Request(UUID.randomUUID(),UUID.randomUUID(),new byte[]{1,2,3},"image/png","gpt-4.1-mini-2025-04-14","PERFECT",Map.of("promptSnapshot",Map.of("canonical_positive_prompt","A wolf","canonical_negative_prompt","no text")));}
 String response(String text){return JSON.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text",text)))),"usage",Map.of("input_tokens",1000,"output_tokens",100)));}
 @Test void sendsImageStrictSchemaFrozenContextAndParsesUsage()throws Exception{var evidence=new MockVisionQualityProvider().analyze(request()).evidence();server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setHeader("x-request-id","req-test").setBody(response(JSON.writeValueAsString(evidence))));var result=provider.analyze(request());assertThat(result.estimatedCost()).isEqualByComparingTo("0.00056");assertThat(result.requestId()).isEqualTo("req-test");var sent=server.takeRequest();assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer test-secret");var body=JSON.readTree(sent.getBody().readUtf8());assertThat(body.path("store").asBoolean()).isFalse();assertThat(body.path("text").path("format").path("strict").asBoolean()).isTrue();assertThat(body.path("input").toString()).contains("data:image/png;base64,AQID","canonical_positive_prompt","A wolf");}
 @Test void malformedOutputAndMissingFieldsAreProviderFailures(){server.enqueue(new MockResponse().setBody(response("{\"findings\":[],\"dimensions\":[]}")));assertThatThrownBy(()->provider.analyze(request())).isInstanceOf(ImageGenerationException.class).hasMessageContaining("schema validation");var evidence=JSON.valueToTree(new MockVisionQualityProvider().analyze(request()).evidence());((tools.jackson.databind.node.ObjectNode)evidence.path("dimensions").get(0)).remove("confidence");assertThatThrownBy(()->OpenAiVisionQualityProvider.parseEvidence(evidence.toString())).hasMessageContaining("schema validation");}
 @Test void authenticationFailureIsPermanentAndNeverLeaksBody(){server.enqueue(new MockResponse().setResponseCode(401).setBody("secret credential and raw provider details"));assertThatThrownBy(()->provider.analyze(request())).isInstanceOfSatisfying(ImageGenerationException.class,e->{assertThat(e.type()).isEqualTo(ImageGenerationException.Type.AUTHENTICATION);assertThat(e.retryable()).isFalse();assertThat(e.getMessage()).doesNotContain("secret credential");});}
 @Test void rateLimitPreservesRetryAfter(){server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After","7").setBody("{}"));assertThatThrownBy(()->provider.analyze(request())).isInstanceOfSatisfying(ImageGenerationException.class,e->{assertThat(e.retryable()).isTrue();assertThat(e.retryAfter().toSeconds()).isEqualTo(7);});}
 @Test void timeoutHasUnknownPaidOutcome(){server.enqueue(new MockResponse().setBody("{}").setBodyDelay(2,TimeUnit.SECONDS));assertThatThrownBy(()->provider.analyze(request())).isInstanceOfSatisfying(ImageGenerationException.class,e->{assertThat(e.type()).isEqualTo(ImageGenerationException.Type.TIMEOUT);assertThat(e.outcomeUnknown()).isTrue();});}
 @Test void unsupportedMediaNeverCallsProvider(){var r=request();assertThatThrownBy(()->provider.analyze(new VisionQualityProvider.Request(r.assetId(),r.generationId(),r.bytes(),"application/pdf",r.model(),r.scenario(),r.context()))).isInstanceOfSatisfying(ImageGenerationException.class,e->assertThat(e.retryable()).isFalse());assertThat(server.getRequestCount()).isZero();}
}

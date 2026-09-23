package com.mediafactory;
import com.mediafactory.provider.*;
import com.mediafactory.provider.resilience.*;
import com.mediafactory.provider.resilience.ImageGenerationException.Type;
import com.mediafactory.provider.openai.OpenAiImageProperties;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.net.URI;
import static org.assertj.core.api.Assertions.*;

class ProviderPolicyTest {
 private final RetryDecisionService policy=new RetryDecisionService();
 private ImageGenerationProperties.Retry retry(boolean ambiguous) {return new ImageGenerationProperties.Retry(3,Duration.ofSeconds(1),Duration.ofSeconds(30),2,true,ambiguous);}
 @Test void permanentFailuresDoNotRetryAndContentPolicyDoesNotFallback() {
  for(var type:java.util.List.of(Type.AUTHENTICATION,Type.INVALID_REQUEST,Type.CONTENT_POLICY,Type.UNEXPECTED))
   assertThat(policy.decide(new ImageGenerationException(type,"Safe failure"),1,retry(false),false,true).retry()).isFalse();
  assertThat(policy.decide(new ImageGenerationException(Type.CONTENT_POLICY,"Blocked"),1,retry(false),false,true).fallback()).isFalse();
 }
 @Test void backoffIsBoundedJitteredAndHonorsRetryAfter() {
  var error=new ImageGenerationException(Type.RATE_LIMIT,"Rate limited",Duration.ofSeconds(60),false,null);
  assertThat(policy.decide(error,1,retry(false),false,false,()->0).delay()).isEqualTo(Duration.ofSeconds(60));
  assertThat(policy.decide(new ImageGenerationException(Type.UNAVAILABLE,"Unavailable"),2,retry(false),false,false,()->0.5).delay()).isEqualTo(Duration.ofMillis(1500));
  assertThat(policy.decide(error,3,retry(false),false,true).fallback()).isTrue();
 }
 @Test void unknownPaidOutcomeRequiresAcknowledgementUnlessExplicitlyConfigured() {
  var error=new ImageGenerationException(Type.TIMEOUT,"Timed out",Duration.ZERO,true,null);
  var safe=policy.decide(error,1,retry(false),false,true);
  assertThat(safe.recoveryRequired()).isTrue();assertThat(safe.retry()).isFalse();assertThat(safe.fallback()).isFalse();
  assertThat(policy.decide(error,1,retry(true),false,true).retry()).isTrue();
 }
 @Test void capabilitiesRejectUnsupportedFeaturesAndQuantity() {
  var options=new ImageOptions(null,null,null,null,null,"negative",42L,null,false,2);
  assertThatThrownBy(()->new MockProviders().capabilities().validate(new ProviderTypes.Request("id","prompt",128,128,options))).isInstanceOf(ImageGenerationException.class);
 }
 @Test void secretConfigurationNeverRendersApiKey() {
  var config=new OpenAiImageProperties("test-secret-sentinel",URI.create("https://api.openai.com/v1/images/generations"),false);
  assertThat(config.toString()).doesNotContain("test-secret-sentinel");
  assertThat(config.redact("echo test-secret-sentinel")).isEqualTo("echo [REDACTED]");
  assertThatThrownBy(()->new OpenAiImageProperties("secret",URI.create("http://remote.example/generate"),false)).isInstanceOf(IllegalArgumentException.class);
 }
}

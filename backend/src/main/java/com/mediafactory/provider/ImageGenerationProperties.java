package com.mediafactory.provider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.*;

@Validated
@ConfigurationProperties("media-factory.image-generation")
public record ImageGenerationProperties(@DefaultValue("mock") String defaultProvider,
 @DefaultValue("development") String environment, @DefaultValue("4") @Min(1) @Max(32) int workerConcurrency,
 @DefaultValue("5m") Duration leaseDuration, @DefaultValue @Valid Routing routing,
 @NotEmpty Map<String,@Valid Provider> providers) {
 public record Routing(@DefaultValue("false") boolean fallbackEnabled,
  @DefaultValue("false") boolean mockProductionFallbackEnabled, List<String> fallbackProviders) {
  public Routing { fallbackProviders=fallbackProviders==null?List.of():List.copyOf(fallbackProviders); }
 }
 public record Provider(@DefaultValue("true") boolean enabled, @NotBlank String model, Set<String> models,
  @DefaultValue @Valid Timeout timeout, @DefaultValue @Valid RateLimit rateLimit,
  @DefaultValue @Valid Retry retry, @DefaultValue @Valid Circuit circuit,
  @DefaultValue("success") String mockScenario, Map<String,Pricing> pricing) {
  public Provider { models=models==null?Set.of(model):Set.copyOf(models);pricing=pricing==null?Map.of():Map.copyOf(pricing); }
 }
 public record Timeout(@DefaultValue("10s") Duration connect,@DefaultValue("120s") Duration request) {}
 public record RateLimit(@DefaultValue("20") @Min(1) int requestsPerMinute,@DefaultValue("3") @Min(1) int concurrentRequests) {}
 public record Retry(@DefaultValue("3") @Min(1) @Max(10) int maxAttempts,@DefaultValue("1s") Duration initialDelay,
  @DefaultValue("30s") Duration maxDelay,@DefaultValue("2") @DecimalMin("1.0") double multiplier,
  @DefaultValue("true") boolean jitter,@DefaultValue("false") boolean retryAmbiguous) {}
 public record Circuit(@DefaultValue("3") @Min(1) int failureThreshold,@DefaultValue("60s") Duration cooldown) {}
 public record Pricing(String version,String source,@DefaultValue("USD") String currency,
  BigDecimal textInputPerMillion,BigDecimal imageInputPerMillion,BigDecimal outputPerMillion) {}
 public Provider provider(String id) {
  var p=providers.get(id);if(p==null) throw new IllegalArgumentException("Unconfigured provider");return p;
 }
}

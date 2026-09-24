package com.mediafactory.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PricingService {

  private final ImageGenerationProperties properties;

  public PricingService(ImageGenerationProperties properties) {
    this.properties = properties;
  }

  public Quote quote(String provider, String model, Map<String, String> usage) {
    if (provider.equals("mock")) {
      return new Quote(BigDecimal.ZERO, BigDecimal.ZERO, "USD", "KNOWN", "mock-free-v1", usage);
    }
    var pricing = properties.provider(provider).pricing().get(model);
    String version = pricing == null ? "unconfigured" : pricing.version();
    String currency = pricing == null ? "USD" : pricing.currency();
    if (pricing == null || pricing.textInputPerMillion() == null
        || pricing.imageInputPerMillion() == null || pricing.outputPerMillion() == null
        || !usage.keySet()
        .containsAll(java.util.Set.of("textInputTokens", "imageInputTokens", "outputTokens"))) {
      return new Quote(null, null, currency, "UNKNOWN", version, usage);
    }
    try {
      var cost = new BigDecimal(usage.get("textInputTokens")).multiply(
              pricing.textInputPerMillion())
          .add(new BigDecimal(usage.get("imageInputTokens")).multiply(
              pricing.imageInputPerMillion()))
          .add(new BigDecimal(usage.get("outputTokens")).multiply(pricing.outputPerMillion()))
          .divide(new BigDecimal("1000000"), 8, RoundingMode.HALF_UP);
      return new Quote(cost, null, currency, "ESTIMATED", version, usage);
    } catch (NumberFormatException e) {
      return new Quote(null, null, currency, "UNKNOWN", version, Map.of());
    }
  }

  public record Quote(BigDecimal estimatedCost, BigDecimal actualCost, String currency,
                      String pricingStatus,
                      String pricingVersion, Map<String, String> usageDetails) {

  }
}

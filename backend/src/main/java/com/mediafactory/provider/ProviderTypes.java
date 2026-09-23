package com.mediafactory.provider;
import java.math.BigDecimal;
import java.util.Map;
public final class ProviderTypes {
 private ProviderTypes() {}
 public record Request(String operationId, String prompt, int width, int height) {}
 public record Usage(String provider, String model, String operation, long inputUsage, long outputUsage, BigDecimal estimatedCost, String currency) {}
 public record Result<T>(T output, Usage usage, Map<String,String> metadata) {}
 public record Media(byte[] bytes, String contentType) {}
}

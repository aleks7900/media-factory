package com.mediafactory.provider;

import java.math.BigDecimal;
import java.util.Map;

public final class ProviderTypes {

  private ProviderTypes() {
  }

  public record Request(String operationId, String prompt, int width, int height,
                        ImageOptions options) {

    public Request(String operationId, String prompt, int width, int height) {
      this(operationId, prompt, width, height, ImageOptions.defaults());
    }

    public Request {
      options = options == null ? ImageOptions.defaults() : options;
    }
  }

  public record Usage(String provider, String model, String operation, long inputUsage,
                      long outputUsage,
                      BigDecimal estimatedCost, String currency) {

  }

  public record Result<T>(T output, Usage usage, Map<String, String> metadata) {

  }

  public record Media(byte[] bytes, String contentType) {

  }
}

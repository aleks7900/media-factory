package com.mediafactory.provider.gemini;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("media-factory.gemini-image")
public record GeminiImageProperties(
    @DefaultValue("") String apiKey,
    @DefaultValue("https://generativelanguage.googleapis.com/v1beta/") URI endpoint,
    @DefaultValue("gemini-3.1-flash-image") String model,
    @DefaultValue("false") boolean allowLocalHttp) {

  public GeminiImageProperties {
    if (endpoint != null && (endpoint.getUserInfo() != null || endpoint.getQuery() != null
        || endpoint.getFragment() != null ||
        !("https".equals(endpoint.getScheme()) || (allowLocalHttp && "http".equals(
            endpoint.getScheme()) && java.util.Set.of("localhost", "127.0.0.1", "[::1]")
            .contains(endpoint.getHost()))))) {
      throw new IllegalArgumentException(
          "Image provider endpoint requires HTTPS; local HTTP is test-only");
    }
  }

  public String redact(String value) {
    return value == null ? null
        : apiKey == null || apiKey.isBlank() ? value : value.replace(apiKey, "[REDACTED]");
  }

  @Override
  public String toString() {
    return "GeminiImageProperties[credentials=REDACTED]";
  }
}

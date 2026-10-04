package com.mediafactory.provider.video.runway;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RunwayVideoProperties {

  final String apiKey;
  final URI endpoint;
  final boolean enabled;
  final BigDecimal pricePerSecond;
  final Set<String> downloadHosts;

  public RunwayVideoProperties(
      @Value("${RUNWAY_VIDEO_API_KEY:}") String key,
      @Value("${RUNWAY_VIDEO_ENDPOINT:https://api.dev.runwayml.com}") String endpoint,
      @Value("${RUNWAY_VIDEO_ENABLED:false}") boolean enabled,
      @Value("${RUNWAY_VIDEO_USD_PER_SECOND:}") String price,
      @Value("${RUNWAY_VIDEO_DOWNLOAD_HOSTS:}") String hosts) {
    this.apiKey = key;
    this.endpoint = URI.create(endpoint);
    this.enabled = enabled;
    this.pricePerSecond = price.isBlank() ? null : new BigDecimal(price);
    if (pricePerSecond != null && pricePerSecond.signum() < 0) {
      throw new IllegalArgumentException("Negative video price");
    }
    this.downloadHosts =
        new HashSet<>(
            Arrays.stream(hosts.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList());
  }

  public boolean configured() {
    return enabled && !apiKey.isBlank() && !downloadHosts.isEmpty();
  }
}

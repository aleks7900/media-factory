package com.mediafactory.quality;

import com.mediafactory.provider.ImageGenerationProperties.Circuit;
import com.mediafactory.provider.ImageGenerationProperties.RateLimit;
import com.mediafactory.provider.ImageGenerationProperties.Retry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Versioned policy files are snapshotted with each review; environment controls admission, not
 * verdicts.
 */
@Component
public class QaConfiguration {

  private final Environment env;
  private final Map<String, QaPolicy> policies;

  public QaConfiguration(Environment env) {
    this.env = env;
    try (var in = new ClassPathResource("qa/policies.json").getInputStream()) {
      var values = JsonMapper.builder().build().readValue(in, QaPolicy[].class);
      var map = new LinkedHashMap<String, QaPolicy>();
      for (var p : values) {
        if (map.put(p.id(), p) != null) {
          throw new IllegalArgumentException("Duplicate policy");
        }
      }
      policies = Map.copyOf(map);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load QA policies");
    }
  }

  public QaPolicy policy(String id) {
    var p = policies.get(id);
    if (p == null) {
      throw new IllegalArgumentException("Unknown QA policy");
    }
    return p;
  }

  public Collection<QaPolicy> policies() {
    return policies.values();
  }

  public String policyFor(String pipeline) {
    return env.getProperty("media.qa.pipeline." + pipeline, "stock".equals(pipeline) ? "stock"
        : "wallpaper".equals(pipeline) ? "wallpaper-standard" : "default");
  }

  public String provider() {
    return env.getProperty("media.qa.provider", "mock");
  }

  public String model(String provider) {
    return provider.equals("mock") ? "mock-vision-v1"
        : env.getProperty("media.qa.openai.model", "gpt-4.1-mini-2025-04-14");
  }

  public List<String> route() {
    var list = new ArrayList<String>();
    list.add(provider());
    for (String item : env.getProperty("media.qa.fallback-providers", "").split(",")) {
      if (!item.isBlank() && !list.contains(item.trim())) {
        list.add(item.trim());
      }
    }
    if (!provider().equals("mock") && list.contains("mock")) {
      throw new IllegalStateException("Real Vision cannot fall back to mock approval");
    }
    return List.copyOf(list);
  }

  public String scenario() {
    return env.getProperty("media.qa.mock-scenario", "PERFECT");
  }

  public int concurrency() {
    return bounded("media.qa.concurrency", 3, 1, 32);
  }

  public int maxCalls() {
    return bounded("media.qa.max-calls", 4, 1, 20);
  }

  public int maxGenerationCalls() {
    return bounded("media.qa.max-generation-calls", 20, 1, 100);
  }

  public Duration lease() {
    return Duration.ofMinutes(3);
  }

  public RateLimit rate() {
    return new RateLimit(bounded("media.qa.requests-per-minute", 120, 1, 10000), concurrency());
  }

  public Retry retry() {
    return new Retry(bounded("media.qa.max-attempts", 2, 1, 10), Duration.ofSeconds(1),
        Duration.ofSeconds(30), 2, true,
        Boolean.parseBoolean(env.getProperty("media.qa.retry-ambiguous", "false")));
  }

  public Circuit circuit() {
    return new Circuit(3, Duration.ofSeconds(60));
  }

  public boolean realEnabled() {
    return Boolean.parseBoolean(env.getProperty("media.qa.openai.enabled", "false"));
  }

  private int bounded(String key, int fallback, int min, int max) {
    int n = env.getProperty(key, Integer.class, fallback);
    if (n < min || n > max) {
      throw new IllegalArgumentException("Invalid " + key);
    }
    return n;
  }
}

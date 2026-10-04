package com.mediafactory.provider.video;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VideoProviderRouter {

  final Map<String, VideoGenerationProvider> providers;
  final String defaultProvider;
  final List<String> fallback;
  final String model;

  public VideoProviderRouter(
      List<VideoGenerationProvider> providers,
      @Value("${VIDEO_DEFAULT_PROVIDER:mock-video}") String defaultProvider,
      @Value("${VIDEO_FALLBACK_PROVIDERS:}") String fallback,
      @Value("${RUNWAY_VIDEO_MODEL:gen4_turbo}") String model) {
    var result = new HashMap<String, VideoGenerationProvider>();
    providers.forEach(p -> result.put(p.providerId(), p));
    this.providers = Map.copyOf(result);
    this.defaultProvider = defaultProvider;
    this.fallback =
        Arrays.stream(fallback.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    this.model = model;
  }

  public VideoGenerationProvider provider(String id) {
    var p = providers.get(id);
    if (p == null || !p.configured()) {
      throw new IllegalArgumentException("Video provider unavailable: " + id);
    }
    return p;
  }

  public List<Map<String, Object>> route(String explicit, boolean allowFallback) {
    var ids = new LinkedHashSet<String>();
    ids.add(explicit == null || explicit.isBlank() ? defaultProvider : explicit);
    if (allowFallback) {
      ids.addAll(fallback);
    }
    if (ids.size() > 4) {
      throw new IllegalArgumentException("Too many video fallback providers");
    }
    return ids.stream()
        .map(
            id -> {
              provider(id);
              return Map.<String, Object>of("provider", id, "model", modelFor(id));
            })
        .toList();
  }

  String modelFor(String id) {
    if (id.equals("mock-video")) {
      return "deterministic-motion-v1";
    }
    if (id.equals("gemini")) {
      return providers.get(id).capabilities().models().iterator().next();
    }
    return model;
  }

  public Object info() {
    return providers.values().stream()
        .map(
            p ->
                Map.of(
                    "provider",
                    p.providerId(),
                    "enabled",
                    p.configured(),
                    "default",
                    p.providerId().equals(defaultProvider),
                    "capabilities",
                    p.capabilities(),
                    "model",
                    modelFor(p.providerId())))
        .toList();
  }
}

package com.mediafactory.provider.routing;

import com.mediafactory.provider.ImageGenerationProperties;
import com.mediafactory.provider.ImageGenerationProvider;
import com.mediafactory.provider.ImageProviderType;
import com.mediafactory.provider.ProviderTypes.Request;
import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.resilience.ImageGenerationException.Type;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ImageProviderRouter {

  private final Map<String, ImageGenerationProvider> adapters;
  private final ImageGenerationProperties properties;

  public ImageProviderRouter(List<ImageGenerationProvider> providers,
      ImageGenerationProperties properties) {
    this.properties = properties;
    var map = new LinkedHashMap<String, ImageGenerationProvider>();
    for (var p : providers) {
      if (map.put(p.providerId(), p) != null) {
        throw new IllegalStateException("Duplicate provider identifier");
      }
    }
    adapters = Map.copyOf(map);
  }

  public static String resolveProviderId(String id) {
    return ImageProviderType.resolveProviderId(id);
  }

  public ImageGenerationProvider provider(String id) {
    String resolved = resolveProviderId(id);
    var p = adapters.get(resolved);
    if (p == null || !properties.providers().containsKey(resolved) || !properties.provider(resolved).enabled()
        || !p.configured()) {
      throw new ImageGenerationException(Type.AUTHENTICATION,
          "Provider is disabled or not configured");
    }
    return p;
  }

  public Collection<ImageGenerationProvider> all() {
    return adapters.values();
  }

  public Request validate(ProviderRoute.Hop hop, Request request) {
    String providerId = resolveProviderId(hop.provider());
    var adapter = provider(providerId);
    String model = hop.model() == null || hop.model().isBlank()
        ? properties.provider(providerId).model()
        : hop.model();
    if (!properties.provider(providerId).models().contains(model)) {
      throw new ImageGenerationException(Type.INVALID_REQUEST,
          "Model is not in the provider's configured allowlist");
    }
    var normalized = new Request(request.operationId(), request.prompt(), request.width(),
        request.height(), request.options().withProvider(providerId).withModel(model));
    adapter.capabilities().validate(normalized);
    return normalized;
  }
}

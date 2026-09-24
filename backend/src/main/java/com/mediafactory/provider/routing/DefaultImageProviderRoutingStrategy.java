package com.mediafactory.provider.routing;

import com.mediafactory.provider.ImageGenerationProperties;
import com.mediafactory.provider.ProviderTypes.Request;
import java.util.ArrayList;
import org.springframework.stereotype.Component;

@Component
public class DefaultImageProviderRoutingStrategy implements ImageProviderRoutingStrategy {

  private final ImageGenerationProperties properties;
  private final ImageProviderRouter router;

  public DefaultImageProviderRoutingStrategy(ImageGenerationProperties properties,
      ImageProviderRouter router) {
    this.properties = properties;
    this.router = router;
  }

  public ProviderRoute resolve(Request request) {
    String explicit = request.options().provider();
    String selected = explicit == null ? properties.defaultProvider() : explicit;
    router.provider(selected);
    var hops = new ArrayList<ProviderRoute.Hop>();
    hops.add(new ProviderRoute.Hop(selected,
        request.options().model() == null ? properties.provider(selected).model()
            : request.options().model()));
    if (properties.routing().fallbackEnabled()) {
      for (String id : properties.routing().fallbackProviders()) {
        if (hops.stream().anyMatch(h -> h.provider().equals(id))) {
          continue;
        }
        if (id.equals("mock") && properties.environment().equalsIgnoreCase("production")
            && !properties.routing().mockProductionFallbackEnabled()) {
          continue;
        }
        router.provider(id);
        hops.add(new ProviderRoute.Hop(id, properties.provider(id).model()));
      }
    }
    // Strict compatibility: the configured route must honor all requested features.
    for (var hop : hops) {
      router.validate(hop, request);
    }
    return new ProviderRoute(explicit == null ? "DEFAULT" : "EXPLICIT", hops);
  }
}

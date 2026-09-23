package com.mediafactory.provider.routing;
import com.mediafactory.provider.*;
import com.mediafactory.provider.ProviderTypes.Request;
import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.resilience.ImageGenerationException.Type;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ImageProviderRouter {
 private final Map<String,ImageGenerationProvider> adapters;
 private final ImageGenerationProperties properties;
 public ImageProviderRouter(List<ImageGenerationProvider> providers,ImageGenerationProperties properties) {
  this.properties=properties;var map=new LinkedHashMap<String,ImageGenerationProvider>();
  for(var p:providers) if(map.put(p.providerId(),p)!=null) throw new IllegalStateException("Duplicate provider identifier");
  adapters=Map.copyOf(map);
 }
 public ImageGenerationProvider provider(String id) {
  var p=adapters.get(id);
  if(p==null || !properties.providers().containsKey(id) || !properties.provider(id).enabled() || !p.configured())
   throw new ImageGenerationException(Type.AUTHENTICATION,"Provider is disabled or not configured");
  return p;
 }
 public Collection<ImageGenerationProvider> all() { return adapters.values(); }
 public Request validate(ProviderRoute.Hop hop,Request request) {
  var adapter=provider(hop.provider());
  if(!properties.provider(hop.provider()).models().contains(hop.model())) throw new ImageGenerationException(Type.INVALID_REQUEST,"Model is not in the provider's configured allowlist");
  var normalized=new Request(request.operationId(),request.prompt(),request.width(),request.height(),request.options().withModel(hop.model()));
  adapter.capabilities().validate(normalized);return normalized;
 }
}

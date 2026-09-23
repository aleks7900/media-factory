package com.mediafactory.provider.routing;
import java.util.List;
public record ProviderRoute(String mode,List<Hop> providers) {
 public ProviderRoute { providers=List.copyOf(providers); }
 public record Hop(String provider,String model) {}
}

package com.mediafactory.bulk;

import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.provider.*;
import com.mediafactory.provider.routing.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class GptImageProcessor implements BulkProcessor {

  final ImageProviderRouter router;

  public GptImageProcessor(ImageProviderRouter router) {
    this.router = router;
  }

  public String kind() {
    return "GPT_IMAGE";
  }

  ProviderTypes.Request request(Input i) {
    var o = i.options();
    String providerId = ImageProviderRouter.resolveProviderId(i.provider());
    String refImage = null;
    if (!i.references().isEmpty()) {
      refImage = Base64.getEncoder().encodeToString(i.references().getFirst());
    }
    var options =
        new ImageOptions(
            providerId,
            i.model(),
            ImageOptions.AspectRatio.CUSTOM,
            ImageOptions.Quality.valueOf(Objects.toString(o.get("quality"), "AUTO")),
            ImageOptions.Format.valueOf(Objects.toString(o.get("format"), "PNG")),
            null,
            null,
            refImage,
            Boolean.TRUE.equals(o.get("transparentBackground")),
            integer(o, "numberOfOutputs", 1));
    return router.validate(
        new ProviderRoute.Hop(providerId, i.model()),
        new ProviderTypes.Request(
            i.attemptId().toString(),
            i.prompt(),
            integer(o, "width", 1024),
            integer(o, "height", 1024),
            options));
  }

  public void validate(Input i) {
    String providerId = ImageProviderRouter.resolveProviderId(i.provider());
    if (!Set.of("openai", "mock", "gemini").contains(providerId)) {
      throw new IllegalArgumentException("Select a supported image provider (OpenAI, Gemini, or Mock)");
    }
    if (!i.references().isEmpty() && !providerId.equals("gemini")) {
      throw new IllegalArgumentException(
          "REFERENCE_IMAGES_UNSUPPORTED: configured image generation endpoint does not accept"
              + " reference images");
    }
    if (!Set.of("width", "height", "quality", "format", "transparentBackground", "numberOfOutputs")
        .containsAll(i.options().keySet())) {
      throw new IllegalArgumentException("Unsupported image option");
    }
    request(i);
  }

  public Quote estimate(Input i) {
    var usage = router.provider(i.provider()).estimate(request(i));
    return new Quote(usage.estimatedCost(), usage.currency());
  }

  public boolean replaySafe(Input i) {
    return router.provider(i.provider()).replaySafe();
  }

  public Output result(String remoteId, Input i) {
    var result = router.provider(i.provider()).generate(request(i));
    var usage = result.usage();
    return new Output(
        result.output().bytes(),
        result.output().contentType(),
        new LinkedHashMap<>(result.metadata()),
        usage.inputUsage(),
        usage.outputUsage(),
        usage.estimatedCost(),
        "mock".equals(ImageProviderRouter.resolveProviderId(i.provider())) ? java.math.BigDecimal.ZERO : null,
        usage.currency());
  }
}

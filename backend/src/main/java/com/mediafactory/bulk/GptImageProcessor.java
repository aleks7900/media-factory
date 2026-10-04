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
    var options =
        new ImageOptions(
            i.provider(),
            i.model(),
            ImageOptions.AspectRatio.CUSTOM,
            ImageOptions.Quality.valueOf(Objects.toString(o.get("quality"), "AUTO")),
            ImageOptions.Format.valueOf(Objects.toString(o.get("format"), "PNG")),
            null,
            null,
            null,
            Boolean.TRUE.equals(o.get("transparentBackground")),
            integer(o, "numberOfOutputs", 1));
    return router.validate(
        new ProviderRoute.Hop(i.provider(), i.model()),
        new ProviderTypes.Request(
            i.attemptId().toString(),
            i.prompt(),
            integer(o, "width", 1024),
            integer(o, "height", 1024),
            options));
  }

  public void validate(Input i) {
    if (!Set.of("openai", "mock").contains(i.provider())) {
      throw new IllegalArgumentException("Select OpenAI or explicit mock test mode");
    }
    if (!i.references().isEmpty()) {
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
        i.provider().equals("mock") ? java.math.BigDecimal.ZERO : null,
        usage.currency());
  }
}

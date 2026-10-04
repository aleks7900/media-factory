package com.mediafactory.bulk;

import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.provider.video.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
public class GeminiVideoProcessor implements BulkProcessor {
  final VideoProviderRouter router;

  public GeminiVideoProcessor(VideoProviderRouter router) {
    this.router = router;
  }

  public String kind() {
    return "GEMINI_VIDEO";
  }

  VideoTypes.Request request(Input i) {
    var o = i.options();
    var providerOptions = new LinkedHashMap<String, Object>();
    if (i.provider().equals("gemini"))
      providerOptions.put(
          "referenceImages",
          i.references().stream()
              .map(
                  r ->
                      Map.of(
                          "mediaType",
                          r.mediaType(),
                          "data",
                          Base64.getEncoder().encodeToString(r.bytes())))
              .toList());
    return new VideoTypes.Request(
        i.generationId(),
        i.attemptId(),
        null,
        null,
        i.prompt(),
        Objects.toString(o.get("negativePrompt"), null),
        integer(o, "width", 1280),
        integer(o, "height", 720),
        integer(o, "durationSeconds", 8),
        null,
        o.containsKey("seed") ? ((Number) o.get("seed")).longValue() : null,
        Map.of(),
        providerOptions,
        i.model());
  }

  byte[] source(Input i) {
    if (i.provider().equals("gemini")) return new byte[0];
    if (!i.references().isEmpty()) return i.references().getFirst().bytes();
    try {
      var out = new ByteArrayOutputStream();
      ImageIO.write(new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB), "png", out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Mock video fixture unavailable");
    }
  }

  String sourceType(Input i) {
    return i.references().isEmpty() ? "image/png" : i.references().getFirst().mediaType();
  }

  public void validate(Input i) {
    if (!Set.of("gemini", "mock-video").contains(i.provider()))
      throw new IllegalArgumentException("Select Gemini or explicit mock-video test mode");
    if (!Set.of("width", "height", "durationSeconds", "negativePrompt", "seed")
        .containsAll(i.options().keySet()))
      throw new IllegalArgumentException("Unsupported video option");
    if (i.provider().equals("mock-video") && i.references().size() > 1)
      throw new IllegalArgumentException("Mock motion supports one reference image");
    router.provider(i.provider()).validateInput(request(i), source(i), sourceType(i));
  }

  public Quote estimate(Input i) {
    var quote = router.provider(i.provider()).estimate(request(i));
    return new Quote(quote.cost(), quote.currency());
  }

  public boolean asynchronous() {
    return true;
  }

  public boolean replaySafe(Input i) {
    return router.provider(i.provider()).capabilities().idempotentSubmission();
  }

  public String submit(Input i) {
    return router
        .provider(i.provider())
        .submit(request(i), source(i), sourceType(i))
        .providerJobId();
  }

  public String status(String id, Input i) {
    return router.provider(i.provider()).status(id).state();
  }

  public Output result(String id, Input i) {
    var result = router.provider(i.provider()).result(id, request(i), source(i), sourceType(i));
    var quote = estimate(i);
    return new Output(
        result.bytes(),
        result.mediaType(),
        result.metadata(),
        null,
        null,
        quote.amount(),
        i.provider().equals("mock-video") ? java.math.BigDecimal.ZERO : null,
        quote.currency());
  }
}

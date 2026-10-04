package com.mediafactory.provider.video.gemini;

import static com.mediafactory.processing.ProcessingJson.*;

import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.resilience.ImageGenerationException.Type;
import com.mediafactory.provider.video.VideoGenerationProvider;
import com.mediafactory.provider.video.VideoTypes.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gemini API Veo adapter. Provider URLs, wire DTOs and authentication stay here.
 */
@Component
public class GeminiVideoGenerationProvider implements VideoGenerationProvider {

  final String key, model;
  final URI endpoint;
  final BigDecimal price;

  public GeminiVideoGenerationProvider(
      @Value("${GEMINI_API_KEY:}") String key,
      @Value("${GEMINI_VIDEO_MODEL:veo-3.1-generate-preview}") String model,
      @Value("${GEMINI_API_ENDPOINT:https://generativelanguage.googleapis.com/v1beta/}")
      URI endpoint,
      @Value("${GEMINI_VIDEO_PRICE_PER_SECOND:}") String price) {
    this.key = key;
    this.model = model;
    this.endpoint = endpoint;
    this.price = price.isBlank() ? null : new BigDecimal(price);
    if (this.price != null && this.price.signum() < 0) {
      throw new IllegalArgumentException("Gemini price must not be negative");
    }
    if (!Set.of("veo-3.1-generate-preview", "veo-3.1-fast-generate-preview").contains(model)) {
      throw new IllegalArgumentException("Unsupported configured Veo model");
    }
    if (endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null
        || !endpoint.getPath().endsWith("/")) {
      throw new IllegalArgumentException("Invalid Gemini endpoint");
    }
    if (!"https".equals(endpoint.getScheme())
        && !Set.of("localhost", "127.0.0.1").contains(endpoint.getHost())) {
      throw new IllegalArgumentException("Gemini endpoint requires HTTPS");
    }
  }

  static boolean validOperation(String id) {
    return id.matches("(?:models/[A-Za-z0-9._-]+/)?operations/[A-Za-z0-9._-]+");
  }

  static ImageGenerationException failure(Type type, boolean unknown, String safe) {
    return new ImageGenerationException(type, safe, Duration.ofSeconds(10), unknown, null);
  }

  public String providerId() {
    return "gemini";
  }

  public boolean configured() {
    return !key.isBlank();
  }

  public Capabilities capabilities() {
    return new Capabilities(
        Set.of(model),
        Set.of("1280:720", "720:1280", "1920:1080", "1080:1920"),
        Set.of(4, 6, 8),
        true,
        true,
        false,
        false,
        true,
        false,
        true,
        false,
        true,
        false,
        true,
        false,
        false);
  }

  public Estimate estimate(Request r) {
    return new Estimate(
        price == null ? null : price.multiply(BigDecimal.valueOf(r.durationSeconds())),
        "USD",
        price == null ? "UNKNOWN" : "operator-configured-v1");
  }

  Map<String, Object> body(Request r, byte[] source, String type) {
    capabilities().validate(r);
    var instance = new LinkedHashMap<String, Object>();
    instance.put("prompt", r.prompt());
    var refs = (List<?>) r.options().getOrDefault("referenceImages", List.of());
    if (refs.size() > 3) {
      throw new IllegalArgumentException("Veo supports at most three reference images");
    }
    if (!refs.isEmpty()) {
      if (r.durationSeconds() != 8) {
        throw new IllegalArgumentException("Veo reference images require eight seconds");
      }
      instance.put(
          "referenceImages",
          refs.stream()
              .map(
                  raw -> {
                    var ref = map(raw);
                    String mime = ref.get("mediaType").toString();
                    if (!Set.of("image/png", "image/jpeg").contains(mime)) {
                      throw new IllegalArgumentException("Unsupported reference media type");
                    }
                    return Map.of(
                        "referenceType",
                        "asset",
                        "image",
                        Map.of("inlineData", Map.of("mimeType", mime, "data", ref.get("data"))));
                  })
              .toList());
    } else if (source != null && source.length > 0) {
      instance.put(
          "image",
          Map.of(
              "inlineData",
              Map.of("mimeType", type, "data", Base64.getEncoder().encodeToString(source))));
    }
    boolean hd = Math.min(r.width(), r.height()) == 1080;
    if (hd && r.durationSeconds() != 8) {
      throw new IllegalArgumentException("1080p Veo requires eight seconds");
    }
    var parameters = new LinkedHashMap<String, Object>();
    parameters.put("aspectRatio", r.width() > r.height() ? "16:9" : "9:16");
    parameters.put("resolution", hd ? "1080p" : "720p");
    parameters.put("durationSeconds", r.durationSeconds());
    parameters.put("sampleCount", 1);
    if (r.seed() != null) {
      parameters.put("seed", r.seed());
    }
    if (r.negativePrompt() != null && !r.negativePrompt().isBlank()) {
      parameters.put("negativePrompt", r.negativePrompt());
    }
    return Map.of("instances", List.of(instance), "parameters", parameters);
  }

  public void validateInput(Request r, byte[] source, String type) {
    body(r, source, type);
  }

  public Submission submit(Request r, byte[] source, String type) {
    var result =
        json(
            endpoint.resolve("models/" + r.model() + ":predictLongRunning"),
            body(r, source, type),
            true);
    String id = Objects.toString(result.get("name"), "");
    if (!validOperation(id)) {
      throw failure(Type.UNEXPECTED, true, "GEMINI_SUBMISSION_UNKNOWN");
    }
    return new Submission(id, null, Map.of("apiVersion", "v1beta"));
  }

  Map<String, Object> operation(String id) {
    if (!validOperation(id)) {
      throw new IllegalArgumentException("Invalid Gemini operation ID");
    }
    return json(endpoint.resolve(id), null, false);
  }

  public Status status(String id) {
    var response = operation(id);
    if (response.containsKey("error")) {
      return new Status("FAILED", null, "USD", "GEMINI_OPERATION_FAILED", Map.of());
    }
    return new Status(
        Boolean.TRUE.equals(response.get("done")) ? "SUCCEEDED" : "RUNNING",
        null,
        "USD",
        null,
        Map.of());
  }

  public Result result(String id, Request r, byte[] source, String type) {
    var response = operation(id);
    if (!Boolean.TRUE.equals(response.get("done")) || response.containsKey("error")) {
      throw failure(Type.INVALID_REQUEST, false, "GEMINI_RESULT_NOT_READY");
    }
    try {
      var generated = map(map(response.get("response")).get("generateVideoResponse"));
      var samples = (List<?>) generated.get("generatedSamples");
      URI url = URI.create(map(map(samples.getFirst()).get("video")).get("uri").toString());
      return new Result(
          exchange(url, null, false, 134217728, true), "video/mp4", Map.of("providerJobId", id));
    } catch (ImageGenerationException e) {
      throw e;
    } catch (RuntimeException e) {
      throw failure(Type.CONTENT_POLICY, false, "GEMINI_RESULT_MISSING_OR_FILTERED");
    }
  }

  Map<String, Object> json(URI uri, Object body, boolean submission) {
    return map(
        new String(
            exchange(uri, body, submission, 2 * 1024 * 1024, false), StandardCharsets.UTF_8));
  }

  byte[] exchange(URI initial, Object body, boolean submission, int limit, boolean download) {
    if (!configured()) {
      throw failure(Type.AUTHENTICATION, false, "GEMINI_NOT_CONFIGURED");
    }
    URI uri = initial;
    for (int redirect = 0; redirect < 4; redirect++) {
      HttpURLConnection c = null;
      try {
        boolean same =
            Objects.equals(uri.getHost(), endpoint.getHost())
                && uri.getPort() == endpoint.getPort()
                && uri.getScheme().equals(endpoint.getScheme());
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
          throw failure(Type.INVALID_REQUEST, false, "GEMINI_UNSAFE_URL");
        }
        if (!same) {
          if (!download
              || !"https".equals(uri.getScheme())
              || !Set.of(
                  "generativelanguage.googleapis.com",
                  "storage.googleapis.com",
                  "video.generativeai.google")
              .contains(uri.getHost())) {
            throw failure(Type.INVALID_REQUEST, false, "GEMINI_DOWNLOAD_HOST_REJECTED");
          }
          for (var address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()) {
              throw failure(Type.INVALID_REQUEST, false, "GEMINI_DOWNLOAD_ADDRESS_REJECTED");
            }
          }
        }
        c = (HttpURLConnection) uri.toURL().openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(false);
        if (same) {
          c.setRequestProperty("x-goog-api-key", key);
        }
        if (body != null) {
          c.setRequestMethod("POST");
          c.setDoOutput(true);
          c.setRequestProperty("Content-Type", "application/json");
          byte[] bytes = write(body).getBytes(StandardCharsets.UTF_8);
          c.setFixedLengthStreamingMode(bytes.length);
          try (var out = c.getOutputStream()) {
            out.write(bytes);
          }
        }
        int code = c.getResponseCode();
        if (download && Set.of(301, 302, 303, 307, 308).contains(code)) {
          uri = uri.resolve(c.getHeaderField("Location"));
          continue;
        }
        if (code < 200 || code >= 300) {
          throw failure(
              code == 429
                  ? Type.RATE_LIMIT
                  : code == 401 || code == 403
                    ? Type.AUTHENTICATION
                      : code >= 500 ? Type.UNAVAILABLE : Type.INVALID_REQUEST,
              submission && code >= 500,
              "Gemini HTTP " + code);
        }
        if (c.getContentLengthLong() > limit) {
          throw failure(Type.UNEXPECTED, submission, "GEMINI_RESPONSE_LIMIT");
        }
        try (var in = c.getInputStream();
            var out = new ByteArrayOutputStream()) {
          byte[] buffer = new byte[65536];
          int n;
          long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
          while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > limit || System.nanoTime() > deadline) {
              throw failure(Type.TIMEOUT, submission, "GEMINI_RESPONSE_LIMIT");
            }
            out.write(buffer, 0, n);
          }
          return out.toByteArray();
        }
      } catch (ImageGenerationException e) {
        throw e;
      } catch (Exception e) {
        throw failure(Type.TIMEOUT, submission, "GEMINI_TRANSPORT_ERROR");
      } finally {
        if (c != null) {
          c.disconnect();
        }
      }
    }
    throw failure(Type.UNEXPECTED, false, "GEMINI_REDIRECT_LIMIT");
  }
}

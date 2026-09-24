package com.mediafactory.quality;

import com.mediafactory.provider.openai.OpenAiImageClient.BoundedBody;
import com.mediafactory.provider.openai.OpenAiImageExceptionMapper;
import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.resilience.ImageGenerationException.Type;
import com.mediafactory.quality.QualityModels.Category;
import com.mediafactory.quality.QualityModels.Code;
import com.mediafactory.quality.QualityModels.DimensionName;
import com.mediafactory.quality.QualityModels.Evidence;
import com.mediafactory.quality.QualityModels.Severity;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OpenAiVisionQualityProvider implements VisionQualityProvider, AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  private final Environment env;
  private final QaConfiguration config;
  private final HttpClient client;
  private final String instructions;

  public OpenAiVisionQualityProvider(Environment env, QaConfiguration config) {
    this.env = env;
    this.config = config;
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    try (var in = new ClassPathResource("qa/visual-quality-v1.txt").getInputStream()) {
      instructions = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Missing QA prompt artifact");
    }
  }

  public static Evidence parseEvidence(String json) {
    try {
      var root = JSON.readTree(json);
      requireFields(root, Set.of("findings", "dimensions"));
      if (!root.path("findings").isArray() || !root.path("dimensions").isArray()) {
        throw invalid();
      }
      for (var f : root.path("findings")) {
        requireFields(f,
            Set.of("category", "code", "severity", "confidence", "detected", "source", "evidence",
                "metadata"));
        if (!f.path("confidence").isNumber() || !f.path("detected").isBoolean()) {
          throw invalid();
        }
        requireFields(f.path("metadata"), Set.of("requirement", "observation"));
      }
      for (var d : root.path("dimensions")) {
        requireFields(d, Set.of("dimension", "score", "confidence", "applicable", "evidence"));
        if (!d.path("score").isNumber() || !d.path("confidence").isNumber() || !d.path("applicable")
            .isBoolean()) {
          throw invalid();
        }
      }
      var evidence = JSON.readValue(json, Evidence.class);
      evidence.validateVision();
      return evidence;
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  private static void requireFields(tools.jackson.databind.JsonNode node, Set<String> fields) {
    if (!node.isObject() || node.size() != fields.size() || fields.stream()
        .anyMatch(f -> !node.has(f) || node.path(f).isNull())) {
      throw invalid();
    }
  }

  private static ImageGenerationException invalid() {
    return new ImageGenerationException(Type.UNEXPECTED, "Vision response failed schema validation",
        Duration.ZERO, true, null);
  }

  public static Map<String, Object> schema() {
    var finding = object(
        Map.of("category", enumeration(Category.values()), "code", enumeration(Code.values()),
            "severity", enumeration(Severity.values()), "confidence", number(), "detected",
            Map.of("type", "boolean"), "source",
            Map.of("type", "string", "enum", List.of("VISION_MODEL")), "evidence",
            Map.of("type", "string"), "metadata", object(
                Map.of("requirement", Map.of("type", "string"), "observation",
                    Map.of("type", "string")))));
    var dimension = object(
        Map.of("dimension", enumeration(DimensionName.values()), "score", number(), "confidence",
            number(), "applicable", Map.of("type", "boolean"), "evidence",
            Map.of("type", "string")));
    return object(Map.of("findings", Map.of("type", "array", "items", finding), "dimensions",
        Map.of("type", "array", "items", dimension)));
  }

  private static Map<String, Object> number() {
    return Map.of("type", "number", "minimum", 0, "maximum", 1);
  }

  private static Map<String, Object> enumeration(Enum<?>[] values) {
    return Map.of("type", "string", "enum", Arrays.stream(values).map(Enum::name).toList());
  }

  private static Map<String, Object> object(Map<String, Object> fields) {
    return Map.of("type", "object", "properties", fields, "required",
        new ArrayList<>(fields.keySet()), "additionalProperties", false);
  }

  public String providerId() {
    return "openai";
  }

  public Set<String> capabilities() {
    return Set.of("image/png", "image/jpeg", "STRUCTURED_EVIDENCE");
  }

  public Result analyze(Request request) {
    String key = env.getProperty("media.qa.openai.api-key", env.getProperty("OPENAI_API_KEY", ""));
    if (!config.realEnabled() || key.isBlank()) {
      throw new ImageGenerationException(Type.AUTHENTICATION,
          "Vision provider is disabled or credentials are unavailable");
    }
    if (!capabilities().contains(request.mediaType())) {
      throw new ImageGenerationException(Type.INVALID_REQUEST, "Vision media type is unsupported");
    }
    URI endpoint = URI.create(
        env.getProperty("media.qa.openai.endpoint", "https://api.openai.com/v1/responses"));
    if (endpoint.getUserInfo() != null || endpoint.getQuery() != null || !(
        "https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()) && Set.of(
            "localhost", "127.0.0.1", "[::1]", "[0:0:0:0:0:0:0:1]").contains(endpoint.getHost()))) {
      throw new ImageGenerationException(Type.INVALID_REQUEST,
          "Vision endpoint requires HTTPS (loopback HTTP allowed for tests)");
    }
    var body = Map.of("model", request.model(), "store", false, "instructions", instructions,
        "max_output_tokens", 6000, "input", List.of(Map.of("role", "user", "content", List.of(
            Map.of("type", "input_text", "text", JSON.writeValueAsString(request.context())),
            Map.of("type", "input_image", "image_url",
                "data:" + request.mediaType() + ";base64," + Base64.getEncoder()
                    .encodeToString(request.bytes()), "detail", "high")))), "text", Map.of("format",
            Map.of("type", "json_schema", "name", "visual_quality_v1", "strict", true, "schema",
                schema())));
    Duration timeout = Duration.ofSeconds(
        env.getProperty("media.qa.openai.timeout-seconds", Integer.class, 90));
    var http = HttpRequest.newBuilder(endpoint).timeout(timeout)
        .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
    var future = client.sendAsync(http, info -> new BoundedBody(1024 * 1024));
    try {
      var response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      String requestId = response.headers().firstValue("x-request-id")
          .filter(v -> v.matches("[a-zA-Z0-9_-]{1,200}") && !v.contains(key)).orElse(null);
      if (response.statusCode() != 200) {
        throw OpenAiImageExceptionMapper.map(response.statusCode(), response.body(),
            response.headers().firstValue("retry-after").orElse(null), requestId);
      }
      var root = JSON.readTree(response.body());
      if (!"completed".equals(root.path("status").asText())) {
        throw invalid();
      }
      String output = null;
      for (var item : root.path("output")) {
        for (var content : item.path("content")) {
          if ("output_text".equals(content.path("type").asText())) {
            if (output != null) {
              throw invalid();
            }
            output = content.path("text").asText();
          }
        }
      }
      if (output == null) {
        throw invalid();
      }
      var evidence = parseEvidence(output);
      var usage = root.path("usage");
      Long input =
          usage.path("input_tokens").isIntegralNumber() ? usage.path("input_tokens").asLong()
              : null;
      Long out =
          usage.path("output_tokens").isIntegralNumber() ? usage.path("output_tokens").asLong()
              : null;
      BigDecimal cost = null;
      String pricing = "unconfigured";
      // Snapshot-pinned pricing; custom models remain explicitly unknown unless configured.
      if (request.model().equals("gpt-4.1-mini-2025-04-14") && input != null && out != null
          && input >= 0 && out >= 0) {
        cost = BigDecimal.valueOf(input).multiply(new BigDecimal("0.40"))
            .add(BigDecimal.valueOf(out).multiply(new BigDecimal("1.60"))).movePointLeft(6);
        pricing = "openai-gpt-4.1-mini-2026-09-24";
      }
      return new Result(evidence, input, out, cost, "USD", pricing, requestId, Map.of());
    } catch (ImageGenerationException e) {
      throw e;
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new ImageGenerationException(Type.TIMEOUT, "Vision request timed out", Duration.ZERO,
          true, null);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new ImageGenerationException(Type.TIMEOUT, "Vision request interrupted", Duration.ZERO,
          true, null);
    } catch (ExecutionException e) {
      throw new ImageGenerationException(Type.UNAVAILABLE, "Vision transport failed", Duration.ZERO,
          true, null);
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  public void close() {
    client.close();
  }
}

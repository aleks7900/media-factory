package com.mediafactory.provider.gemini;

import com.mediafactory.provider.ImageOptions;
import com.mediafactory.provider.ImageOptions.AspectRatio;
import com.mediafactory.provider.ImageOptions.Quality;
import com.mediafactory.provider.ProviderTypes.Media;
import com.mediafactory.provider.ProviderTypes.Request;
import com.mediafactory.provider.ProviderTypes.Result;
import com.mediafactory.provider.ProviderTypes.Usage;
import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.resilience.ImageGenerationException.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class GeminiImageMapper {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final Set<String> SAFETY_FINISH_REASONS =
      Set.of("SAFETY", "IMAGE_SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII");

  private GeminiImageMapper() {
  }

  static String resolveAspectRatio(Request request) {
    AspectRatio ar = request.options().aspectRatio();
    if (ar != null && ar != AspectRatio.CUSTOM) {
      return switch (ar) {
        case SQUARE -> "1:1";
        case WIDE_16_9 -> "16:9";
        case TALL_9_16 -> "9:16";
        case PHOTO_4_3 -> "4:3";
        case PHOTO_3_4 -> "3:4";
        case LANDSCAPE -> "3:2";
        case PORTRAIT -> "2:3";
        case CUSTOM -> "1:1";
      };
    }

    int w = request.width();
    int h = request.height();
    if (w <= 0 || h <= 0) {
      return "1:1";
    }

    double ratio = (double) w / h;
    if (Math.abs(ratio - 1.0) < 0.08) {
      return "1:1";
    }
    if (Math.abs(ratio - (16.0 / 9.0)) < 0.08) {
      return "16:9";
    }
    if (Math.abs(ratio - (9.0 / 16.0)) < 0.08) {
      return "9:16";
    }
    if (Math.abs(ratio - (4.0 / 3.0)) < 0.08) {
      return "4:3";
    }
    if (Math.abs(ratio - (3.0 / 4.0)) < 0.08) {
      return "3:4";
    }
    if (Math.abs(ratio - (3.0 / 2.0)) < 0.08) {
      return "3:2";
    }
    if (Math.abs(ratio - (2.0 / 3.0)) < 0.08) {
      return "2:3";
    }

    // Default to closest major orientation
    if (ratio > 1.4) {
      return "16:9";
    } else if (ratio < 0.7) {
      return "9:16";
    } else if (ratio > 1.1) {
      return "4:3";
    } else if (ratio < 0.9) {
      return "3:4";
    }
    return "1:1";
  }

  static String resolveImageSize(Request request) {
    int maxDim = Math.max(request.width(), request.height());
    Quality quality = request.options().quality();

    if (maxDim > 2048 || quality == Quality.HIGH && maxDim >= 2048) {
      return "4K";
    }
    if (maxDim > 1024 || quality == Quality.HIGH) {
      return "2K";
    }
    return "1K";
  }

  static String request(Request request) {
    var parts = new ArrayList<Map<String, Object>>();
    parts.add(Map.of("text", request.prompt()));

    String ref = request.options().referenceImage();
    if (ref != null && !ref.isBlank()) {
      String mimeType = "image/png";
      String base64Data = ref.trim();
      if (base64Data.startsWith("data:")) {
        int commaIdx = base64Data.indexOf(',');
        if (commaIdx > 0) {
          String header = base64Data.substring(5, commaIdx);
          int semiIdx = header.indexOf(';');
          if (semiIdx > 0) {
            mimeType = header.substring(0, semiIdx).trim();
          }
          base64Data = base64Data.substring(commaIdx + 1).trim();
        }
      } else if (request.options().format() == ImageOptions.Format.JPEG) {
        mimeType = "image/jpeg";
      }

      parts.add(Map.of(
          "inlineData", Map.of(
              "mimeType", mimeType,
              "data", base64Data
          )
      ));
    }

    var content = Map.of(
        "role", "user",
        "parts", List.copyOf(parts)
    );

    var imageConfig = new LinkedHashMap<String, Object>();
    imageConfig.put("aspectRatio", resolveAspectRatio(request));
    imageConfig.put("imageSize", resolveImageSize(request));

    var generationConfig = new LinkedHashMap<String, Object>();
    generationConfig.put("responseModalities", List.of("IMAGE"));
    generationConfig.put("imageConfig", imageConfig);

    var body = new LinkedHashMap<String, Object>();
    body.put("contents", List.of(content));
    body.put("generationConfig", generationConfig);

    return JSON.writeValueAsString(body);
  }

  static Result<Media> response(GeminiImageClient.Response response, Request request,
      GeminiImageProperties secrets) {
    try {
      JsonNode root = JSON.readTree(response.body());

      // 1. Check prompt feedback block
      JsonNode promptFeedback = root.path("promptFeedback");
      if (!promptFeedback.isMissingNode()) {
        String blockReason = promptFeedback.path("blockReason").asText("");
        if (!blockReason.isBlank()) {
          throw new ImageGenerationException(Type.CONTENT_POLICY,
              "Content rejected by Gemini safety policy: " + blockReason, Duration.ZERO, false,
              response.requestId());
        }
      }

      // 2. Check candidates
      JsonNode candidates = root.path("candidates");
      if (!candidates.isArray() || candidates.isEmpty()) {
        JsonNode errorNode = root.path("error");
        if (!errorNode.isMissingNode()) {
          throw GeminiImageExceptionMapper.map(400, response.body(), null, response.requestId());
        }
        throw new ImageGenerationException(Type.UNEXPECTED,
            "Gemini image provider returned no candidates", Duration.ZERO, true,
            response.requestId());
      }

      JsonNode candidate = candidates.get(0);
      String finishReason = candidate.path("finishReason").asText("");
      if (SAFETY_FINISH_REASONS.contains(finishReason.toUpperCase(Locale.ROOT))) {
        throw new ImageGenerationException(Type.CONTENT_POLICY,
            "Content rejected by Gemini safety policy: " + finishReason, Duration.ZERO, false,
            response.requestId());
      }

      // 3. Find image inlineData
      JsonNode parts = candidate.path("content").path("parts");
      if (!parts.isArray() || parts.isEmpty()) {
        throw new ImageGenerationException(Type.UNEXPECTED,
            "Gemini candidate content parts are empty", Duration.ZERO, true,
            response.requestId());
      }

      byte[] imageBytes = null;
      String mimeType = "image/png";
      String responseText = "";

      for (JsonNode part : parts) {
        JsonNode inlineData = part.path("inlineData");
        if (!inlineData.isMissingNode() && inlineData.has("data")) {
          String encoded = inlineData.path("data").asText("");
          if (!encoded.isBlank()) {
            imageBytes = Base64.getDecoder().decode(encoded);
            if (inlineData.has("mimeType")) {
              mimeType = inlineData.path("mimeType").asText("image/png");
            }
            break;
          }
        }
        if (part.has("text")) {
          responseText = part.path("text").asText("");
        }
      }

      if (imageBytes == null || imageBytes.length == 0) {
        String lowerText = responseText.toLowerCase(Locale.ROOT);
        if (lowerText.contains("safety") || lowerText.contains("policy") || lowerText.contains("cannot generate")) {
          throw new ImageGenerationException(Type.CONTENT_POLICY,
              "Content rejected by Gemini safety policy: " + secrets.redact(responseText),
              Duration.ZERO, false, response.requestId());
        }
        throw new ImageGenerationException(Type.UNEXPECTED,
            "Gemini response did not contain image data", Duration.ZERO, true,
            response.requestId());
      }

      if (imageBytes.length > 36 * 1024 * 1024) {
        throw new ImageGenerationException(Type.UNEXPECTED,
            "Generated image exceeds maximum permitted size", Duration.ZERO, true,
            response.requestId());
      }

      // 4. Metadata and usage
      var metadata = new HashMap<>(response.headers());
      if (response.requestId() != null) {
        metadata.put("providerRequestId", response.requestId());
      }

      metadata.put("aspectRatio", resolveAspectRatio(request));
      metadata.put("imageSize", resolveImageSize(request));
      metadata.put("format", request.options().format().name());
      metadata.put("quality", request.options().quality().name());

      JsonNode usageNode = root.path("usageMetadata");
      long promptTokens = usageNode.path("promptTokenCount").asLong(0);
      long candidateTokens = usageNode.path("candidatesTokenCount").asLong(0);

      metadata.put("textInputTokens", String.valueOf(promptTokens));
      metadata.put("outputTokens", String.valueOf(candidateTokens));
      metadata.put("imageInputTokens", request.options().referenceImage() != null ? "258" : "0");

      return new Result<>(
          new Media(imageBytes, mimeType),
          new Usage("gemini", request.options().model(), "IMAGE_GENERATION",
              promptTokens, candidateTokens, null, "USD"),
          Map.copyOf(metadata));
    } catch (ImageGenerationException ige) {
      throw ige;
    } catch (Exception e) {
      throw new ImageGenerationException(Type.UNEXPECTED,
          "Gemini image provider returned an invalid image response", Duration.ZERO, true,
          response.requestId());
    }
  }
}

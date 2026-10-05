package com.mediafactory.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.mediafactory.provider.ImageOptions;
import com.mediafactory.provider.ImageOptions.AspectRatio;
import com.mediafactory.provider.ImageOptions.Format;
import com.mediafactory.provider.ImageOptions.Quality;
import com.mediafactory.provider.ProviderTypes.Media;
import com.mediafactory.provider.ProviderTypes.Request;
import com.mediafactory.provider.ProviderTypes.Result;
import java.net.URI;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GeminiImageMappingTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  Request req(AspectRatio ar, int w, int h, Quality q, Format f, String ref) {
    var options = new ImageOptions("gemini", "gemini-3.1-flash-image", ar, q, f, null, null, ref, false, 1);
    return new Request(UUID.randomUUID().toString(), "Alpine lake test prompt", w, h, options);
  }

  @Test
  void mapsAspectRatiosCorrectly() throws Exception {
    assertThat(GeminiImageMapper.resolveAspectRatio(req(AspectRatio.SQUARE, 1024, 1024, Quality.AUTO, Format.PNG, null)))
        .isEqualTo("1:1");
    assertThat(GeminiImageMapper.resolveAspectRatio(req(AspectRatio.WIDE_16_9, 1920, 1080, Quality.AUTO, Format.PNG, null)))
        .isEqualTo("16:9");
    assertThat(GeminiImageMapper.resolveAspectRatio(req(AspectRatio.TALL_9_16, 1080, 1920, Quality.AUTO, Format.PNG, null)))
        .isEqualTo("9:16");
    assertThat(GeminiImageMapper.resolveAspectRatio(req(AspectRatio.PHOTO_4_3, 1408, 1056, Quality.AUTO, Format.PNG, null)))
        .isEqualTo("4:3");
    assertThat(GeminiImageMapper.resolveAspectRatio(req(AspectRatio.PHOTO_3_4, 1056, 1408, Quality.AUTO, Format.PNG, null)))
        .isEqualTo("3:4");
    assertThat(GeminiImageMapper.resolveAspectRatio(req(AspectRatio.LANDSCAPE, 1536, 1024, Quality.AUTO, Format.PNG, null)))
        .isEqualTo("3:2");
    assertThat(GeminiImageMapper.resolveAspectRatio(req(AspectRatio.PORTRAIT, 1024, 1536, Quality.AUTO, Format.PNG, null)))
        .isEqualTo("2:3");

    // Custom dimensions inferred
    assertThat(GeminiImageMapper.resolveAspectRatio(req(AspectRatio.CUSTOM, 1280, 720, Quality.AUTO, Format.PNG, null)))
        .isEqualTo("16:9");
    assertThat(GeminiImageMapper.resolveAspectRatio(req(AspectRatio.CUSTOM, 720, 1280, Quality.AUTO, Format.PNG, null)))
        .isEqualTo("9:16");
    assertThat(GeminiImageMapper.resolveAspectRatio(req(AspectRatio.CUSTOM, 800, 600, Quality.AUTO, Format.PNG, null)))
        .isEqualTo("4:3");
    assertThat(GeminiImageMapper.resolveAspectRatio(req(AspectRatio.CUSTOM, 500, 500, Quality.AUTO, Format.PNG, null)))
        .isEqualTo("1:1");
  }

  @Test
  void mapsImageSizeBasedOnDimensionsAndQuality() {
    assertThat(GeminiImageMapper.resolveImageSize(req(AspectRatio.SQUARE, 1024, 1024, Quality.AUTO, Format.PNG, null)))
        .isEqualTo("1K");
    assertThat(GeminiImageMapper.resolveImageSize(req(AspectRatio.WIDE_16_9, 1920, 1080, Quality.HIGH, Format.PNG, null)))
        .isEqualTo("2K");
    assertThat(GeminiImageMapper.resolveImageSize(req(AspectRatio.SQUARE, 3840, 3840, Quality.HIGH, Format.PNG, null)))
        .isEqualTo("4K");
  }

  @Test
  void buildsJsonWithReferenceImageAndMimeType() throws Exception {
    String dummyData = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
    Request r = req(AspectRatio.WIDE_16_9, 1920, 1080, Quality.HIGH, Format.PNG, "data:image/png;base64," + dummyData);

    String json = GeminiImageMapper.request(r);
    JsonNode node = JSON.readTree(json);

    assertThat(node.path("contents").get(0).path("parts").get(0).path("text").asText())
        .isEqualTo("Alpine lake test prompt");

    JsonNode inlineData = node.path("contents").get(0).path("parts").get(1).path("inlineData");
    assertThat(inlineData.path("mimeType").asText()).isEqualTo("image/png");
    assertThat(inlineData.path("data").asText()).isEqualTo(dummyData);

    JsonNode imgConfig = node.path("generationConfig").path("imageConfig");
    assertThat(imgConfig.path("aspectRatio").asText()).isEqualTo("16:9");
    assertThat(imgConfig.path("imageSize").asText()).isEqualTo("2K");
  }

  @Test
  void parsesResponseAndUsageTokens() throws Exception {
    byte[] testImage = new byte[] {0x11, 0x22, 0x33};
    String b64 = Base64.getEncoder().encodeToString(testImage);

    String responseJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "inlineData": {
                      "mimeType": "image/jpeg",
                      "data": "%s"
                    }
                  }
                ]
              }
            }
          ],
          "usageMetadata": {
            "promptTokenCount": 50,
            "candidatesTokenCount": 500
          }
        }
        """.formatted(b64);

    Request r = req(AspectRatio.SQUARE, 1024, 1024, Quality.AUTO, Format.JPEG, null);
    var clientResponse = new GeminiImageClient.Response(responseJson.getBytes(), "req-999", Map.of("x-custom", "value"));
    var secrets = new GeminiImageProperties("dummy-key", URI.create("https://generativelanguage.googleapis.com/v1beta/"), "gemini-3.1-flash-image", false);

    Result<Media> result = GeminiImageMapper.response(clientResponse, r, secrets);

    assertThat(result.output().bytes()).isEqualTo(testImage);
    assertThat(result.output().contentType()).isEqualTo("image/jpeg");
    assertThat(result.metadata()).containsEntry("textInputTokens", "50");
    assertThat(result.metadata()).containsEntry("outputTokens", "500");
    assertThat(result.metadata()).containsEntry("providerRequestId", "req-999");
    assertThat(result.metadata()).containsEntry("aspectRatio", "1:1");
    assertThat(result.metadata()).containsEntry("imageSize", "1K");
  }
}

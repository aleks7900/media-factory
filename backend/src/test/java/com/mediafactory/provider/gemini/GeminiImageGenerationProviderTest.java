package com.mediafactory.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mediafactory.provider.ImageGenerationProperties;
import com.mediafactory.provider.ImageOptions;
import com.mediafactory.provider.ImageOptions.AspectRatio;
import com.mediafactory.provider.ImageOptions.Format;
import com.mediafactory.provider.ImageOptions.Quality;
import com.mediafactory.provider.ProviderTypes.Media;
import com.mediafactory.provider.ProviderTypes.Request;
import com.mediafactory.provider.ProviderTypes.Result;
import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.resilience.ImageGenerationException.Type;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GeminiImageGenerationProviderTest {

  HttpServer server;
  URI endpoint;
  AtomicReference<String> requestBody = new AtomicReference<>();
  AtomicReference<String> authHeader = new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/");
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  void mockResponse(int status, String json, Map<String, String> headers) {
    server.createContext("/v1beta/models/gemini-3.1-flash-image:generateContent", exchange -> {
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      authHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
      if (headers != null) {
        headers.forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
      }
      byte[] responseBytes = json.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, responseBytes.length);
      exchange.getResponseBody().write(responseBytes);
      exchange.close();
    });
  }

  GeminiImageGenerationProvider createProvider(String apiKey) {
    var secrets = new GeminiImageProperties(apiKey, endpoint, "gemini-3.1-flash-image", true);
    var providerProps = new ImageGenerationProperties.Provider(
        true,
        "gemini-3.1-flash-image",
        Set.of("gemini-3.1-flash-image"),
        new ImageGenerationProperties.Timeout(Duration.ofSeconds(2), Duration.ofSeconds(5)),
        new ImageGenerationProperties.RateLimit(60, 5),
        new ImageGenerationProperties.Retry(3, Duration.ofSeconds(1), Duration.ofSeconds(30), 2.0, true, false),
        new ImageGenerationProperties.Circuit(3, Duration.ofSeconds(60)),
        "success",
        Map.of()
    );
    var properties = new ImageGenerationProperties(
        "mock",
        "development",
        4,
        Duration.ofMinutes(5),
        new ImageGenerationProperties.Routing(false, false, null),
        Map.of("gemini", providerProps)
    );
    var client = new GeminiImageClient(secrets, properties);
    return new GeminiImageGenerationProvider(client, secrets);
  }

  Request createRequest(String prompt, String referenceImage) {
    var options = new ImageOptions(
        "gemini",
        "gemini-3.1-flash-image",
        AspectRatio.WIDE_16_9,
        Quality.HIGH,
        Format.PNG,
        null,
        null,
        referenceImage,
        false,
        1
    );
    return new Request(UUID.randomUUID().toString(), prompt, 1920, 1080, options);
  }

  @Test
  void successfulImageGeneration() {
    byte[] samplePng = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    String base64Png = Base64.getEncoder().encodeToString(samplePng);

    String responseJson = """
        {
          "candidates": [
            {
              "content": {
                "role": "model",
                "parts": [
                  {
                    "inlineData": {
                      "mimeType": "image/png",
                      "data": "%s"
                    }
                  }
                ]
              },
              "finishReason": "STOP"
            }
          ],
          "usageMetadata": {
            "promptTokenCount": 24,
            "candidatesTokenCount": 258,
            "totalTokenCount": 282
          }
        }
        """.formatted(base64Png);

    mockResponse(200, responseJson, Map.of("x-goog-request-id", "req-12345"));

    var provider = createProvider("test-gemini-key");
    assertThat(provider.configured()).isTrue();
    assertThat(provider.providerId()).isEqualTo("gemini");

    Request request = createRequest("Photorealistic mountain lake at dawn", null);
    Result<Media> result = provider.generate(request);

    assertThat(authHeader.get()).isEqualTo("test-gemini-key");
    assertThat(requestBody.get()).contains("Photorealistic mountain lake at dawn");
    assertThat(requestBody.get()).contains("\"aspectRatio\":\"16:9\"");
    assertThat(requestBody.get()).contains("\"imageSize\":\"2K\"");

    assertThat(result.output().bytes()).isEqualTo(samplePng);
    assertThat(result.output().contentType()).isEqualTo("image/png");
    assertThat(result.usage().provider()).isEqualTo("gemini");
    assertThat(result.usage().model()).isEqualTo("gemini-3.1-flash-image");
    assertThat(result.usage().inputUsage()).isEqualTo(24L);
    assertThat(result.usage().outputUsage()).isEqualTo(258L);
    assertThat(result.metadata()).containsEntry("aspectRatio", "16:9");
    assertThat(result.metadata()).containsEntry("textInputTokens", "24");
    assertThat(result.metadata()).containsEntry("outputTokens", "258");
  }

  @Test
  void referenceImageIsIncludedInRequest() {
    byte[] refBytes = new byte[] {1, 2, 3, 4};
    String refBase64 = Base64.getEncoder().encodeToString(refBytes);

    byte[] samplePng = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};
    String base64Png = Base64.getEncoder().encodeToString(samplePng);

    String responseJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "inlineData": {
                      "mimeType": "image/png",
                      "data": "%s"
                    }
                  }
                ]
              }
            }
          ]
        }
        """.formatted(base64Png);

    mockResponse(200, responseJson, null);

    var provider = createProvider("test-gemini-key");
    Request request = createRequest("Style transfer of reference", "data:image/png;base64," + refBase64);
    Result<Media> result = provider.generate(request);

    assertThat(requestBody.get()).contains(refBase64);
    assertThat(requestBody.get()).contains("inlineData");
    assertThat(result.output().bytes()).isEqualTo(samplePng);
  }

  @Test
  void invalidApiKeyReturnsAuthenticationException() {
    mockResponse(401, "{\"error\":{\"code\":401,\"message\":\"API key not valid\",\"status\":\"UNAUTHENTICATED\"}}", null);

    var provider = createProvider("invalid-key");
    assertThatThrownBy(() -> provider.generate(createRequest("Test prompt", null)))
        .isInstanceOf(ImageGenerationException.class)
        .satisfies(ex -> {
          var ige = (ImageGenerationException) ex;
          assertThat(ige.type()).isEqualTo(Type.AUTHENTICATION);
          assertThat(ige.retryable()).isFalse();
        });
  }

  @Test
  void depletedCreditsReturnsAuthenticationException() {
    mockResponse(402, "{\"error\":{\"code\":402,\"message\":\"Prepayment credits depleted\",\"status\":\"RESOURCE_EXHAUSTED\"}}", null);

    var provider = createProvider("valid-key");
    assertThatThrownBy(() -> provider.generate(createRequest("Test prompt", null)))
        .isInstanceOf(ImageGenerationException.class)
        .satisfies(ex -> {
          var ige = (ImageGenerationException) ex;
          assertThat(ige.type()).isEqualTo(Type.AUTHENTICATION);
          assertThat(ige.retryable()).isFalse();
        });
  }

  @Test
  void rateLimit429ReturnsRateLimitExceptionWithRetryAfter() {
    mockResponse(429, "{\"error\":{\"code\":429,\"message\":\"Quota exceeded\",\"status\":\"RESOURCE_EXHAUSTED\"}}",
        Map.of("retry-after", "15"));

    var provider = createProvider("test-key");
    assertThatThrownBy(() -> provider.generate(createRequest("Test prompt", null)))
        .isInstanceOf(ImageGenerationException.class)
        .satisfies(ex -> {
          var ige = (ImageGenerationException) ex;
          assertThat(ige.type()).isEqualTo(Type.RATE_LIMIT);
          assertThat(ige.retryable()).isTrue();
          assertThat(ige.retryAfter()).isEqualTo(Duration.ofSeconds(15));
        });
  }

  @Test
  void serverError500ReturnsUnavailableException() {
    mockResponse(500, "{\"error\":{\"code\":500,\"message\":\"Internal backend error\",\"status\":\"INTERNAL\"}}", null);

    var provider = createProvider("test-key");
    assertThatThrownBy(() -> provider.generate(createRequest("Test prompt", null)))
        .isInstanceOf(ImageGenerationException.class)
        .satisfies(ex -> {
          var ige = (ImageGenerationException) ex;
          assertThat(ige.type()).isEqualTo(Type.UNAVAILABLE);
          assertThat(ige.retryable()).isTrue();
        });
  }

  @Test
  void promptSafetyRejectionReturnsContentPolicyException() {
    String safetyJson = """
        {
          "promptFeedback": {
            "blockReason": "SAFETY"
          },
          "candidates": []
        }
        """;
    mockResponse(200, safetyJson, null);

    var provider = createProvider("test-key");
    assertThatThrownBy(() -> provider.generate(createRequest("Prohibited prompt", null)))
        .isInstanceOf(ImageGenerationException.class)
        .satisfies(ex -> {
          var ige = (ImageGenerationException) ex;
          assertThat(ige.type()).isEqualTo(Type.CONTENT_POLICY);
          assertThat(ige.retryable()).isFalse();
          assertThat(ige.getMessage()).contains("safety policy");
        });
  }

  @Test
  void candidateSafetyFinishReasonReturnsContentPolicyException() {
    String safetyCandidateJson = """
        {
          "candidates": [
            {
              "finishReason": "IMAGE_SAFETY"
            }
          ]
        }
        """;
    mockResponse(200, safetyCandidateJson, null);

    var provider = createProvider("test-key");
    assertThatThrownBy(() -> provider.generate(createRequest("Unsafe image request", null)))
        .isInstanceOf(ImageGenerationException.class)
        .satisfies(ex -> {
          var ige = (ImageGenerationException) ex;
          assertThat(ige.type()).isEqualTo(Type.CONTENT_POLICY);
          assertThat(ige.retryable()).isFalse();
        });
  }

  @Test
  void emptyCandidatesReturnsUnexpectedException() {
    mockResponse(200, "{\"candidates\":[]}", null);

    var provider = createProvider("test-key");
    assertThatThrownBy(() -> provider.generate(createRequest("Test prompt", null)))
        .isInstanceOf(ImageGenerationException.class)
        .satisfies(ex -> {
          var ige = (ImageGenerationException) ex;
          assertThat(ige.type()).isEqualTo(Type.UNEXPECTED);
        });
  }

  @Test
  void malformedResponseReturnsUnexpectedException() {
    mockResponse(200, "not-valid-json", null);

    var provider = createProvider("test-key");
    assertThatThrownBy(() -> provider.generate(createRequest("Test prompt", null)))
        .isInstanceOf(ImageGenerationException.class)
        .satisfies(ex -> {
          var ige = (ImageGenerationException) ex;
          assertThat(ige.type()).isEqualTo(Type.UNEXPECTED);
        });
  }

  @Test
  void unconfiguredProviderReportsConfiguredFalse() {
    var provider = createProvider("");
    assertThat(provider.configured()).isFalse();
    assertThatThrownBy(() -> provider.generate(createRequest("Test", null)))
        .isInstanceOf(ImageGenerationException.class)
        .hasFieldOrPropertyWithValue("type", Type.AUTHENTICATION);
  }
}

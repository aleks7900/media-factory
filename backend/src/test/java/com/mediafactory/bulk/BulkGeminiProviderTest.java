package com.mediafactory.bulk;

import static org.assertj.core.api.Assertions.*;

import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.video.VideoTypes.*;
import com.mediafactory.provider.video.gemini.GeminiVideoGenerationProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class BulkGeminiProviderTest {

  HttpServer server;
  URI endpoint;
  AtomicReference<String> body = new AtomicReference<>(), auth = new AtomicReference<>();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/");
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  void response(String path, int status, String json) {
    server.createContext(
        path,
        e -> {
          body.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          auth.set(e.getRequestHeaders().getFirst("x-goog-api-key"));
          var bytes = json.getBytes(StandardCharsets.UTF_8);
          e.sendResponseHeaders(status, bytes.length);
          e.getResponseBody().write(bytes);
          e.close();
        });
  }

  GeminiVideoGenerationProvider provider() {
    return new GeminiVideoGenerationProvider(
        "fake-test-key", "veo-3.1-generate-preview", endpoint, "");
  }

  Request request(int seconds, List<?> refs) {
    return new Request(
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        null,
        "A car on a mountain road",
        null,
        1280,
        720,
        seconds,
        null,
        null,
        Map.of(),
        Map.of("referenceImages", refs),
        "veo-3.1-generate-preview");
  }

  @Test
  void sendsReferencesAndPollsWithoutInventingPrices() {
    response(
        "/v1beta/models/veo-3.1-generate-preview:predictLongRunning",
        200,
        "{\"name\":\"operations/test\"}");
    response("/v1beta/operations/test", 200, "{\"done\":false}");
    var p = provider();
    var r = request(8, List.of(Map.of("mediaType", "image/png", "data", "aW1hZ2U=")));
    assertThat(p.submit(r, new byte[0], "image/png").providerJobId()).isEqualTo("operations/test");
    assertThat(body.get()).contains("referenceImages", "inlineData", "asset", "durationSeconds");
    assertThat(auth.get()).isEqualTo("fake-test-key");
    assertThat(p.status("operations/test").state()).isEqualTo("RUNNING");
    assertThat(p.estimate(r).cost()).isNull();
  }

  @Test
  void rejectsUnsupportedReferenceDurationBeforeSubmission() {
    assertThatThrownBy(
        () ->
            provider()
                .validateInput(
                    request(4, List.of(Map.of("mediaType", "image/png", "data", "aW1hZ2U="))),
                    new byte[0],
                    "image/png"))
        .hasMessageContaining("eight seconds");
  }

  @Test
  void rejectsUnknownSubmissionAndUnsafeResultUrl() {
    response(
        "/v1beta/models/veo-3.1-generate-preview:predictLongRunning",
        200,
        "{\"name\":\"../outside\"}");
    assertThatThrownBy(() -> provider().submit(request(8, List.of()), new byte[0], "image/png"))
        .isInstanceOfSatisfying(
            ImageGenerationException.class, e -> assertThat(e.outcomeUnknown()).isTrue());
    response(
        "/v1beta/operations/test",
        200,
        "{\"done\":true,\"response\":{\"generateVideoResponse\":{\"generatedSamples\":[{\"video\":{\"uri\":\"http://169.254.169.254/latest/meta-data\"}}]}}}");
    assertThatThrownBy(
        () ->
            provider()
                .result("operations/test", request(8, List.of()), new byte[0], "image/png"))
        .isInstanceOf(ImageGenerationException.class);
  }

  @Test
  void classifiesQuotaWithoutExposingProviderResponse() {
    response(
        "/v1beta/models/veo-3.1-generate-preview:predictLongRunning",
        429,
        "private-provider-error");
    assertThatThrownBy(() -> provider().submit(request(8, List.of()), new byte[0], "image/png"))
        .isInstanceOfSatisfying(
            ImageGenerationException.class,
            e -> {
              assertThat(e.type()).isEqualTo(ImageGenerationException.Type.RATE_LIMIT);
              assertThat(e.getMessage()).doesNotContain("private-provider-error");
            });
  }
}

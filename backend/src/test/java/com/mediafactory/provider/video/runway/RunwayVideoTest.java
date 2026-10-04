package com.mediafactory.provider.video.runway;

import static org.assertj.core.api.Assertions.*;

import com.mediafactory.provider.resilience.ImageGenerationException;
import com.mediafactory.provider.video.VideoTypes.Request;
import com.mediafactory.video.VideoFailure;
import java.util.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;

class RunwayVideoTest {

  MockWebServer server;
  RunwayVideoGenerationProvider provider;
  RunwayVideoClient client;

  @BeforeEach
  void setup() throws Exception {
    server = new MockWebServer();
    server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
    var p =
        new RunwayVideoProperties(
            "test-token", "http://127.0.0.1:" + server.getPort() + "/", true, "0.1",
            "cdn.example.test");
    client = new RunwayVideoClient(p);
    provider = new RunwayVideoGenerationProvider(client, p);
  }

  @AfterEach
  void close() throws Exception {
    server.close();
  }

  Request request(String negative, Integer fps) {
    return new Request(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64),
        "Subtle floating motion",
        negative,
        720,
        1280,
        5,
        fps,
        42L,
        Map.of(),
        Map.of(),
        "gen4_turbo");
  }

  @Test
  void submitMapsVerifiedContractAndPersistsRemoteIdentity() throws Exception {
    UUID id = UUID.randomUUID();
    server.enqueue(new MockResponse().setBody("{\"id\":\"" + id + "\"}"));
    assertThat(
        provider.submit(request("", null), new byte[]{1, 2, 3}, "image/png").providerJobId())
        .isEqualTo(id.toString());
    var captured = server.takeRequest();
    assertThat(captured.getPath()).isEqualTo("/v1/image_to_video");
    assertThat(captured.getHeader("X-Runway-Version")).isEqualTo("2024-11-06");
    assertThat(captured.getBody().readUtf8())
        .contains("data:image/png;base64,AQID", "720:1280")
        .doesNotContain("negativePrompt", "fps");
  }

  @Test
  void unsupportedControlsFailBeforeSubmission() {
    assertThatThrownBy(() -> provider.submit(request("no text", null), new byte[1], "image/png"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> provider.submit(request("", 30), new byte[1], "image/png"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void malformedSuccessfulSubmissionIsUncertain() {
    server.enqueue(new MockResponse().setBody("{}"));
    assertThatThrownBy(() -> provider.submit(request("", null), new byte[1], "image/png"))
        .isInstanceOfSatisfying(
            ImageGenerationException.class, e -> assertThat(e.outcomeUnknown()).isTrue());
  }

  @Test
  void rateLimitAndAuthenticationDoNotPretendToHaveSubmitted() {
    for (int code : List.of(429, 401)) {
      server.enqueue(new MockResponse().setResponseCode(code).setHeader("Retry-After", "7"));
      assertThatThrownBy(() -> provider.submit(request("", null), new byte[1], "image/png"))
          .isInstanceOfSatisfying(
              ImageGenerationException.class,
              e -> {
                assertThat(e.outcomeUnknown()).isFalse();
                assertThat(e.type())
                    .isEqualTo(
                        code == 429
                            ? ImageGenerationException.Type.RATE_LIMIT
                            : ImageGenerationException.Type.AUTHENTICATION);
              });
    }
  }

  @Test
  void unavailableSubmissionIsUncertain() {
    server.enqueue(new MockResponse().setResponseCode(503));
    assertThatThrownBy(() -> provider.submit(request("", null), new byte[1], "image/png"))
        .isInstanceOfSatisfying(
            ImageGenerationException.class, e -> assertThat(e.outcomeUnknown()).isTrue());
  }

  @Test
  void statusesAreProviderNeutral() {
    for (var pair :
        Map.of(
                "PENDING",
                "QUEUED",
                "RUNNING",
                "PROCESSING",
                "SUCCEEDED",
                "SUCCEEDED",
                "FAILED",
                "FAILED",
                "CANCELED",
                "CANCELLED")
            .entrySet()) {
      server.enqueue(new MockResponse().setBody("{\"status\":\"" + pair.getKey() + "\"}"));
      assertThat(provider.status(UUID.randomUUID().toString()).state()).isEqualTo(pair.getValue());
    }
  }

  @Test
  void unsafeDownloadsAreRejectedBeforeConnection() {
    for (String url :
        List.of(
            "http://cdn.example.test/a.mp4",
            "https://127.0.0.1/a.mp4",
            "https://evil.test/a.mp4",
            "https://user@cdn.example.test/a.mp4")) {
      assertThatThrownBy(() -> client.download(url)).isInstanceOf(VideoFailure.class);
    }
  }

  @Test
  void readinessRequiresExplicitDownloadPolicy() {
    assertThat(
        new RunwayVideoProperties("test", "http://127.0.0.1:" + server.getPort() + "/", true, "0.1",
            "")
            .configured())
        .isFalse();
  }

  @Test
  void connectionLostAfterSubmissionIsUncertain() {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    assertThatThrownBy(() -> provider.submit(request("", null), new byte[1], "image/png"))
        .isInstanceOfSatisfying(ImageGenerationException.class,
            e -> assertThat(e.outcomeUnknown()).isTrue());
    assertThat(server.getRequestCount()).isEqualTo(1);
  }
}

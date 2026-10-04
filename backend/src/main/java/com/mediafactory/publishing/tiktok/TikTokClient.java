package com.mediafactory.publishing.tiktok;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TikTokClient {

  private static final Logger log = LoggerFactory.getLogger(TikTokClient.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final TikTokProperties properties;
  private final HttpClient httpClient;

  // Mock state tracking for deterministic offline test scenarios
  private final Map<String, Integer> mockPollCounts = new ConcurrentHashMap<>();

  public TikTokClient(TikTokProperties properties) {
    this.properties = properties;
    this.httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  public TikTokProperties getProperties() {
    return properties;
  }

  public String getAuthorizationUrl(String state) {
    String clientKey = properties.getClientKey();
    String redirectUri = URLEncoder.encode(properties.getRedirectUri(), StandardCharsets.UTF_8);
    String scope = URLEncoder.encode("user.info.basic,video.publish,video.upload", StandardCharsets.UTF_8);
    String encodedState = URLEncoder.encode(state != null ? state : UUID.randomUUID().toString(), StandardCharsets.UTF_8);

    return properties.getAuthUrl()
        + "?client_key=" + clientKey
        + "&response_type=code"
        + "&scope=" + scope
        + "&redirect_uri=" + redirectUri
        + "&state=" + encodedState;
  }

  public TikTokResponses.TokenResponse exchangeCode(String code) {
    if (!properties.isEnabled()) {
      return mockTokenResponse(code);
    }

    String endpoint = properties.getApiBaseUrl() + "/v2/oauth/token/";
    Map<String, String> params = new HashMap<>();
    params.put("client_key", properties.getClientKey());
    params.put("client_secret", properties.getClientSecret());
    params.put("code", code);
    params.put("grant_type", "authorization_code");
    params.put("redirect_uri", properties.getRedirectUri());

    String responseBody = executeFormPost(endpoint, params);
    return TikTokResponses.TokenResponse.fromJson(responseBody);
  }

  public TikTokResponses.TokenResponse refreshToken(String refreshToken) {
    if (!properties.isEnabled()) {
      return mockTokenResponse("refreshed-" + UUID.randomUUID());
    }

    String endpoint = properties.getApiBaseUrl() + "/v2/oauth/token/";
    Map<String, String> params = new HashMap<>();
    params.put("client_key", properties.getClientKey());
    params.put("client_secret", properties.getClientSecret());
    params.put("grant_type", "refresh_token");
    params.put("refresh_token", refreshToken);

    String responseBody = executeFormPost(endpoint, params);
    return TikTokResponses.TokenResponse.fromJson(responseBody);
  }

  public TikTokResponses.CreatorInfoResponse queryCreatorInfo(String accessToken) {
    if (!properties.isEnabled()) {
      return mockCreatorInfoResponse();
    }

    String endpoint = properties.getApiBaseUrl() + "/v2/post/publish/creator_info/query/";
    String responseBody = executeJsonPost(endpoint, accessToken, Map.of());
    return TikTokResponses.CreatorInfoResponse.fromJson(responseBody);
  }

  public TikTokResponses.PostInitResponse initVideoUpload(
      String accessToken,
      String title,
      String privacyLevel,
      boolean disableComment,
      boolean disableDuet,
      boolean disableStitch,
      Long coverTimestampMs,
      long videoSizeBytes,
      long chunkSize,
      int totalChunks) {

    if (!properties.isEnabled()) {
      return mockPostInitResponse(title);
    }

    String endpoint = properties.getApiBaseUrl() + "/v2/post/publish/video/init/";

    Map<String, Object> postInfo = new LinkedHashMap<>();
    postInfo.put("title", title != null ? title : "");
    postInfo.put("privacy_level", privacyLevel != null ? privacyLevel : "SELF_ONLY");
    postInfo.put("disable_comment", disableComment);
    postInfo.put("disable_duet", disableDuet);
    postInfo.put("disable_stitch", disableStitch);
    if (coverTimestampMs != null) {
      postInfo.put("video_cover_timestamp_ms", coverTimestampMs);
    }

    Map<String, Object> sourceInfo = new LinkedHashMap<>();
    sourceInfo.put("source", "FILE_UPLOAD");
    sourceInfo.put("video_size", videoSizeBytes);
    sourceInfo.put("chunk_size", chunkSize);
    sourceInfo.put("total_chunk_count", totalChunks);

    Map<String, Object> requestBody = Map.of(
        "post_info", postInfo,
        "source_info", sourceInfo
    );

    String responseBody = executeJsonPost(endpoint, accessToken, requestBody);
    return TikTokResponses.PostInitResponse.fromJson(responseBody);
  }

  public void uploadVideoChunk(String uploadUrl, byte[] chunkData, long startByte, long endByte, long totalBytes) {
    if (!properties.isEnabled()) {
      if ("UPLOAD_FAIL".equalsIgnoreCase(properties.getMockScenario())) {
        throw new TikTokApiException(500, "upload_error", "Mock upload failure simulated", true, 2000L);
      }
      return;
    }

    String contentRange = String.format("bytes %d-%d/%d", startByte, endByte, totalBytes);
    HttpRequest request = HttpRequest.newBuilder(URI.create(uploadUrl))
        .PUT(HttpRequest.BodyPublishers.ofByteArray(chunkData))
        .header("Content-Type", "video/mp4")
        .header("Content-Range", contentRange)
        .timeout(Duration.ofSeconds(120))
        .build();

    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status == 429) {
        Long retryAfter = parseRetryAfter(response);
        throw new TikTokApiException(429, "rate_limit_exceeded", "TikTok upload rate limit exceeded", true, retryAfter);
      }
      if (status >= 500) {
        throw new TikTokApiException(status, "server_error", "TikTok upload server error: " + status, true, 3000L);
      }
      if (status != 200 && status != 201 && status != 204 && status != 308) {
        throw new TikTokApiException(status, "upload_rejected", "TikTok rejected chunk upload: " + response.body(), false, null);
      }
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new TikTokApiException(0, "transport_error", "Network error uploading video chunk: " + e.getMessage(), true, 2000L);
    }
  }

  public TikTokResponses.StatusResponse fetchPublishStatus(String accessToken, String publishId) {
    if (!properties.isEnabled()) {
      return mockStatusResponse(publishId);
    }

    String endpoint = properties.getApiBaseUrl() + "/v2/post/publish/status/fetch/";
    Map<String, Object> body = Map.of("publish_id", publishId);

    String responseBody = executeJsonPost(endpoint, accessToken, body);
    return TikTokResponses.StatusResponse.fromJson(responseBody);
  }

  private String executeJsonPost(String endpoint, String accessToken, Object body) {
    try {
      String json = JSON.writeValueAsString(body);
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
          .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
          .header("Content-Type", "application/json; charset=UTF-8")
          .timeout(Duration.ofSeconds(30));

      if (accessToken != null && !accessToken.isBlank()) {
        builder.header("Authorization", "Bearer " + accessToken);
      }

      HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return checkAndReturnBody(response);
    } catch (TikTokApiException e) {
      throw e;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new TikTokApiException(0, "transport_error", "Connection failed to TikTok: " + e.getMessage(), true, 3000L);
    } catch (Exception e) {
      throw new TikTokApiException(0, "client_error", "Failed to execute TikTok request: " + e.getMessage(), false, null);
    }
  }

  private String executeFormPost(String endpoint, Map<String, String> formParams) {
    try {
      StringBuilder formBody = new StringBuilder();
      for (var entry : formParams.entrySet()) {
        if (!formBody.isEmpty()) formBody.append("&");
        formBody.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
            .append("=")
            .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
      }

      HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
          .POST(HttpRequest.BodyPublishers.ofString(formBody.toString(), StandardCharsets.UTF_8))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .timeout(Duration.ofSeconds(30))
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return checkAndReturnBody(response);
    } catch (TikTokApiException e) {
      throw e;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new TikTokApiException(0, "transport_error", "Connection failed to TikTok token service: " + e.getMessage(), true, 3000L);
    } catch (Exception e) {
      throw new TikTokApiException(0, "client_error", "Token exchange failed: " + e.getMessage(), false, null);
    }
  }

  private String checkAndReturnBody(HttpResponse<String> response) {
    int status = response.statusCode();
    String body = response.body();

    if (status == 429) {
      Long retryAfter = parseRetryAfter(response);
      throw new TikTokApiException(429, "rate_limit_exceeded", "TikTok API rate limit exceeded (HTTP 429)", true, retryAfter);
    }

    if (status == 401) {
      throw new TikTokApiException(401, "unauthorized", "TikTok authorization failed or token expired", true, 1000L);
    }

    if (status >= 500) {
      throw new TikTokApiException(status, "server_error", "TikTok internal server error (" + status + ")", true, 5000L);
    }

    return body;
  }

  private Long parseRetryAfter(HttpResponse<?> response) {
    var header = response.headers().firstValue("Retry-After");
    if (header.isPresent()) {
      try {
        return Long.parseLong(header.get()) * 1000L;
      } catch (NumberFormatException ignored) {}
    }
    return 10000L;
  }

  // --- Mock Implementations ---

  private TikTokResponses.TokenResponse mockTokenResponse(String code) {
    return new TikTokResponses.TokenResponse(
        "mock_access_token_" + UUID.randomUUID(),
        86400L,
        "mock_open_id_creator1",
        31536000L,
        "mock_refresh_token_" + UUID.randomUUID(),
        "user.info.basic,video.publish,video.upload",
        "Bearer",
        new TikTokResponses.TikTokError("ok", "", "mock-log"),
        null);
  }

  private TikTokResponses.CreatorInfoResponse mockCreatorInfoResponse() {
    List<String> options = "AUDIT_PRIVATE_ONLY".equalsIgnoreCase(properties.getMockScenario())
        ? List.of("SELF_ONLY")
        : List.of("PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "SELF_ONLY");

    var data = new TikTokResponses.CreatorInfoData(
        "https://p16-sign-va.tiktokcdn.com/mock-avatar.jpeg",
        "mediafactory_studio",
        "Media Content Factory",
        options,
        false,
        false,
        false,
        600);

    return new TikTokResponses.CreatorInfoResponse(data, new TikTokResponses.TikTokError("ok", "", "mock-log"));
  }

  private TikTokResponses.PostInitResponse mockPostInitResponse(String title) {
    if ("RATE_LIMIT_429".equalsIgnoreCase(properties.getMockScenario())) {
      throw new TikTokApiException(429, "rate_limit_exceeded", "Mock TikTok rate limit hit (429)", true, 3000L);
    }

    String publishId = "v_pub_url~v2.mock." + UUID.randomUUID();
    String uploadUrl = "http://localhost:8080/api/v1/publishing/mock-upload/" + publishId;

    return new TikTokResponses.PostInitResponse(
        new TikTokResponses.PostInitData(publishId, uploadUrl),
        new TikTokResponses.TikTokError("ok", "", "mock-log"));
  }

  private TikTokResponses.StatusResponse mockStatusResponse(String publishId) {
    int count = mockPollCounts.compute(publishId, (k, v) -> v == null ? 1 : v + 1);

    if ("FAILED".equalsIgnoreCase(properties.getMockScenario())) {
      return new TikTokResponses.StatusResponse(
          new TikTokResponses.StatusData("FAILED", "Simulated mock transcode failure", List.of()),
          new TikTokResponses.TikTokError("ok", "", "mock-log"));
    }

    if ("POLL_TIMEOUT".equalsIgnoreCase(properties.getMockScenario()) && count < 10) {
      return new TikTokResponses.StatusResponse(
          new TikTokResponses.StatusData("PROCESSING_UPLOAD", null, List.of()),
          new TikTokResponses.TikTokError("ok", "", "mock-log"));
    }

    if (count <= 1) {
      return new TikTokResponses.StatusResponse(
          new TikTokResponses.StatusData("PROCESSING_UPLOAD", null, List.of()),
          new TikTokResponses.TikTokError("ok", "", "mock-log"));
    }

    String postId = String.valueOf(7350000000000000000L + Math.abs(publishId.hashCode() % 100000000L));
    return new TikTokResponses.StatusResponse(
        new TikTokResponses.StatusData("PUBLISH_COMPLETE", null, List.of(postId)),
        new TikTokResponses.TikTokError("ok", "", "mock-log"));
  }
}

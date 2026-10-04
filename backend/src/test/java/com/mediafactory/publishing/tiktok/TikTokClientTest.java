package com.mediafactory.publishing.tiktok;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TikTokClientTest {

  private MockWebServer server;
  private TikTokProperties properties;
  private TikTokClient client;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();

    properties = new TikTokProperties();
    properties.setEnabled(true);
    properties.setClientKey("test_client_key");
    properties.setClientSecret("test_client_secret");
    properties.setRedirectUri("http://localhost:3000/callback");
    properties.setApiBaseUrl(server.url("").toString().replaceAll("/$", ""));

    client = new TikTokClient(properties);
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void generatesValidAuthorizationUrlWithScopes() {
    String url = client.getAuthorizationUrl("test_state_123");

    assertThat(url).contains("client_key=test_client_key");
    assertThat(url).contains("response_type=code");
    assertThat(url).contains("scope=user.info.basic%2Cvideo.publish%2Cvideo.upload");
    assertThat(url).contains("state=test_state_123");
  }

  @Test
  void exchangesOAuthCodeSuccessfully() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""
            {
              "access_token": "act.tiktok_access_token_123",
              "expires_in": 86400,
              "open_id": "_000_open_id_456",
              "refresh_expires_in": 31536000,
              "refresh_token": "rft.tiktok_refresh_token_789",
              "scope": "user.info.basic,video.publish,video.upload",
              "token_type": "Bearer"
            }
            """));

    var res = client.exchangeCode("auth_code_xyz");

    assertThat(res.isSuccess()).isTrue();
    assertThat(res.accessToken()).isEqualTo("act.tiktok_access_token_123");
    assertThat(res.refreshToken()).isEqualTo("rft.tiktok_refresh_token_789");
    assertThat(res.openId()).isEqualTo("_000_open_id_456");

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("POST");
    assertThat(req.getPath()).isEqualTo("/v2/oauth/token/");
    assertThat(req.getHeader("Content-Type")).contains("application/x-www-form-urlencoded");
    String body = req.getBody().readString(StandardCharsets.UTF_8);
    assertThat(body).contains("grant_type=authorization_code");
    assertThat(body).contains("code=auth_code_xyz");
    assertThat(body).contains("client_key=test_client_key");
  }

  @Test
  void refreshesAccessTokenSuccessfully() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""
            {
              "access_token": "act.new_refreshed_token",
              "expires_in": 86400,
              "open_id": "_000_open_id_456",
              "refresh_expires_in": 31536000,
              "refresh_token": "rft.rotated_refresh_token",
              "scope": "user.info.basic,video.publish,video.upload",
              "token_type": "Bearer"
            }
            """));

    var res = client.refreshToken("rft.old_refresh_token");

    assertThat(res.isSuccess()).isTrue();
    assertThat(res.accessToken()).isEqualTo("act.new_refreshed_token");
    assertThat(res.refreshToken()).isEqualTo("rft.rotated_refresh_token");

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("POST");
    assertThat(req.getPath()).isEqualTo("/v2/oauth/token/");
    String body = req.getBody().readString(StandardCharsets.UTF_8);
    assertThat(body).contains("grant_type=refresh_token");
    assertThat(body).contains("refresh_token=rft.old_refresh_token");
  }

  @Test
  void queriesCreatorInfoSuccessfully() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""
            {
              "data": {
                "creator_avatar_url": "https://avatar.tiktok.com/creator.png",
                "creator_username": "mediafactory_official",
                "creator_nickname": "Media Content Factory",
                "privacy_level_options": ["PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "SELF_ONLY"],
                "comment_disabled": false,
                "duet_disabled": false,
                "stitch_disabled": false,
                "max_video_post_duration_sec": 600
              },
              "error": { "code": "ok", "message": "" }
            }
            """));

    var res = client.queryCreatorInfo("act.valid_token");

    assertThat(res.isSuccess()).isTrue();
    assertThat(res.data().creatorUsername()).isEqualTo("mediafactory_official");
    assertThat(res.data().creatorNickname()).isEqualTo("Media Content Factory");
    assertThat(res.data().privacyLevelOptions()).containsExactly("PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "SELF_ONLY");

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("POST");
    assertThat(req.getPath()).isEqualTo("/v2/post/publish/creator_info/query/");
    assertThat(req.getHeader("Authorization")).isEqualTo("Bearer act.valid_token");
  }

  @Test
  void initializesVideoUploadSuccessfully() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""
            {
              "data": {
                "publish_id": "v_pub_url~v2.1234567890",
                "upload_url": "https://upload.tiktok.com/video/chunk_1"
              },
              "error": { "code": "ok", "message": "" }
            }
            """));

    var res = client.initVideoUpload(
        "act.token", "BMW M4 #drift", "PUBLIC_TO_EVERYONE",
        false, false, false, 1000L, 50000000L, 50000000L, 1);

    assertThat(res.isSuccess()).isTrue();
    assertThat(res.data().publishId()).isEqualTo("v_pub_url~v2.1234567890");
    assertThat(res.data().uploadUrl()).isEqualTo("https://upload.tiktok.com/video/chunk_1");

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("POST");
    assertThat(req.getPath()).isEqualTo("/v2/post/publish/video/init/");
    String body = req.getBody().readString(StandardCharsets.UTF_8);
    assertThat(body).contains("\"title\":\"BMW M4 #drift\"");
    assertThat(body).contains("\"privacy_level\":\"PUBLIC_TO_EVERYONE\"");
    assertThat(body).contains("\"source\":\"FILE_UPLOAD\"");
  }

  @Test
  void uploadsVideoChunkWithContentRangeHeader() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200));

    byte[] chunk = "dummy video payload".getBytes(StandardCharsets.UTF_8);
    String uploadUrl = server.url("/upload_chunk").toString();

    client.uploadVideoChunk(uploadUrl, chunk, 0, chunk.length - 1, chunk.length);

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("PUT");
    assertThat(req.getPath()).isEqualTo("/upload_chunk");
    assertThat(req.getHeader("Content-Type")).isEqualTo("video/mp4");
    assertThat(req.getHeader("Content-Range")).isEqualTo("bytes 0-" + (chunk.length - 1) + "/" + chunk.length);
  }

  @Test
  void fetchesStatusAndExtractsPostIdWhenPublishComplete() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""
            {
              "data": {
                "status": "PUBLISH_COMPLETE",
                "fail_reason": null,
                "publicaly_available_post_id": [7351234567890123456]
              },
              "error": { "code": "ok", "message": "" }
            }
            """));

    var res = client.fetchPublishStatus("act.token", "v_pub_url~v2.1234567890");

    assertThat(res.isSuccess()).isTrue();
    assertThat(res.data().status()).isEqualTo("PUBLISH_COMPLETE");
    assertThat(res.getPostId()).isEqualTo("7351234567890123456");

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("POST");
    assertThat(req.getPath()).isEqualTo("/v2/post/publish/status/fetch/");
    String body = req.getBody().readString(StandardCharsets.UTF_8);
    assertThat(body).contains("\"publish_id\":\"v_pub_url~v2.1234567890\"");
  }

  @Test
  void handlesHttp429RateLimitWithRetryAfterHeader() {
    server.enqueue(new MockResponse()
        .setResponseCode(429)
        .setHeader("Retry-After", "12")
        .setBody("Rate limit exceeded"));

    assertThatThrownBy(() -> client.fetchPublishStatus("act.token", "v_pub_123"))
        .isInstanceOf(TikTokApiException.class)
        .satisfies(e -> {
          TikTokApiException te = (TikTokApiException) e;
          assertThat(te.getStatusCode()).isEqualTo(429);
          assertThat(te.isRetryable()).isTrue();
          assertThat(te.getRetryAfterMs()).isEqualTo(12000L);
        });
  }

  @Test
  void handlesHttp500ServerErrorAsRetryable() {
    server.enqueue(new MockResponse()
        .setResponseCode(503)
        .setBody("Service Temporarily Unavailable"));

    assertThatThrownBy(() -> client.queryCreatorInfo("act.token"))
        .isInstanceOf(TikTokApiException.class)
        .satisfies(e -> {
          TikTokApiException te = (TikTokApiException) e;
          assertThat(te.getStatusCode()).isEqualTo(503);
          assertThat(te.isRetryable()).isTrue();
        });
  }
}

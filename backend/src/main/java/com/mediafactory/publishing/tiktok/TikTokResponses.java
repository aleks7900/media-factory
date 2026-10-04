package com.mediafactory.publishing.tiktok;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class TikTokResponses {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public record TikTokError(String code, String message, String logId) {}

  public record TokenResponse(
      String accessToken,
      Long expiresIn,
      String openId,
      Long refreshExpiresIn,
      String refreshToken,
      String scope,
      String tokenType,
      TikTokError error,
      String errorDescription) {

    public boolean isSuccess() {
      return (error == null || "ok".equalsIgnoreCase(error.code())) && accessToken != null && !accessToken.isBlank();
    }

    public static TokenResponse fromJson(String json) {
      try {
        JsonNode root = JSON.readTree(json);
        String accessToken = root.path("access_token").asText(null);
        Long expiresIn = root.has("expires_in") ? root.path("expires_in").asLong() : null;
        String openId = root.path("open_id").asText(null);
        Long refreshExpiresIn = root.has("refresh_expires_in") ? root.path("refresh_expires_in").asLong() : null;
        String refreshToken = root.path("refresh_token").asText(null);
        String scope = root.path("scope").asText(null);
        String tokenType = root.path("token_type").asText("Bearer");
        String errorDesc = root.path("error_description").asText(null);

        TikTokError error = null;
        if (root.has("error")) {
          JsonNode errNode = root.path("error");
          error = new TikTokError(
              errNode.path("code").asText("error"),
              errNode.path("message").asText(""),
              errNode.path("log_id").asText(""));
        } else if (root.has("error_code")) {
          error = new TikTokError(root.path("error_code").asText("error"), errorDesc != null ? errorDesc : "", "");
        }

        return new TokenResponse(accessToken, expiresIn, openId, refreshExpiresIn, refreshToken, scope, tokenType, error, errorDesc);
      } catch (Exception e) {
        return new TokenResponse(null, null, null, null, null, null, null,
            new TikTokError("PARSE_ERROR", e.getMessage(), ""), e.getMessage());
      }
    }
  }

  public record CreatorInfoData(
      String creatorAvatarUrl,
      String creatorUsername,
      String creatorNickname,
      List<String> privacyLevelOptions,
      Boolean commentDisabled,
      Boolean duetDisabled,
      Boolean stitchDisabled,
      Integer maxVideoPostDurationSec) {}

  public record CreatorInfoResponse(CreatorInfoData data, TikTokError error) {
    public boolean isSuccess() {
      return (error == null || "ok".equalsIgnoreCase(error.code())) && data != null;
    }

    public static CreatorInfoResponse fromJson(String json) {
      try {
        JsonNode root = JSON.readTree(json);
        TikTokError error = parseError(root);
        JsonNode dataNode = root.path("data");
        if (dataNode.isMissingNode() || dataNode.isNull()) {
          return new CreatorInfoResponse(null, error);
        }

        List<String> privacyOptions = new ArrayList<>();
        JsonNode opts = dataNode.path("privacy_level_options");
        if (opts.isArray()) {
          for (JsonNode opt : opts) {
            privacyOptions.add(opt.asText());
          }
        }
        if (privacyOptions.isEmpty()) {
          privacyOptions.add("SELF_ONLY");
        }

        CreatorInfoData data = new CreatorInfoData(
            dataNode.path("creator_avatar_url").asText(null),
            dataNode.path("creator_username").asText(null),
            dataNode.path("creator_nickname").asText(null),
            privacyOptions,
            dataNode.has("comment_disabled") ? dataNode.path("comment_disabled").asBoolean() : null,
            dataNode.has("duet_disabled") ? dataNode.path("duet_disabled").asBoolean() : null,
            dataNode.has("stitch_disabled") ? dataNode.path("stitch_disabled").asBoolean() : null,
            dataNode.has("max_video_post_duration_sec") ? dataNode.path("max_video_post_duration_sec").asInt() : null);

        return new CreatorInfoResponse(data, error);
      } catch (Exception e) {
        return new CreatorInfoResponse(null, new TikTokError("PARSE_ERROR", e.getMessage(), ""));
      }
    }
  }

  public record PostInitData(String publishId, String uploadUrl) {}

  public record PostInitResponse(PostInitData data, TikTokError error) {
    public boolean isSuccess() {
      return (error == null || "ok".equalsIgnoreCase(error.code())) && data != null && data.publishId != null;
    }

    public static PostInitResponse fromJson(String json) {
      try {
        JsonNode root = JSON.readTree(json);
        TikTokError error = parseError(root);
        JsonNode dataNode = root.path("data");
        if (dataNode.isMissingNode() || dataNode.isNull()) {
          return new PostInitResponse(null, error);
        }

        PostInitData data = new PostInitData(
            dataNode.path("publish_id").asText(null),
            dataNode.path("upload_url").asText(null));
        return new PostInitResponse(data, error);
      } catch (Exception e) {
        return new PostInitResponse(null, new TikTokError("PARSE_ERROR", e.getMessage(), ""));
      }
    }
  }

  public record StatusData(String status, String failReason, List<String> publiclyAvailablePostId) {}

  public record StatusResponse(StatusData data, TikTokError error) {
    public boolean isSuccess() {
      return (error == null || "ok".equalsIgnoreCase(error.code())) && data != null;
    }

    public String getPostId() {
      if (data != null && data.publiclyAvailablePostId != null && !data.publiclyAvailablePostId.isEmpty()) {
        return data.publiclyAvailablePostId.get(0);
      }
      return null;
    }

    public static StatusResponse fromJson(String json) {
      try {
        JsonNode root = JSON.readTree(json);
        TikTokError error = parseError(root);
        JsonNode dataNode = root.path("data");
        if (dataNode.isMissingNode() || dataNode.isNull()) {
          return new StatusResponse(null, error);
        }

        List<String> postIds = new ArrayList<>();
        JsonNode idsNode = dataNode.path("publicaly_available_post_id");
        if (idsNode.isArray()) {
          for (JsonNode id : idsNode) {
            postIds.add(id.asText());
          }
        }

        StatusData data = new StatusData(
            dataNode.path("status").asText("PROCESSING"),
            dataNode.path("fail_reason").asText(null),
            postIds);

        return new StatusResponse(data, error);
      } catch (Exception e) {
        return new StatusResponse(null, new TikTokError("PARSE_ERROR", e.getMessage(), ""));
      }
    }
  }

  private static TikTokError parseError(JsonNode root) {
    if (root.has("error")) {
      JsonNode errNode = root.path("error");
      return new TikTokError(
          errNode.path("code").asText("ok"),
          errNode.path("message").asText(""),
          errNode.path("log_id").asText(""));
    }
    return new TikTokError("ok", "", "");
  }
}

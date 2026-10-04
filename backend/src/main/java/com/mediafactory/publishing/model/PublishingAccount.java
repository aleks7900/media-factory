package com.mediafactory.publishing.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PublishingAccount(
    UUID id,
    PublishingPlatform platform,
    String accountId,
    String username,
    String displayName,
    String avatarUrl,
    String accessToken,
    String refreshToken,
    Instant tokenExpiresAt,
    Instant refreshExpiresAt,
    String scopes,
    List<String> privacyLevelOptions,
    boolean commentDisabled,
    boolean duetDisabled,
    boolean stitchDisabled,
    int maxVideoPostDurationSec,
    boolean isActive,
    Instant createdAt,
    Instant updatedAt) {

  public PublishingAccountDto toDto() {
    return new PublishingAccountDto(
        id,
        platform.name(),
        accountId,
        username,
        displayName,
        avatarUrl,
        scopes,
        privacyLevelOptions,
        commentDisabled,
        duetDisabled,
        stitchDisabled,
        maxVideoPostDurationSec,
        isActive,
        tokenExpiresAt != null && tokenExpiresAt.isAfter(Instant.now()),
        createdAt);
  }

  @Override
  public String toString() {
    return "PublishingAccount[id="
        + id
        + ", platform="
        + platform
        + ", username="
        + username
        + ", accountId="
        + accountId
        + ", credentials=[REDACTED]]";
  }
}

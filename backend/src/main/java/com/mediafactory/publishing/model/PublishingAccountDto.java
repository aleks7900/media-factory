package com.mediafactory.publishing.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PublishingAccountDto(
    UUID id,
    String platform,
    String accountId,
    String username,
    String displayName,
    String avatarUrl,
    String scopes,
    List<String> privacyLevelOptions,
    boolean commentDisabled,
    boolean duetDisabled,
    boolean stitchDisabled,
    int maxVideoPostDurationSec,
    boolean isActive,
    boolean isAuthorized,
    Instant createdAt) {}

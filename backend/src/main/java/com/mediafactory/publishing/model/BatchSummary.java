package com.mediafactory.publishing.model;

import java.time.Instant;
import java.util.UUID;

public record BatchSummary(
    UUID id,
    UUID projectId,
    UUID accountId,
    String accountUsername,
    String platform,
    String name,
    String status,
    int totalVideos,
    int published,
    int processing,
    int queued,
    int failed,
    int cancelled,
    int draft,
    boolean paused,
    boolean isCancelled,
    Instant createdAt,
    Instant completedAt) {}

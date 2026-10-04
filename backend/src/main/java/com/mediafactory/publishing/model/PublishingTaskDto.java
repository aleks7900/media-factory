package com.mediafactory.publishing.model;

import java.time.Instant;
import java.util.UUID;

public record PublishingTaskDto(
    UUID id,
    UUID batchId,
    String platform,
    String videoFilename,
    long videoSizeBytes,
    Double durationSeconds,
    Integer width,
    Integer height,
    String caption,
    String privacyLevel,
    boolean disableComment,
    boolean disableDuet,
    boolean disableStitch,
    String status,
    String validationError,
    String publishId,
    String postId,
    String postUrl,
    int attempts,
    String lastErrorCode,
    String lastErrorMessage,
    Instant createdAt,
    Instant completedAt) {}

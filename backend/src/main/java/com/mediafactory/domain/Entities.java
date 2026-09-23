package com.mediafactory.domain;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.math.BigDecimal;

/** Immutable domain snapshots; persistence relationships use explicit foreign keys. */
public final class Entities {
 private Entities() {}
 public record Project(UUID id, String name, String description, OffsetDateTime createdAt) {}
 public record Collection(UUID id, UUID projectId, String name, OffsetDateTime createdAt) {}
 public record Concept(UUID id, UUID collectionId, String name, String prompt) {}
 public record Generation(UUID id, UUID conceptId, UUID parentId, GenerationStatus status, String prompt, int width, int height) {}
 public record Asset(UUID id, UUID generationId, String storageKey, String sha256, String mediaType, long sizeBytes, int width, int height) {}
 public record AssetVariant(UUID id, UUID assetId, String kind, String storageKey, String sha256, long sizeBytes) {}
 public record QualityReview(UUID id, UUID assetId, String kind, String decision, String reasons) {}
 public record Publication(UUID id, UUID assetId, String channel, String externalId, OffsetDateTime publishedAt) {}
 public record PerformanceMetric(UUID id, UUID publicationId, String name, BigDecimal value, OffsetDateTime measuredAt) {}
 public record GenerationCost(UUID id, UUID generationId, UUID jobId, int attempt, String provider, String model, String operation, long inputUsage, long outputUsage, BigDecimal estimatedCost, String currency) {}
}

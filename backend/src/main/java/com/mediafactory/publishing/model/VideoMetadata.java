package com.mediafactory.publishing.model;

public record VideoMetadata(
    String caption,
    String privacyLevel,
    Boolean disableComment,
    Boolean disableDuet,
    Boolean disableStitch,
    Long coverTimestampMs) {}

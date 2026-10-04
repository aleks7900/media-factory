package com.mediafactory.publishing.model;

import java.util.List;
import java.util.UUID;

public record BatchPreview(
    UUID batchId,
    String batchName,
    String platform,
    PublishingAccountDto targetAccount,
    int totalCount,
    int validCount,
    int invalidCount,
    List<PublishingTaskDto> tasks) {}

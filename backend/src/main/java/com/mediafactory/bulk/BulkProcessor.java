package com.mediafactory.bulk;

import java.math.BigDecimal;
import java.util.*;

/** Batch lifecycle is shared; processors translate only provider requests/results. */
public interface BulkProcessor {
  record Input(
      UUID taskId,
      UUID generationId,
      UUID attemptId,
      String provider,
      String model,
      String prompt,
      Map<String, Object> options,
      List<BulkArchiveParser.Reference> references) {}

  record Quote(BigDecimal amount, String currency) {}

  record Output(
      byte[] bytes,
      String mediaType,
      Map<String, Object> metadata,
      Long inputUsage,
      Long outputUsage,
      BigDecimal estimatedCost,
      BigDecimal actualCost,
      String currency) {}

  String kind();

  void validate(Input input);

  Quote estimate(Input input);

  boolean replaySafe(Input input);

  default boolean asynchronous() {
    return false;
  }

  default String submit(Input input) {
    throw new UnsupportedOperationException();
  }

  default String status(String remoteId, Input input) {
    return "SUCCEEDED";
  }

  Output result(String remoteId, Input input);
}

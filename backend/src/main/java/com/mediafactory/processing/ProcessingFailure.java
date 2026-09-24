package com.mediafactory.processing;

public class ProcessingFailure extends RuntimeException {
  private final String code;

  public ProcessingFailure(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return java.util.Set.of(
            "WORKER_UNAVAILABLE", "WORKER_BUSY", "WORKER_FAILURE", "STORAGE_UNAVAILABLE")
        .contains(code);
  }
}

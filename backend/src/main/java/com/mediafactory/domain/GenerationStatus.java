package com.mediafactory.domain;
import java.util.Set;
public enum GenerationStatus {
    CREATED, QUEUED, GENERATING, GENERATED, QA_PENDING, APPROVED, REJECTED, FAILED, PUBLISHED;
    public boolean canTransitionTo(GenerationStatus next) {
        return switch(this) {
            case CREATED -> next == QUEUED;
            case QUEUED -> next == GENERATING;
            case GENERATING -> Set.of(GENERATED, FAILED, QUEUED).contains(next);
            case GENERATED -> next == QA_PENDING;
            case QA_PENDING -> Set.of(APPROVED, REJECTED).contains(next);
            case APPROVED -> next == PUBLISHED;
            case FAILED -> next == QUEUED;
            default -> false;
        };
    }
}

package com.mediafactory.provider.resilience;

import com.mediafactory.provider.ImageGenerationProperties.Retry;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.stereotype.Service;

@Service
public class RetryDecisionService {

  public Decision decide(ImageGenerationException error, int attempt, Retry policy,
      boolean replaySafe, boolean hasFallback) {
    return decide(error, attempt, policy, replaySafe, hasFallback,
        () -> ThreadLocalRandom.current().nextDouble());
  }

  public Decision decide(ImageGenerationException error, int attempt, Retry policy,
      boolean replaySafe, boolean hasFallback, DoubleSupplier random) {
    boolean ambiguous = error.outcomeUnknown() && !replaySafe && !policy.retryAmbiguous();
    boolean retry = !ambiguous && error.retryable() && attempt < policy.maxAttempts();
    double base = Math.min(policy.maxDelay().toMillis(),
        policy.initialDelay().toMillis() * Math.pow(policy.multiplier(), Math.max(0, attempt - 1)));
    long backoff = (long) (base * (policy.jitter() ? 0.5 + random.getAsDouble() * 0.5 : 1));
    var delay = Duration.ofMillis(Math.max(backoff, error.retryAfter().toMillis()));
    return new Decision(retry, !retry && !ambiguous && error.fallbackEligible() && hasFallback,
        delay, ambiguous);
  }

  public record Decision(boolean retry, boolean fallback, Duration delay,
                         boolean recoveryRequired) {

  }
}

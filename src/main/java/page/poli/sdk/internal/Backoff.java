package page.poli.sdk.internal;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Pure exponential-backoff calculator with injectable jitter.
 *
 * <p>Formula per {@code sdk-java-plan.md} §5:
 *
 * <pre>{@code delay = baseDelay × 2^attempt × jitter, where jitter ∈ [0.5, 1.5)}</pre>
 *
 * <p>Production uses {@link #defaultJitter()} which pulls from {@link ThreadLocalRandom}. Tests
 * inject a deterministic {@link DoubleSupplier} (e.g. {@code () -> 1.0}) for reproducible
 * assertions on the computed durations.
 */
public final class Backoff {

  private static final double JITTER_MIN = 0.5;
  private static final double JITTER_MAX = 1.5;

  private final DoubleSupplier jitter;

  /**
   * @param jitter source of jitter values; each invocation should return a value in
   *     {@code [0.5, 1.5)} (production) or any fixed value (tests)
   */
  public Backoff(DoubleSupplier jitter) {
    this.jitter = jitter;
  }

  /**
   * Build an instance whose jitter is uniformly drawn from {@code [0.5, 1.5)} via the
   * thread-local PRNG. Cheap to allocate; one instance per {@link RetryLoop} is plenty.
   *
   * @return a new {@code Backoff} backed by {@link ThreadLocalRandom}
   */
  public static Backoff defaultJitter() {
    return new Backoff(() -> ThreadLocalRandom.current().nextDouble(JITTER_MIN, JITTER_MAX));
  }

  /**
   * Compute the delay before the next attempt.
   *
   * @param attempt zero-based attempt index — {@code 0} means "before the first retry, i.e. after
   *     attempt 0 failed"
   * @param baseDelay configured base delay
   * @return computed delay rounded to milliseconds
   */
  public Duration compute(int attempt, Duration baseDelay) {
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must be >= 0, got: " + attempt);
    }
    long base = baseDelay.toMillis();
    // 2^attempt overflows for attempt >= 63; clamp the shift to avoid surprises if a buggy caller
    // ever passes a huge number. Real retry budgets cap at a single digit anyway.
    int shift = Math.min(attempt, 30);
    long exponential = base << shift;
    double withJitter = exponential * jitter.getAsDouble();
    return Duration.ofMillis(Math.round(withJitter));
  }
}

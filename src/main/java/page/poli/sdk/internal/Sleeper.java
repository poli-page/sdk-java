package page.poli.sdk.internal;

import java.time.Duration;

/**
 * Injection seam for blocking sleeps inside {@link RetryLoop}. Production uses {@link #THREAD},
 * which is a thin wrapper over {@link Thread#sleep(long)}. Tests inject a recording / no-op
 * implementation so the suite finishes in milliseconds instead of seconds.
 */
@FunctionalInterface
public interface Sleeper {

  /** Default production implementation backed by {@link Thread#sleep(long)}. */
  Sleeper THREAD = duration -> Thread.sleep(duration.toMillis());

  /**
   * Block the current thread for the requested duration.
   *
   * @param duration how long to sleep; must be non-negative
   * @throws InterruptedException if the thread is interrupted during the sleep
   */
  void sleep(Duration duration) throws InterruptedException;
}

package page.poli.sdk.internal;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Injection seam for blocking sleeps inside {@link RetryLoop}. Production uses {@link #THREAD}.
 * Tests inject a recording / no-op implementation so the suite finishes in milliseconds instead of
 * seconds.
 */
@FunctionalInterface
public interface Sleeper {

  /**
   * Default production implementation. The wait is never shorter than asked: should the thread wake
   * early, it sleeps again for the rest, measured on the monotonic clock.
   */
  Sleeper THREAD =
      duration -> {
        long left = Timers.saturatedNanos(duration);
        long end = System.nanoTime() + left;
        while (left > 0) {
          TimeUnit.NANOSECONDS.sleep(left);
          left = end - System.nanoTime();
        }
      };

  /**
   * Block the current thread for the requested duration.
   *
   * @param duration how long to sleep; must be non-negative
   * @throws InterruptedException if the thread is interrupted during the sleep
   */
  void sleep(Duration duration) throws InterruptedException;
}

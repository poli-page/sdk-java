package page.poli.sdk.internal;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * One daemon thread for the async path's timers: the per-attempt timeout and the wait before a
 * retry. A timer only hands its task to the common async executor, so a slow hook never delays
 * another timer. Cancelled timers are removed at once.
 */
final class Timers {

  private static final ScheduledThreadPoolExecutor TIMER = newTimer();

  private Timers() {}

  /**
   * Run {@code task} on the common async executor once {@code delay} has passed on the monotonic
   * clock.
   *
   * @param task what to run
   * @param delay how long to wait first
   * @return the timer, to cancel it
   */
  static ScheduledFuture<?> schedule(Runnable task, Duration delay) {
    return TIMER.schedule(
        () -> CompletableFuture.runAsync(task), saturatedNanos(delay), TimeUnit.NANOSECONDS);
  }

  /**
   * @param delay a duration
   * @return the duration in nanoseconds, {@link Long#MAX_VALUE} when it does not fit
   */
  static long saturatedNanos(Duration delay) {
    try {
      return Math.max(0L, delay.toNanos());
    } catch (ArithmeticException tooLong) {
      return Long.MAX_VALUE;
    }
  }

  private static ScheduledThreadPoolExecutor newTimer() {
    ScheduledThreadPoolExecutor timer =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = new Thread(runnable, "poli-page-sdk-timer");
              thread.setDaemon(true);
              return thread;
            });
    timer.setRemoveOnCancelPolicy(true);
    return timer;
  }
}

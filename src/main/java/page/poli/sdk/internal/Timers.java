package page.poli.sdk.internal;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * One daemon thread for the SDK's timers: the per-attempt timeout and the wait before a retry of
 * the async path, and the idle timeout of a streamed download. A timer hands its task to the common
 * async executor, so a slow hook never delays another timer; only the idle timeout, which just
 * closes the body, runs on the timer thread. Cancelled timers are removed at once.
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
   * Run {@code task} on the timer thread itself once {@code delay} has passed, so a busy common
   * pool cannot delay it. Only for a task that is short and never blocks.
   *
   * @param task what to run
   * @param delay how long to wait first
   * @return the timer, to cancel it
   */
  static ScheduledFuture<?> scheduleOnTimerThread(Runnable task, Duration delay) {
    return TIMER.schedule(task, saturatedNanos(delay), TimeUnit.NANOSECONDS);
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

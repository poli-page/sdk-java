package page.poli.sdk.internal;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import page.poli.sdk.PoliPageErrorCode;
import page.poli.sdk.exception.PoliPageException;

/**
 * The future an async SDK call returns. {@link #cancel(boolean)} aborts the call: it cancels the
 * work in flight (an attempt, a retry wait, the download), fires {@code onError} once with the
 * SDK's {@code aborted} error, and completes this future with a {@link CancellationException} whose
 * {@linkplain CancellationException#getCause() cause} is that error. The {@code Future} contract
 * holds: {@link #isCancelled()} is {@code true} and {@code get()} / {@code join()} throw that
 * {@code CancellationException}, the same one on every JDK.
 *
 * <p>Stages chained on it ({@code thenApply}…) are plain {@code CompletableFuture}s.
 *
 * @param <T> the call's value
 */
public final class CallFuture<T> extends CompletableFuture<T> {

  private final Hooks hooks;
  private final List<Runnable> onAbort = new CopyOnWriteArrayList<>();

  CallFuture(Hooks hooks) {
    this.hooks = hooks;
  }

  /**
   * Register what stops the work in flight when the call is aborted. It runs at once if the call
   * was already aborted.
   *
   * @param action cancels a stage, closes a stream…
   */
  public void onAbort(Runnable action) {
    onAbort.add(action);
    if (isCancelled()) {
      action.run();
    }
  }

  /**
   * Abort the call. Whatever {@code mayInterruptIfRunning}, the work in flight is cancelled.
   *
   * @param mayInterruptIfRunning ignored
   * @return {@code true} if the call is aborted: by this call, or by an earlier one
   */
  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    return abort(true);
  }

  /** Abort as part of an enclosing call, which reports the {@code aborted} error itself. */
  void abortQuietly() {
    abort(false);
  }

  private boolean abort(boolean report) {
    if (isDone()) {
      return isCancelled();
    }
    PoliPageException aborted =
        new PoliPageException(PoliPageErrorCode.ABORTED, 0, "Request was aborted", null, null);
    CancellationException cancelled = new CancellationException("Request was aborted");
    cancelled.initCause(aborted);
    if (!completeExceptionally(cancelled)) {
      // Another cancel, or the call's own outcome, got there first: nothing more to report.
      return isCancelled();
    }
    for (Runnable action : onAbort) {
      action.run();
    }
    if (report) {
      hooks.error(aborted);
    }
    return true;
  }

  // Later JDKs throw a new CancellationException from join() / get(), with the one this future
  // completed with as its cause. Throw that one, so getCause() is the aborted error on every JDK.

  @Override
  public T join() {
    try {
      return super.join();
    } catch (CancellationException e) {
      throw own(e);
    }
  }

  @Override
  public T get() throws InterruptedException, ExecutionException {
    try {
      return super.get();
    } catch (CancellationException e) {
      throw own(e);
    }
  }

  @Override
  public T get(long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    try {
      return super.get(timeout, unit);
    } catch (CancellationException e) {
      throw own(e);
    }
  }

  @Override
  public T getNow(T valueIfAbsent) {
    try {
      return super.getNow(valueIfAbsent);
    } catch (CancellationException e) {
      throw own(e);
    }
  }

  /** The exception this future completed with, unwrapped from the JDK's own if it wraps it. */
  private static CancellationException own(CancellationException thrown) {
    if (thrown.getCause() instanceof CancellationException completedWith
        && completedWith.getCause() instanceof PoliPageException) {
      return completedWith;
    }
    return thrown;
  }

  @Override
  public <U> CompletableFuture<U> newIncompleteFuture() {
    return new CompletableFuture<>();
  }
}

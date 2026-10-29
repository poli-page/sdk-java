package page.poli.sdk.internal;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
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
  private volatile @Nullable Runnable onAbort;
  private volatile @Nullable CancellationException cancellation;

  CallFuture(Hooks hooks) {
    this.hooks = hooks;
  }

  /**
   * @param action what cancels the work in flight; runs at once if the call was already aborted
   */
  void onAbort(Runnable action) {
    onAbort = action;
    if (isCancelled()) {
      action.run();
    }
  }

  /**
   * Abort the call. Whatever {@code mayInterruptIfRunning}, the work in flight is cancelled.
   *
   * @param mayInterruptIfRunning ignored
   * @return {@code true} if this call ended here
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
    PoliPageException aborted =
        new PoliPageException(PoliPageErrorCode.ABORTED, 0, "Request was aborted", null, null);
    CancellationException cancelled = new CancellationException("Request was aborted");
    cancelled.initCause(aborted);
    cancellation = cancelled;
    if (!completeExceptionally(cancelled)) {
      cancellation = null;
      return false;
    }
    Runnable action = onAbort;
    if (action != null) {
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

  private CancellationException own(CancellationException thrown) {
    CancellationException cancelled = cancellation;
    return cancelled != null ? cancelled : thrown;
  }

  @Override
  public <U> CompletableFuture<U> newIncompleteFuture() {
    return new CompletableFuture<>();
  }
}

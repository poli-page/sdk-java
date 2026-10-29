package page.poli.sdk.internal;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Builds the {@link CallFuture}s the async facades return. Cancelling one cancels the stage in
 * flight upstream (an attempt, a retry wait, the download), which {@link
 * CompletableFuture#thenApply} and {@link CompletableFuture#thenCompose} do not do. A value that
 * arrives after the call was cancelled is closed when it is closeable (a PDF stream), so no
 * connection leaks. Errors reach the returned future unwrapped, so {@code join()} throws {@code
 * CompletionException(PoliPageException)}.
 */
public final class Futures {

  private final Hooks hooks;

  /**
   * @param hooks the client's hooks; {@code onError} fires when a call is cancelled
   */
  public Futures(Hooks hooks) {
    this.hooks = hooks;
  }

  /**
   * @param upstream the stage in flight
   * @param fn maps its value; a thrown exception fails the returned future
   * @param <T> upstream value type
   * @param <R> result type
   * @return a future of {@code fn(value)}; cancelling it cancels {@code upstream}
   */
  public <T, R> CallFuture<R> map(
      CompletableFuture<T> upstream, Function<? super T, ? extends R> fn) {
    CallFuture<R> result = new CallFuture<>(hooks);
    result.onAbort(() -> cancelStage(upstream));
    upstream.whenComplete(
        (value, error) -> {
          if (error != null) {
            result.completeExceptionally(RetryLoop.unwrap(error));
          } else if (result.isDone()) {
            discard(value);
          } else {
            deliver(result, value, fn);
          }
        });
    return result;
  }

  /**
   * @param upstream the first stage
   * @param fn starts the second stage from the first one's value
   * @param <T> first value type
   * @param <R> result type
   * @return a future of the second stage's value; cancelling it cancels the stage in flight
   */
  public <T, R> CallFuture<R> compose(
      CompletableFuture<T> upstream, Function<? super T, CompletableFuture<R>> fn) {
    CallFuture<R> result = new CallFuture<>(hooks);
    result.onAbort(() -> cancelStage(upstream));
    upstream.whenComplete(
        (value, error) -> {
          if (error != null) {
            result.completeExceptionally(RetryLoop.unwrap(error));
            return;
          }
          if (result.isDone()) {
            discard(value);
            return;
          }
          CompletableFuture<R> next;
          try {
            next = fn.apply(value);
          } catch (Throwable t) {
            result.completeExceptionally(t);
            return;
          }
          result.onAbort(() -> cancelStage(next));
          next.whenComplete(
              (second, secondError) -> {
                if (secondError != null) {
                  result.completeExceptionally(RetryLoop.unwrap(secondError));
                } else if (!result.complete(second)) {
                  discard(second);
                }
              });
        });
    return result;
  }

  private static <T, R> void deliver(
      CallFuture<R> result, T value, Function<? super T, ? extends R> fn) {
    R mapped;
    try {
      mapped = fn.apply(value);
    } catch (Throwable t) {
      result.completeExceptionally(t);
      return;
    }
    if (!result.complete(mapped)) {
      discard(mapped);
    }
  }

  /** Cancel a stage of an aborted call; a nested call's own future does not report it again. */
  private static void cancelStage(CompletableFuture<?> stage) {
    if (stage instanceof CallFuture<?> call) {
      call.abortQuietly();
    } else {
      stage.cancel(true);
    }
  }

  /** Close a value nobody will read, such as a PDF stream that arrived after a cancel. */
  static void discard(@Nullable Object value) {
    if (value instanceof AutoCloseable closeable) {
      try {
        closeable.close();
      } catch (Exception ignored) {
        // nobody reads it
      }
    }
  }
}

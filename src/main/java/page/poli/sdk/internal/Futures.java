package page.poli.sdk.internal;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Chaining for the async facades that keeps cancellation working: cancelling the future handed to
 * the caller cancels the stage in flight upstream (an attempt, a retry wait, the download), which
 * {@link CompletableFuture#thenApply} and {@link CompletableFuture#thenCompose} do not do. Errors
 * reach the returned future unwrapped, so {@code join()} throws {@code
 * CompletionException(PoliPageException)} as before.
 */
public final class Futures {

  private Futures() {}

  /**
   * @param upstream the stage in flight
   * @param fn maps its value; a thrown exception fails the returned future
   * @param <T> upstream value type
   * @param <R> result type
   * @return a future of {@code fn(value)}; cancelling it cancels {@code upstream}
   */
  public static <T, R> CompletableFuture<R> map(
      CompletableFuture<T> upstream, Function<? super T, ? extends R> fn) {
    CompletableFuture<R> result = new CompletableFuture<>();
    cancelWith(result, upstream);
    upstream.whenComplete(
        (value, error) -> {
          if (error != null) {
            result.completeExceptionally(RetryLoop.unwrap(error));
            return;
          }
          try {
            result.complete(fn.apply(value));
          } catch (Throwable t) {
            result.completeExceptionally(t);
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
  public static <T, R> CompletableFuture<R> compose(
      CompletableFuture<T> upstream, Function<? super T, CompletableFuture<R>> fn) {
    CompletableFuture<R> result = new CompletableFuture<>();
    cancelWith(result, upstream);
    upstream.whenComplete(
        (value, error) -> {
          if (error != null) {
            result.completeExceptionally(RetryLoop.unwrap(error));
            return;
          }
          CompletableFuture<R> next;
          try {
            next = fn.apply(value);
          } catch (Throwable t) {
            result.completeExceptionally(t);
            return;
          }
          cancelWith(result, next);
          next.whenComplete(
              (second, secondError) -> {
                if (secondError != null) {
                  result.completeExceptionally(RetryLoop.unwrap(secondError));
                } else {
                  result.complete(second);
                }
              });
        });
    return result;
  }

  private static void cancelWith(CompletableFuture<?> result, CompletableFuture<?> stage) {
    result.whenComplete(
        (value, error) -> {
          if (result.isCancelled()) {
            stage.cancel(true);
          }
        });
  }
}

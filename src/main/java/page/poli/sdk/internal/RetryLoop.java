package page.poli.sdk.internal;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import page.poli.sdk.PoliPageErrorCode;
import page.poli.sdk.RetryEvent;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.exception.PoliPageNetworkException;

/**
 * Synchronous retry orchestration. Wraps a transport call in an exponential-backoff loop, honours
 * {@code Retry-After} (capped at 30 seconds), and never retries non-retryable failures.
 *
 * <p>Per {@code sdk-java-plan.md} §5:
 *
 * <ul>
 *   <li>Retryable HTTP statuses: {@code 5xx} and {@code 429}.
 *   <li>Retryable exceptions: {@link IOException}, {@link HttpTimeoutException}.
 *   <li>{@link InterruptedException} is never retried — surfaced as a {@link PoliPageException}
 *       with code {@code "aborted"}.
 *   <li>{@code maxRetries == 0} effectively disables retries.
 * </ul>
 *
 * <p>The loop returns the {@link HttpResponse} it actually got — either a 2xx, a final
 * non-retryable status (4xx other than 429), or a retryable status after retries were exhausted.
 * The caller is responsible for status-code -> exception mapping via {@link ErrorParsing}. The loop
 * only throws when the underlying transport threw and retries were exhausted.
 */
public final class RetryLoop {

  private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);
  private static final String RETRY_AFTER_HEADER = "retry-after";

  private final int maxRetries;
  private final Duration baseDelay;
  private final Backoff backoff;
  private final Sleeper sleeper;
  private final @Nullable Consumer<RetryEvent> onRetry;
  private final @Nullable Consumer<Throwable> onError;

  /**
   * @param maxRetries number of retries on top of the initial attempt (0 disables retries)
   * @param baseDelay base delay for the exponential backoff
   * @param backoff injectable backoff calculator
   * @param sleeper injectable sleeper (production uses {@link Sleeper#THREAD})
   * @param onRetry optional hook fired before each retry sleep ({@code null} for no-op)
   * @param onError optional hook fired before a terminal failure throws ({@code null} for no-op)
   */
  public RetryLoop(
      int maxRetries,
      Duration baseDelay,
      Backoff backoff,
      Sleeper sleeper,
      @Nullable Consumer<RetryEvent> onRetry,
      @Nullable Consumer<Throwable> onError) {
    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must be >= 0, got: " + maxRetries);
    }
    this.maxRetries = maxRetries;
    this.baseDelay = baseDelay;
    this.backoff = backoff;
    this.sleeper = sleeper;
    this.onRetry = onRetry;
    this.onError = onError;
  }

  /** Convenience constructor for callers that don't need hooks (tests, mostly). */
  public RetryLoop(int maxRetries, Duration baseDelay, Backoff backoff, Sleeper sleeper) {
    this(maxRetries, baseDelay, backoff, sleeper, null, null);
  }

  /**
   * Execute the given transport call, retrying as configured.
   *
   * @param call the transport call to run; invoked up to {@code maxRetries + 1} times
   * @param label human-readable description used in exception messages (e.g. {@code "POST
   *     /v1/render"})
   * @return the final {@link HttpResponse} — either 2xx, a non-retryable status, or the last
   *     retryable status after retries were exhausted
   * @throws PoliPageNetworkException on a transport failure that wasn't recovered by retries
   * @throws PoliPageException on caller interruption ({@code code() == "aborted"})
   */
  public HttpResponse<byte[]> execute(IoCall call, String label) {
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        HttpResponse<byte[]> response = call.send();
        if (!isRetryableStatus(response.statusCode()) || attempt == maxRetries) {
          return response;
        }
        Duration delay = pickDelay(attempt, readRetryAfter(response));
        fireOnRetry(attempt, delay, response.statusCode(), reasonForStatus(response.statusCode()));
        sleep(delay, label);
      } catch (HttpTimeoutException e) {
        if (attempt == maxRetries) {
          PoliPageNetworkException terminal =
              new PoliPageNetworkException(
                  PoliPageErrorCode.TIMEOUT, label + " timed out: " + e.getMessage(), e);
          fireOnError(terminal);
          throw terminal;
        }
        Duration delay = backoff.compute(attempt, baseDelay);
        fireOnRetry(attempt, delay, null, PoliPageErrorCode.TIMEOUT);
        sleep(delay, label);
      } catch (IOException e) {
        if (attempt == maxRetries) {
          PoliPageNetworkException terminal =
              new PoliPageNetworkException(
                  PoliPageErrorCode.NETWORK_ERROR, label + " failed: " + e.getMessage(), e);
          fireOnError(terminal);
          throw terminal;
        }
        Duration delay = backoff.compute(attempt, baseDelay);
        fireOnRetry(attempt, delay, null, PoliPageErrorCode.NETWORK_ERROR);
        sleep(delay, label);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        // Interruption is a caller-initiated abort, not a failure the SDK is reporting —
        // onError intentionally does not fire here.
        throw new PoliPageException(
            PoliPageErrorCode.ABORTED, 0, label + " was interrupted", null, e);
      }
    }

    // Unreachable: the loop body always returns or throws.
    throw new IllegalStateException("RetryLoop exited without returning a response or throwing");
  }

  private Duration pickDelay(int attempt, @Nullable Duration retryAfter) {
    return retryAfter != null ? cap(retryAfter) : backoff.compute(attempt, baseDelay);
  }

  private void sleep(Duration delay, String label) {
    try {
      sleeper.sleep(delay);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PoliPageException(
          PoliPageErrorCode.ABORTED, 0, label + " retry sleep was interrupted", null, e);
    }
  }

  private static String reasonForStatus(int status) {
    return status == 429 ? "rate_limit" : "5xx";
  }

  private void fireOnRetry(int attempt, Duration delay, @Nullable Integer status, String reason) {
    if (onRetry == null) {
      return;
    }
    try {
      onRetry.accept(new RetryEvent(attempt, delay, status, reason));
    } catch (Throwable ignored) {
      // Hook errors must never break the request — swallow.
    }
  }

  private void fireOnError(Throwable error) {
    if (onError == null) {
      return;
    }
    try {
      onError.accept(error);
    } catch (Throwable ignored) {
      // Hook errors must never break the request — swallow.
    }
  }

  private static Duration cap(Duration retryAfter) {
    return retryAfter.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : retryAfter;
  }

  private static @Nullable Duration readRetryAfter(HttpResponse<?> response) {
    return ErrorParsing.parseRetryAfter(
        response.headers().firstValue(RETRY_AFTER_HEADER).orElse(null));
  }

  private static boolean isRetryableStatus(int status) {
    return status == 429 || status >= 500;
  }

  // ===== Async path =================================================

  /**
   * Asynchronous counterpart of {@link #execute(IoCall, String)}. Uses {@link
   * CompletableFuture#delayedExecutor(long, TimeUnit)} for inter-attempt back-off, so no worker
   * thread is blocked while waiting.
   *
   * <p>The returned future:
   *
   * <ul>
   *   <li>completes with the final {@link HttpResponse} (2xx, non-retryable, or last retryable
   *       after exhaustion)
   *   <li>completes exceptionally with {@link PoliPageNetworkException} on transport failure that
   *       wasn't recovered by retries
   *   <li>completes exceptionally with {@link CancellationException} when the original supplier
   *       returned a cancelled future (caller-side cancel propagates per JDK convention)
   * </ul>
   *
   * @param call the async transport call
   * @param label human-readable description used in exception messages
   * @return a {@code CompletableFuture} of the final response
   */
  public CompletableFuture<HttpResponse<byte[]>> executeAsync(AsyncCall call, String label) {
    return attemptAsync(0, call, label);
  }

  private CompletableFuture<HttpResponse<byte[]>> attemptAsync(
      int attempt, AsyncCall call, String label) {
    CompletableFuture<HttpResponse<byte[]>> fut;
    try {
      fut = call.send();
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
    return fut.handle((response, error) -> processAsync(attempt, response, error, call, label))
        .thenCompose(next -> next);
  }

  private CompletableFuture<HttpResponse<byte[]>> processAsync(
      int attempt,
      @Nullable HttpResponse<byte[]> response,
      @Nullable Throwable error,
      AsyncCall call,
      String label) {
    if (error != null) {
      Throwable cause = unwrapCompletion(error);
      if (cause instanceof CancellationException ce) {
        // Caller-initiated cancel — never retry, never fire onError (matches sync's
        // InterruptedException semantics).
        return CompletableFuture.failedFuture(ce);
      }
      if (cause instanceof HttpTimeoutException) {
        if (attempt < maxRetries) {
          Duration delay = backoff.compute(attempt, baseDelay);
          fireOnRetry(attempt, delay, null, PoliPageErrorCode.TIMEOUT);
          return scheduleAsyncRetry(delay, attempt, call, label);
        }
        PoliPageNetworkException terminal =
            new PoliPageNetworkException(
                PoliPageErrorCode.TIMEOUT, label + " timed out: " + cause.getMessage(), cause);
        fireOnError(terminal);
        return CompletableFuture.failedFuture(terminal);
      }
      if (cause instanceof IOException) {
        if (attempt < maxRetries) {
          Duration delay = backoff.compute(attempt, baseDelay);
          fireOnRetry(attempt, delay, null, PoliPageErrorCode.NETWORK_ERROR);
          return scheduleAsyncRetry(delay, attempt, call, label);
        }
        PoliPageNetworkException terminal =
            new PoliPageNetworkException(
                PoliPageErrorCode.NETWORK_ERROR, label + " failed: " + cause.getMessage(), cause);
        fireOnError(terminal);
        return CompletableFuture.failedFuture(terminal);
      }
      // Any other Throwable — propagate (caller's bug, OOM, etc.)
      return CompletableFuture.failedFuture(cause);
    }

    // Response present.
    int status = response.statusCode();
    if (isRetryableStatus(status) && attempt < maxRetries) {
      Duration delay = pickDelay(attempt, readRetryAfter(response));
      fireOnRetry(attempt, delay, status, reasonForStatus(status));
      return scheduleAsyncRetry(delay, attempt, call, label);
    }
    return CompletableFuture.completedFuture(response);
  }

  private CompletableFuture<HttpResponse<byte[]>> scheduleAsyncRetry(
      Duration delay, int previousAttempt, AsyncCall call, String label) {
    long ms = Math.max(0L, delay.toMillis());
    Executor delayed = CompletableFuture.delayedExecutor(ms, TimeUnit.MILLISECONDS);
    return CompletableFuture.supplyAsync(() -> (Void) null, delayed)
        .thenCompose(__ -> attemptAsync(previousAttempt + 1, call, label));
  }

  private static Throwable unwrapCompletion(Throwable t) {
    if (t instanceof CompletionException && t.getCause() != null) {
      return t.getCause();
    }
    return t;
  }

  /** Functional shape of a transport call that may throw I/O or interruption. */
  @FunctionalInterface
  public interface IoCall {
    HttpResponse<byte[]> send() throws IOException, InterruptedException;
  }

  /** Functional shape of an async transport call (no checked exceptions on the signature). */
  @FunctionalInterface
  public interface AsyncCall {
    CompletableFuture<HttpResponse<byte[]>> send();
  }
}

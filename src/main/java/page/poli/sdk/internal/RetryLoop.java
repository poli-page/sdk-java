package page.poli.sdk.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.ResponseInfo;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import page.poli.sdk.PoliPageErrorCode;
import page.poli.sdk.RequestEvent;
import page.poli.sdk.ResponseEvent;
import page.poli.sdk.RetryEvent;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.exception.PoliPageNetworkException;

/**
 * Runs the attempts of one API call (SDK spec §9).
 *
 * <ul>
 *   <li>Each attempt is bounded by the client's timeout as a whole: connect, headers and body. The
 *       body of every answer is read inside its attempt, so a body that breaks off or stalls fails
 *       that attempt.
 *   <li>A 2xx answer is returned. Any other answer becomes the {@link PoliPageException} of SDK
 *       spec §8.2; a network failure {@code network_error}, an attempt over the timeout {@code
 *       timeout}.
 *   <li>A failure is retried when {@link PoliPageException#isRetryable()} says so — one rule for
 *       the loop and for callers — up to {@code maxRetries} times. The last error is thrown.
 *   <li>Before retry {@code n} (1-based) the loop waits the failed answer's {@code Retry-After}
 *       (integer seconds or HTTP-date, clamped to 0–30 s, no jitter), else {@code retryDelay ×
 *       2^(n-1) × j} with {@code j} in {@code [0.5, 1.5)}. {@code Retry-After-Ms} is ignored. The
 *       wait is never shorter than the delay.
 *   <li>Cancellation — a thread interrupt on the blocking path, {@code cancel} of the returned
 *       future on the async path — ends the call at once, during an attempt or a wait, and is never
 *       retried.
 * </ul>
 */
public final class RetryLoop {

  private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);
  private static final String REQUEST_ID_HEADER = "x-request-id";
  private static final String RETRY_AFTER_HEADER = "retry-after";

  private final int maxRetries;
  private final Duration baseDelay;
  private final Duration timeout;
  private final Backoff backoff;
  private final Sleeper sleeper;
  private final ObjectMapper mapper;
  private final Hooks hooks;

  /**
   * @param maxRetries number of retries on top of the initial attempt (0 disables retries)
   * @param baseDelay base delay for the exponential backoff
   * @param timeout bound of each attempt, body included
   * @param backoff injectable backoff calculator
   * @param sleeper injectable sleeper for the blocking path (production uses {@link
   *     Sleeper#THREAD})
   * @param mapper the SDK's ObjectMapper, to read error bodies
   * @param hooks the client's hooks
   */
  public RetryLoop(
      int maxRetries,
      Duration baseDelay,
      Duration timeout,
      Backoff backoff,
      Sleeper sleeper,
      ObjectMapper mapper,
      Hooks hooks) {
    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must be >= 0, got: " + maxRetries);
    }
    this.maxRetries = maxRetries;
    this.baseDelay = baseDelay;
    this.timeout = timeout;
    this.backoff = backoff;
    this.sleeper = sleeper;
    this.mapper = mapper;
    this.hooks = hooks;
  }

  /** One HTTP exchange of the call, started anew for each attempt. */
  @FunctionalInterface
  public interface Attempt {
    /**
     * @param capture the body handler to read the answer with
     * @return the pending answer
     */
    CompletableFuture<HttpResponse<byte[]>> send(ResponseCapture<byte[]> capture);
  }

  // ===== Blocking path ===============================================

  /**
   * Run the call on the current thread.
   *
   * @param call starts one exchange
   * @param method the HTTP method, for {@code onRequest}
   * @param url the full request URL, for {@code onRequest}
   * @return the 2xx answer, its body read
   * @throws PoliPageException the last error; {@code aborted} when the thread is interrupted
   */
  public HttpResponse<byte[]> execute(Attempt call, String method, URI url) {
    for (int attempt = 1; ; attempt++) {
      hooks.request(new RequestEvent(method, url.toString(), attempt));
      Outcome outcome = attemptBlocking(call);
      if (outcome instanceof Success success) {
        return success.response();
      }
      if (outcome instanceof Crash crash) {
        throw crash.unchecked();
      }
      Failure failure = (Failure) outcome;
      if (!failure.error().isRetryable() || attempt > maxRetries) {
        hooks.error(failure.error());
        throw failure.error();
      }
      Duration delay = delayBefore(attempt, failure);
      hooks.retry(new RetryEvent(attempt + 1, delay, failure.status(), failure.reason()));
      try {
        sleeper.sleep(delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw abort(e);
      }
    }
  }

  private Outcome attemptBlocking(Attempt call) {
    ResponseCapture<byte[]> capture = ResponseCapture.ofByteArray();
    long started = System.nanoTime();
    CompletableFuture<HttpResponse<byte[]>> exchange;
    try {
      exchange = call.send(capture);
    } catch (RuntimeException e) {
      return new Crash(e);
    }
    try {
      HttpResponse<byte[]> response =
          exchange.get(Timers.saturatedNanos(timeout), TimeUnit.NANOSECONDS);
      return settle(response, null, false, capture, started);
    } catch (TimeoutException e) {
      exchange.cancel(true);
      return settle(null, e, true, capture, started);
    } catch (ExecutionException e) {
      return settle(null, e.getCause() != null ? e.getCause() : e, false, capture, started);
    } catch (CancellationException e) {
      return settle(null, e, false, capture, started);
    } catch (InterruptedException e) {
      exchange.cancel(true);
      Thread.currentThread().interrupt();
      throw abort(e);
    }
  }

  private PoliPageException abort(Throwable cause) {
    PoliPageException aborted =
        new PoliPageException(PoliPageErrorCode.ABORTED, 0, "Request was aborted", null, cause);
    hooks.error(aborted);
    return aborted;
  }

  // ===== Async path ==================================================

  /**
   * Run the call asynchronously. No thread is blocked while waiting on the network or between
   * attempts. Cancelling the returned future cancels the attempt in flight or the pending wait, and
   * no further attempt starts.
   *
   * @param call starts one exchange
   * @param method the HTTP method, for {@code onRequest}
   * @param url the full request URL, for {@code onRequest}
   * @return the 2xx answer, its body read; or the last error
   */
  public CompletableFuture<HttpResponse<byte[]>> executeAsync(
      Attempt call, String method, URI url) {
    AsyncCall running = new AsyncCall(call, method, url);
    running.start();
    return running.result;
  }

  private final class AsyncCall {

    final CompletableFuture<HttpResponse<byte[]>> result = new CompletableFuture<>();
    private final Attempt call;
    private final String method;
    private final URI url;
    private volatile @Nullable Future<?> pending;

    AsyncCall(Attempt call, String method, URI url) {
      this.call = call;
      this.method = method;
      this.url = url;
    }

    void start() {
      result.whenComplete(
          (response, error) -> {
            if (result.isCancelled()) {
              cancelPending();
            }
          });
      attempt(1);
    }

    private void cancelPending() {
      Future<?> current = pending;
      if (current != null) {
        current.cancel(true);
      }
    }

    /** Make {@code next} the pending work, and cancel it at once if the call was cancelled. */
    private void track(Future<?> next) {
      pending = next;
      if (result.isCancelled()) {
        next.cancel(true);
      }
    }

    private void attempt(int attempt) {
      if (result.isDone()) {
        return;
      }
      hooks.request(new RequestEvent(method, url.toString(), attempt));
      ResponseCapture<byte[]> capture = ResponseCapture.ofByteArray();
      long started = System.nanoTime();
      CompletableFuture<HttpResponse<byte[]>> exchange;
      try {
        exchange = call.send(capture);
      } catch (RuntimeException e) {
        result.completeExceptionally(e);
        return;
      }
      track(exchange);
      AtomicBoolean timedOut = new AtomicBoolean();
      ScheduledFuture<?> timer =
          Timers.schedule(
              () -> {
                if (!exchange.isDone()) {
                  timedOut.set(true);
                  exchange.cancel(true);
                }
              },
              timeout);
      exchange.whenComplete(
          (response, error) -> {
            timer.cancel(false);
            if (result.isDone()) {
              return;
            }
            settled(attempt, settle(response, error, timedOut.get(), capture, started));
          });
    }

    private void settled(int attempt, Outcome outcome) {
      if (outcome instanceof Success success) {
        result.complete(success.response());
        return;
      }
      if (outcome instanceof Crash crash) {
        result.completeExceptionally(crash.error());
        return;
      }
      Failure failure = (Failure) outcome;
      if (!failure.error().isRetryable() || attempt > maxRetries) {
        hooks.error(failure.error());
        result.completeExceptionally(failure.error());
        return;
      }
      Duration delay = delayBefore(attempt, failure);
      hooks.retry(new RetryEvent(attempt + 1, delay, failure.status(), failure.reason()));
      waitThen(System.nanoTime() + Timers.saturatedNanos(delay), () -> attempt(attempt + 1));
    }

    /** Run {@code next} once the monotonic clock reaches {@code end}, never earlier. */
    private void waitThen(long end, Runnable next) {
      if (result.isDone()) {
        return;
      }
      long left = end - System.nanoTime();
      if (left <= 0) {
        CompletableFuture.runAsync(next);
        return;
      }
      track(Timers.schedule(() -> waitThen(end, next), Duration.ofNanos(left)));
    }
  }

  // ===== One attempt's outcome =======================================

  private sealed interface Outcome permits Success, Failure, Crash {}

  /** A 2xx answer, body read. */
  private record Success(HttpResponse<byte[]> response) implements Outcome {}

  /** An error the SDK maps; retried when the error is retryable. */
  private record Failure(
      PoliPageException error,
      @Nullable Integer status,
      String reason,
      @Nullable Duration retryAfter)
      implements Outcome {}

  /** Not an HTTP outcome: a bug or a misconfiguration, rethrown as is. */
  private record Crash(Throwable error) implements Outcome {
    RuntimeException unchecked() {
      return error instanceof RuntimeException runtime
          ? runtime
          : new IllegalStateException(error.getMessage(), error);
    }
  }

  private Outcome settle(
      @Nullable HttpResponse<byte[]> response,
      @Nullable Throwable failure,
      boolean timedOut,
      ResponseCapture<byte[]> capture,
      long startedNanos) {
    if (response != null) {
      int status = response.statusCode();
      String requestId = response.headers().firstValue(REQUEST_ID_HEADER).orElse(null);
      if (status >= 200 && status < 300) {
        long durationMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        hooks.response(new ResponseEvent(status, requestId, durationMs));
        return new Success(response);
      }
      String retryAfter = response.headers().firstValue(RETRY_AFTER_HEADER).orElse(null);
      return apiFailure(
          ErrorParsing.toException(status, response.body(), requestId, retryAfter, mapper),
          retryAfter);
    }
    Throwable cause = failure == null ? new IOException("no answer") : unwrap(failure);
    boolean isTimeout = timedOut || hasCause(cause, HttpTimeoutException.class);
    if (!isTimeout && !hasCause(cause, IOException.class)) {
      return new Crash(cause);
    }
    if (timedOut) {
      cause = new HttpTimeoutException("the attempt exceeded " + timeout.toMillis() + " ms");
    }
    ResponseInfo info = capture.info();
    if (info != null && (info.statusCode() < 200 || info.statusCode() >= 300)) {
      // The error answer's body broke off: a malformed error body, retried by its status.
      String retryAfter = info.headers().firstValue(RETRY_AFTER_HEADER).orElse(null);
      String requestId = info.headers().firstValue(REQUEST_ID_HEADER).orElse(null);
      return apiFailure(
          ErrorParsing.malformedBody(info.statusCode(), requestId, retryAfter, cause), retryAfter);
    }
    if (isTimeout) {
      return new Failure(
          new PoliPageNetworkException(
              PoliPageErrorCode.TIMEOUT,
              "Request timed out after " + timeout.toMillis() + "ms",
              cause),
          null,
          PoliPageErrorCode.TIMEOUT,
          null);
    }
    return new Failure(
        new PoliPageNetworkException(
            PoliPageErrorCode.NETWORK_ERROR, "Network request failed", cause),
        null,
        PoliPageErrorCode.NETWORK_ERROR,
        null);
  }

  private static Failure apiFailure(PoliPageException error, @Nullable String retryAfterHeader) {
    int status = error.statusCode();
    Duration retryAfter =
        error.isRetryable() ? ErrorParsing.parseRetryAfter(retryAfterHeader) : null;
    return new Failure(error, status, status == 429 ? "rate_limit" : "5xx", retryAfter);
  }

  private Duration delayBefore(int failedAttempt, Failure failure) {
    Duration retryAfter = failure.retryAfter();
    if (retryAfter != null) {
      return retryAfter.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : retryAfter;
    }
    return backoff.compute(failedAttempt - 1, baseDelay);
  }

  /**
   * @param error a failure, possibly wrapped by a future
   * @return the failure without its {@link CompletionException} / {@link ExecutionException}
   *     wrappers
   */
  static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while ((current instanceof CompletionException || current instanceof ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  /**
   * @param error a failure
   * @param type an exception type
   * @return whether {@code error} or one of its causes is a {@code type}
   */
  static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return true;
      }
      if (t.getCause() == t) {
        return false;
      }
    }
    return false;
  }
}

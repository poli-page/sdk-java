package page.poli.sdk.internal;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.ResponseInfo;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import page.poli.sdk.PoliPageErrorCode;
import page.poli.sdk.exception.PoliPageDownloadException;
import page.poli.sdk.exception.PoliPageException;

/**
 * The presigned-URL download: one plain {@code GET} to object storage, never retried, with no
 * {@code Authorization} and no {@code Idempotency-Key}.
 *
 * <ul>
 *   <li>A non-2xx answer raises {@code DOWNLOAD_FAILED} with the storage status: {@code Failed to
 *       download PDF: HTTP <status>}.
 *   <li>No answer, or a connection dropped while the body is read (a body shorter than its {@code
 *       Content-Length} included), raises {@code DOWNLOAD_FAILED} without a status: {@code Failed
 *       to download PDF: network error}, the I/O exception as cause. A streamed body that breaks
 *       off raises the same error from {@link InputStream#read()}.
 *   <li>The client's timeout passing raises {@code DOWNLOAD_FAILED} without a status: {@code Failed
 *       to download PDF: timeout}. Only a timer that fired gives this message: the SDK's own bound
 *       below, or a JDK timeout. The download carries no JDK request timeout, which on JDK 26 and
 *       later would also bound the whole body.
 *   <li>A thread interrupt, or {@code cancel} of the returned future, raises {@code aborted}.
 * </ul>
 *
 * <p>The timeout bounds the whole download of {@link #bytes(String)}, body included. For {@link
 * #stream(String)}, whose body the caller reads at its own pace, it is an idle timeout: it bounds
 * the wait for the headers, then each read that waits for the next bytes, so a large PDF read
 * slowly is not cut off, but a body that stops arriving is.
 */
public final class Download {

  /** {@code policy.sdkErrors.DOWNLOAD_FAILED.networkMessage} of the API contract. */
  static final String NETWORK_MESSAGE = "Failed to download PDF: network error";

  /** {@code policy.sdkErrors.DOWNLOAD_FAILED.timeoutMessage} of the API contract. */
  static final String TIMEOUT_MESSAGE = "Failed to download PDF: timeout";

  private final Transport transport;
  private final Duration timeout;
  private final Hooks hooks;

  /**
   * @param transport sends the {@code GET}
   * @param timeout the client's timeout
   * @param hooks the client's hooks; {@code onError} fires with each download error
   */
  public Download(Transport transport, Duration timeout, Hooks hooks) {
    this.transport = transport;
    this.timeout = timeout;
    this.hooks = hooks;
  }

  /**
   * Download the whole PDF.
   *
   * @param url the presigned URL
   * @return the PDF bytes
   * @throws PoliPageException {@code DOWNLOAD_FAILED} or {@code aborted}
   */
  public byte[] bytes(String url) {
    return awaitBlocking(start(url, BodyHandlers.ofByteArray()));
  }

  /**
   * Download the PDF as a stream. The caller must close it.
   *
   * @param url the presigned URL
   * @return the PDF body; a read that fails raises {@code DOWNLOAD_FAILED}
   * @throws PoliPageException {@code DOWNLOAD_FAILED} or {@code aborted}
   */
  public InputStream stream(String url) {
    return new DownloadStream(awaitBlocking(start(url, BodyHandlers.ofInputStream())));
  }

  /**
   * Async variant of {@link #bytes(String)}. Cancelling the returned future cancels the download.
   *
   * @param url the presigned URL
   * @return a future of the PDF bytes
   */
  public CompletableFuture<byte[]> bytesAsync(String url) {
    return awaitAsync(start(url, BodyHandlers.ofByteArray()), bytes -> bytes);
  }

  /**
   * Async variant of {@link #stream(String)}. Cancelling the returned future cancels the download.
   *
   * @param url the presigned URL
   * @return a future of the PDF body
   */
  public CompletableFuture<InputStream> streamAsync(String url) {
    return awaitAsync(start(url, BodyHandlers.ofInputStream()), DownloadStream::new);
  }

  // -- internals -----------------------------------------------------

  private record Started<T>(
      @Nullable CompletableFuture<HttpResponse<T>> exchange,
      ResponseCapture<T> capture,
      @Nullable RuntimeException invalid) {}

  private <T> Started<T> start(String url, HttpResponse.BodyHandler<T> body) {
    ResponseCapture<T> capture = new ResponseCapture<>(body);
    try {
      return new Started<>(transport.download(URI.create(url), capture), capture, null);
    } catch (IllegalArgumentException e) {
      return new Started<>(null, capture, e);
    }
  }

  private <T> T awaitBlocking(Started<T> started) {
    CompletableFuture<HttpResponse<T>> exchange = started.exchange();
    if (exchange == null) {
      throw fail(networkFailure(started.invalid()));
    }
    HttpResponse<T> response;
    try {
      response = exchange.get(Timers.saturatedNanos(timeout), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      exchange.cancel(true);
      throw fail(failure(started.capture().info(), e));
    } catch (ExecutionException e) {
      throw fail(failure(started.capture().info(), RetryLoop.unwrap(e)));
    } catch (CancellationException e) {
      throw fail(aborted(e));
    } catch (InterruptedException e) {
      exchange.cancel(true);
      Thread.currentThread().interrupt();
      throw fail(aborted(e));
    }
    try {
      return checked(response);
    } catch (PoliPageException e) {
      throw fail(e);
    }
  }

  /**
   * One future for the whole download, body included: cancelling it cancels the exchange, and a
   * body that arrives after the cancel is closed.
   */
  private <T, R> CompletableFuture<R> awaitAsync(Started<T> started, Function<T, R> body) {
    CompletableFuture<R> result = new CompletableFuture<>();
    CompletableFuture<HttpResponse<T>> exchange = started.exchange();
    if (exchange == null) {
      result.completeExceptionally(fail(networkFailure(started.invalid())));
      return result;
    }
    result.whenComplete(
        (value, error) -> {
          if (result.isCancelled()) {
            exchange.cancel(true);
          }
        });
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
            if (response != null) {
              Futures.discard(response.body());
            }
            return;
          }
          try {
            if (error == null) {
              R value = body.apply(checked(response));
              if (!result.complete(value)) {
                Futures.discard(value);
              }
            } else if (timedOut.get()) {
              failAsync(
                  result, failure(started.capture().info(), new TimeoutException("download")));
            } else {
              failAsync(result, failure(started.capture().info(), RetryLoop.unwrap(error)));
            }
          } catch (PoliPageException e) {
            failAsync(result, e);
          }
        });
    return result;
  }

  private <T> T checked(HttpResponse<T> response) {
    int status = response.statusCode();
    if (status >= 200 && status < 300) {
      return response.body();
    }
    if (response.body() instanceof InputStream body) {
      try {
        body.close();
      } catch (IOException ignored) {
        // already failing
      }
    }
    throw httpFailure(status, null);
  }

  /** Fail the download, firing {@code onError} only if no cancel got there first. */
  private void failAsync(CompletableFuture<?> result, PoliPageException error) {
    if (result.completeExceptionally(error)) {
      hooks.error(error);
    }
  }

  private PoliPageException fail(PoliPageException error) {
    hooks.error(error);
    return error;
  }

  private static PoliPageException failure(@Nullable ResponseInfo info, Throwable cause) {
    if (cause instanceof CancellationException) {
      return aborted(cause);
    }
    if (info != null && (info.statusCode() < 200 || info.statusCode() >= 300)) {
      return httpFailure(info.statusCode(), cause);
    }
    // TimeoutException: the SDK's own bound fired; HttpTimeoutException: a JDK timeout fired. Both
    // are the timeout itself, never a guess from elapsed time.
    if (cause instanceof TimeoutException
        || RetryLoop.hasCause(cause, HttpTimeoutException.class)) {
      return timeoutFailure(cause);
    }
    return networkFailure(cause);
  }

  private static PoliPageException httpFailure(int status, @Nullable Throwable cause) {
    return new PoliPageDownloadException(
        PoliPageErrorCode.DOWNLOAD_FAILED, status, "Failed to download PDF: HTTP " + status, cause);
  }

  static PoliPageException networkFailure(@Nullable Throwable cause) {
    return new PoliPageDownloadException(
        PoliPageErrorCode.DOWNLOAD_FAILED, 0, NETWORK_MESSAGE, cause);
  }

  static PoliPageException timeoutFailure(@Nullable Throwable cause) {
    return new PoliPageDownloadException(
        PoliPageErrorCode.DOWNLOAD_FAILED, 0, TIMEOUT_MESSAGE, cause);
  }

  static PoliPageException aborted(Throwable cause) {
    return new PoliPageException(PoliPageErrorCode.ABORTED, 0, "Request was aborted", null, cause);
  }

  /**
   * The body of a streamed download, whose read failures are SDK errors: {@code DOWNLOAD_FAILED}
   * when the connection drops or the idle timeout fires, {@code aborted} when the reading thread is
   * interrupted. Both are unchecked, so they pass through {@link InputStream#transferTo} and {@code
   * Files.copy}; each fires {@code onError}.
   *
   * <p>Each read that blocks is bounded by the client's timeout: a timer armed for that read closes
   * the body when it fires, which ends the blocked read, and the read then fails with {@code Failed
   * to download PDF: timeout}. The time the caller spends between two reads is not counted.
   */
  private final class DownloadStream extends FilterInputStream {

    private final Object lock = new Object();

    /** Closed by the caller, or by an abort from another thread. */
    private volatile boolean closed;

    /** The idle timer fired during a read and closed the body. */
    private volatile boolean timedOut;

    /** Number of the read in progress; guarded by {@link #lock}. */
    private long readNumber;

    /** Whether a read is in progress; guarded by {@link #lock}. */
    private boolean reading;

    DownloadStream(InputStream body) {
      super(body);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }

    @Override
    public int read() {
      return bounded(() -> super.read());
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      return bounded(() -> super.read(buffer, offset, length));
    }

    @Override
    public long skip(long n) {
      return bounded(() -> super.skip(n));
    }

    private <R extends Number> R bounded(Read<R> read) {
      if (timedOut) {
        throw fail(timeoutFailure(null));
      }
      long number;
      synchronized (lock) {
        number = ++readNumber;
        reading = true;
      }
      // expire only flags and closes the body: short enough for the timer thread itself.
      ScheduledFuture<?> timer = Timers.scheduleOnTimerThread(() -> expire(number), timeout);
      R result;
      try {
        result = read.run();
      } catch (IOException e) {
        throw readFailure(e);
      } finally {
        synchronized (lock) {
          reading = false;
        }
        timer.cancel(false);
      }
      // A body closed by the timer may end as if complete: never hand that out as the end of the
      // PDF.
      if (timedOut && result.longValue() <= 0) {
        throw fail(timeoutFailure(null));
      }
      return result;
    }

    /** The idle timer of read {@code number}: close the body if that read is still waiting. */
    private void expire(long number) {
      synchronized (lock) {
        if (!reading || readNumber != number) {
          return;
        }
        timedOut = true;
      }
      try {
        in.close();
      } catch (IOException ignored) {
        // the blocked read fails either way
      }
    }

    /**
     * A read that fails once the stream was closed — by the caller, or by an abort from another
     * thread — is not a download failure to report: {@code onError} stays quiet.
     */
    private PoliPageException readFailure(IOException e) {
      PoliPageException error = mapped(e);
      return closed ? error : fail(error);
    }

    private PoliPageException mapped(IOException e) {
      if (e instanceof InterruptedIOException
          || RetryLoop.hasCause(e, InterruptedException.class)
          || Thread.currentThread().isInterrupted()) {
        return aborted(e);
      }
      if (timedOut || RetryLoop.hasCause(e, HttpTimeoutException.class)) {
        return timeoutFailure(e);
      }
      return networkFailure(e);
    }
  }

  /** One read of the body. */
  @FunctionalInterface
  private interface Read<R> {
    R run() throws IOException;
  }
}

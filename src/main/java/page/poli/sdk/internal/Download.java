package page.poli.sdk.internal;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.ResponseInfo;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import page.poli.sdk.PoliPageErrorCode;
import page.poli.sdk.exception.PoliPageDownloadException;
import page.poli.sdk.exception.PoliPageException;

/**
 * The presigned-URL download (SDK spec §7.2): one plain {@code GET} to object storage, never
 * retried, with no {@code Authorization} and no {@code Idempotency-Key}.
 *
 * <ul>
 *   <li>A non-2xx answer raises {@code DOWNLOAD_FAILED} with the storage status: {@code Failed to
 *       download PDF: HTTP <status>}.
 *   <li>No answer, a connection dropped while the body is read, or the client's timeout passing
 *       raise {@code DOWNLOAD_FAILED} without a status: {@code Failed to download PDF: network
 *       error}, the I/O or timeout exception as cause. A streamed body that breaks off raises the
 *       same error from {@link InputStream#read()}.
 *   <li>A thread interrupt, or {@code cancel} of the returned future, raises {@code aborted}.
 * </ul>
 *
 * <p>The timeout bounds the whole download of {@link #bytes(String)}, and the wait for the headers
 * of {@link #stream(String)}, whose body the caller reads at its own pace.
 */
public final class Download {

  /** {@code policy.sdkErrors.DOWNLOAD_FAILED.networkMessage} of the API contract. */
  static final String NETWORK_MESSAGE = "Failed to download PDF: network error";

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
    return awaitAsync(start(url, BodyHandlers.ofByteArray()));
  }

  /**
   * Async variant of {@link #stream(String)}. Cancelling the returned future cancels the download.
   *
   * @param url the presigned URL
   * @return a future of the PDF body
   */
  public CompletableFuture<InputStream> streamAsync(String url) {
    return awaitAsync(start(url, BodyHandlers.ofInputStream())).thenApply(DownloadStream::new);
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
    return checked(response);
  }

  private <T> CompletableFuture<T> awaitAsync(Started<T> started) {
    CompletableFuture<T> result = new CompletableFuture<>();
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
            return;
          }
          try {
            if (error == null) {
              result.complete(checked(response));
            } else if (timedOut.get()) {
              result.completeExceptionally(
                  fail(failure(started.capture().info(), new TimeoutException("download"))));
            } else {
              result.completeExceptionally(
                  fail(failure(started.capture().info(), RetryLoop.unwrap(error))));
            }
          } catch (PoliPageException e) {
            result.completeExceptionally(e);
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
    throw fail(httpFailure(status, null));
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

  static PoliPageException aborted(Throwable cause) {
    return new PoliPageException(PoliPageErrorCode.ABORTED, 0, "Request was aborted", null, cause);
  }

  /**
   * The body of a streamed download, whose read failures are SDK errors: {@code DOWNLOAD_FAILED}
   * when the connection drops, {@code aborted} when the reading thread is interrupted. Both are
   * unchecked, so they pass through {@link InputStream#transferTo} and {@code Files.copy}; each
   * fires {@code onError}.
   */
  private final class DownloadStream extends FilterInputStream {

    DownloadStream(InputStream body) {
      super(body);
    }

    @Override
    public int read() {
      try {
        return super.read();
      } catch (IOException e) {
        throw fail(mapped(e));
      }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      try {
        return super.read(buffer, offset, length);
      } catch (IOException e) {
        throw fail(mapped(e));
      }
    }

    @Override
    public long skip(long n) {
      try {
        return super.skip(n);
      } catch (IOException e) {
        throw fail(mapped(e));
      }
    }

    private PoliPageException mapped(IOException e) {
      if (e instanceof InterruptedIOException
          || RetryLoop.hasCause(e, InterruptedException.class)
          || Thread.currentThread().isInterrupted()) {
        return aborted(e);
      }
      return networkFailure(e);
    }
  }
}

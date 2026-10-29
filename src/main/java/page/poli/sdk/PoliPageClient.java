package page.poli.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.input.RenderInput;
import page.poli.sdk.internal.Backoff;
import page.poli.sdk.internal.Download;
import page.poli.sdk.internal.Futures;
import page.poli.sdk.internal.Hooks;
import page.poli.sdk.internal.HttpTransport;
import page.poli.sdk.internal.RetryLoop;
import page.poli.sdk.internal.Sleeper;
import page.poli.sdk.internal.Transport;

/**
 * Entry point of the Poli Page SDK for Java.
 *
 * <p>Construct with {@link #builder()}, then access the blocking facade via {@code client.render()}
 * / {@code client.documents()} or the {@link java.util.concurrent.CompletableFuture}-based async
 * facade via {@code client.renderAsync()} / {@code client.documentsAsync()}.
 *
 * <p>Instances are immutable and thread-safe. One {@code PoliPageClient} owns a single {@code
 * java.net.http.HttpClient} connection pool; reuse one instance per application rather than
 * creating one per request.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * PoliPageClient client = PoliPageClient.builder()
 *     .apiKey(System.getenv("POLI_PAGE_API_KEY"))
 *     .build();
 * }</pre>
 */
public final class PoliPageClient {

  private static final URI DEFAULT_BASE_URL = URI.create("https://api.poli.page");
  private static final int DEFAULT_MAX_RETRIES = 2;
  private static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(500);
  private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

  private final PoliPageClientOptions options;
  private final Calls calls;
  private final Render render;
  private final Documents documents;
  private final RenderAsync renderAsync;
  private final DocumentsAsync documentsAsync;

  private PoliPageClient(PoliPageClientOptions options, Calls calls) {
    this.options = options;
    this.calls = calls;
    this.render = new Render(calls);
    this.documents = new Documents(calls);
    this.renderAsync = new RenderAsync(calls);
    this.documentsAsync = new DocumentsAsync(calls);
  }

  /**
   * Returns a new {@link Builder} for constructing a {@link PoliPageClient}.
   *
   * @return a fresh builder with no fields set
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the blocking render facade.
   *
   * <p>The returned instance is cached for the lifetime of the client — call this method as often
   * as you like, no allocation happens after the first call.
   *
   * @return the (cached) blocking render facade
   */
  public Render render() {
    return render;
  }

  /**
   * Returns the blocking documents facade ({@code get}, {@code preview}, {@code thumbnails}, {@code
   * delete}). Cached for the lifetime of the client.
   *
   * @return the (cached) blocking documents facade
   */
  public Documents documents() {
    return documents;
  }

  /**
   * Returns the asynchronous render facade. Same methods as {@link #render()} but each returns a
   * {@link java.util.concurrent.CompletableFuture}. Cached for the lifetime of the client.
   *
   * @return the (cached) async render facade
   */
  public RenderAsync renderAsync() {
    return renderAsync;
  }

  /**
   * Returns the asynchronous documents facade. Same methods as {@link #documents()} but each
   * returns a {@link java.util.concurrent.CompletableFuture}. Cached for the lifetime of the
   * client.
   *
   * @return the (cached) async documents facade
   */
  public DocumentsAsync documentsAsync() {
    return documentsAsync;
  }

  /**
   * Render the input as a PDF and stream the bytes to {@code path}, replacing any existing file.
   * Convenience over {@code render().pdfStream(input)} + manual copy.
   *
   * <p>The PDF is written to a temporary file next to {@code path}, then moved onto it (atomically
   * where the file system can): a call that fails or is interrupted never leaves a partial file,
   * {@code path} is either as it was or the complete PDF, and the temporary file is removed. The
   * underlying stream is closed before this method returns, even on failure. The PDF is not
   * buffered in memory.
   *
   * @param input the render input — non-null
   * @param path destination file path — non-null
   * @throws PoliPageException on render failure, a failed download ({@code DOWNLOAD_FAILED}, also
   *     when the connection drops while the PDF is read) or local file-write failure (code {@code
   *     "io_error"})
   */
  public void renderToFile(RenderInput input, Path path) {
    try (InputStream stream = render.pdfStream(input)) {
      writeFile(stream, path, () -> false);
    } catch (IOException e) {
      throw calls.failed(ioError(path, e));
    }
  }

  /**
   * Async variant of {@link #renderToFile(RenderInput, Path)}, with the same temporary file: a call
   * that fails or is cancelled never leaves a partial file. {@code path} is either as it was or the
   * complete PDF; a cancel that lands once the download is complete may still replace it.
   * Cancelling the future during the copy closes the download and stops the copy.
   *
   * @param input the render input — non-null
   * @param path destination file path — non-null
   * @return a future that completes when the file has been written (or completes exceptionally on
   *     failure, with the cause wrapped in {@code CompletionException} per JDK convention)
   */
  public CompletableFuture<Void> renderToFileAsync(RenderInput input, Path path) {
    return calls
        .futures()
        .<InputStream, Void>map(
            renderAsync.pdfStream(input),
            (stream, call) -> {
              // An abort during the copy closes the stream, so the copy stops at once.
              call.onAbort(() -> Futures.discard(stream));
              if (call.isCancelled()) {
                return null;
              }
              try (InputStream s = stream) {
                writeFile(s, path, call::isCancelled);
              } catch (IOException | RuntimeException e) {
                if (call.isCancelled()) {
                  // Aborted: the abort is the call's one error.
                  return null;
                }
                if (e instanceof RuntimeException runtime) {
                  throw runtime;
                }
                throw calls.failed(ioError(path, (IOException) e));
              }
              return null;
            });
  }

  /**
   * Copy {@code stream} to a temporary file in {@code path}'s directory, then move it onto {@code
   * path}. The target is touched only once the whole PDF is written and the call is not cancelled;
   * otherwise only the temporary file is removed.
   *
   * @return whether the file was written
   */
  private static boolean writeFile(InputStream stream, Path path, BooleanSupplier cancelled)
      throws IOException {
    Path target = path.toAbsolutePath();
    Path name = target.getFileName();
    Path temp =
        target.resolveSibling(
            "." + (name != null ? name : "pdf") + "." + UUID.randomUUID() + ".part");
    boolean moved = false;
    try {
      Files.copy(stream, temp);
      if (cancelled.getAsBoolean()) {
        return false;
      }
      try {
        Files.move(
            temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
      moved = true;
      return true;
    } finally {
      if (!moved) {
        deleteQuietly(temp);
      }
    }
  }

  /**
   * The {@code io_error} of a failed write. It names the target; the JDK exception, which may name
   * the temporary file, is the cause.
   */
  private static PoliPageException ioError(Path path, IOException e) {
    return new PoliPageException(
        "io_error",
        0,
        "Failed to write PDF to " + path + ": " + e.getClass().getSimpleName(),
        null,
        e);
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // best effort: the call already failed
    }
  }

  /** Package-private accessor used by tests to assert defaults. Not part of the public API. */
  PoliPageClientOptions options() {
    return options;
  }

  /**
   * The JDK HTTP client. No connect timeout of its own: {@code requestTimeout} bounds each attempt
   * as a whole, the connect included, so a timeout always reports the bound that fired.
   */
  static HttpClient newHttpClient() {
    return HttpClient.newBuilder().build();
  }

  /**
   * Internal factory for the SDK's Jackson ObjectMapper: lenient on unknown wire fields, omit nulls
   * on the way out.
   */
  private static ObjectMapper newObjectMapper() {
    return JsonMapper.builder()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .defaultPropertyInclusion(JsonInclude.Value.empty().withValueInclusion(Include.NON_NULL))
        .build();
  }

  /**
   * Builder for {@link PoliPageClient}. Obtain via {@link PoliPageClient#builder()}.
   *
   * <p>The only required field is {@link #apiKey(String)}. Every other field has a documented
   * default. Setters return {@code this} for fluent chaining and {@link #build()} validates
   * everything and produces an immutable client.
   */
  public static final class Builder {

    private @Nullable String apiKey;
    private @Nullable URI baseUrl;
    private @Nullable Integer maxRetries;
    private @Nullable Duration retryDelay;
    private @Nullable Duration requestTimeout;
    private @Nullable Consumer<RetryEvent> onRetry;
    private @Nullable Consumer<Throwable> onError;
    private @Nullable Consumer<RequestEvent> onRequest;
    private @Nullable Consumer<ResponseEvent> onResponse;

    private Builder() {}

    /**
     * Sets the API key used for authentication. Required.
     *
     * <p>A key has the form {@code pp_{env}_<32 hex>}, where {@code {env}} is the name of the
     * environment it belongs to ({@code pp_sandbox_…}, {@code pp_live_…}, {@code pp_staging_…}).
     * The API resolves the environment from the key; the SDK never sends one and only checks that
     * the key is present and not empty. Find your keys at <a
     * href="https://poli.page/dashboard/keys">poli.page/dashboard/keys</a>.
     *
     * @param apiKey the API key — must not be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code apiKey} is {@code null}
     */
    public Builder apiKey(String apiKey) {
      this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
      return this;
    }

    /**
     * Sets the base URL of the Poli Page API. Defaults to {@code https://api.poli.page}.
     *
     * <p>Override this for staging environments, self-hosted deployments, or testing against a mock
     * server (e.g. WireMock). The URL must be absolute.
     *
     * @param baseUrl absolute URL of the API root — must not be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code baseUrl} is {@code null}
     * @throws IllegalArgumentException if {@code baseUrl} is not absolute
     */
    public Builder baseUrl(URI baseUrl) {
      Objects.requireNonNull(baseUrl, "baseUrl");
      if (!baseUrl.isAbsolute()) {
        throw new IllegalArgumentException("baseUrl must be absolute, got: " + baseUrl);
      }
      this.baseUrl = baseUrl;
      return this;
    }

    /**
     * Sets the maximum number of retry attempts on top of the initial request. Defaults to {@code
     * 2} (i.e. up to three total attempts).
     *
     * <p>Retries fire on {@code 5xx} responses, {@code 429}, and network/timeout errors, except
     * {@code QUOTA_EXCEEDED}, {@code OVERAGE_CAP_EXCEEDED} (429) and {@code STORAGE_ACCESS_DENIED}
     * (502), which waiting cannot fix. {@code 4xx} responses (other than {@code 429}) are never
     * retried, nor is the presigned-URL download.
     *
     * @param maxRetries number of retries — must be {@code >= 0}. {@code 0} disables retries.
     * @return this builder
     * @throws IllegalArgumentException if {@code maxRetries < 0}
     */
    public Builder maxRetries(int maxRetries) {
      if (maxRetries < 0) {
        throw new IllegalArgumentException("maxRetries must be >= 0, got: " + maxRetries);
      }
      this.maxRetries = maxRetries;
      return this;
    }

    /**
     * Sets the base delay between retry attempts. Defaults to {@code 500ms}. Before retry {@code n}
     * (1-based) the SDK waits {@code retryDelay × 2^(n-1)} with jitter in {@code [0.5, 1.5)}; a
     * {@code Retry-After} header (integer seconds or HTTP-date, clamped to 0–30 s) replaces it,
     * without jitter. {@code Retry-After-Ms} is ignored.
     *
     * @param retryDelay base delay — must be strictly positive
     * @return this builder
     * @throws NullPointerException if {@code retryDelay} is {@code null}
     * @throws IllegalArgumentException if {@code retryDelay} is zero or negative
     */
    public Builder retryDelay(Duration retryDelay) {
      Objects.requireNonNull(retryDelay, "retryDelay");
      if (retryDelay.isZero() || retryDelay.isNegative()) {
        throw new IllegalArgumentException("retryDelay must be positive, got: " + retryDelay);
      }
      this.retryDelay = retryDelay;
      return this;
    }

    /**
     * Sets the per-attempt request timeout. Defaults to {@code 60s}. It bounds each attempt as a
     * whole — connecting, the answer's headers and its body — and the presigned-URL download.
     *
     * <p>A timeout surfaces as a {@code PoliPageNetworkException} with {@code code() == "timeout"}
     * and the message {@code Request timed out after <ms>ms}, and is eligible for retry.
     *
     * @param requestTimeout per-attempt timeout — must be strictly positive
     * @return this builder
     * @throws NullPointerException if {@code requestTimeout} is {@code null}
     * @throws IllegalArgumentException if {@code requestTimeout} is zero or negative
     */
    public Builder requestTimeout(Duration requestTimeout) {
      Objects.requireNonNull(requestTimeout, "requestTimeout");
      if (requestTimeout.isZero() || requestTimeout.isNegative()) {
        throw new IllegalArgumentException(
            "requestTimeout must be positive, got: " + requestTimeout);
      }
      this.requestTimeout = requestTimeout;
      return this;
    }

    /**
     * Registers a callback fired just before each wait between attempts, with the number of the
     * attempt about to start ({@code 2} for the first retry), the delay and the reason. Useful for
     * metrics, structured logs, or distributed-trace annotations without instrumenting the SDK's
     * SLF4J output.
     *
     * <p>On the blocking API the hook runs on the calling thread; on the async API, on an HTTP
     * client or timer thread. A hook that throws is caught and swallowed — a faulty hook will never
     * break the request. Set to {@code null} to clear.
     *
     * @param onRetry the callback, or {@code null} to clear
     * @return this builder
     */
    public Builder onRetry(@Nullable Consumer<RetryEvent> onRetry) {
      this.onRetry = onRetry;
      return this;
    }

    /**
     * Registers a callback fired once with each error a call ends with: an API error (after the
     * retries, when it is retried), {@code network_error}, {@code timeout}, {@code aborted} (an
     * interrupted blocking call or a cancelled async future), {@code DOWNLOAD_FAILED}, {@code
     * invalid_response}, {@code io_error}, and any other exception the call throws.
     *
     * <p>The hook is invoked synchronously. A hook that throws is caught and swallowed.
     *
     * @param onError the callback, or {@code null} to clear
     * @return this builder
     */
    public Builder onError(@Nullable Consumer<Throwable> onError) {
      this.onError = onError;
      return this;
    }

    /**
     * Registers a callback fired immediately before each HTTP attempt. Fires once per attempt
     * (including retries). A hook that throws is caught and swallowed.
     *
     * @param onRequest the callback, or {@code null} to clear
     * @return this builder
     */
    public Builder onRequest(@Nullable Consumer<RequestEvent> onRequest) {
      this.onRequest = onRequest;
      return this;
    }

    /**
     * Registers a callback fired after each 2xx API answer, once its body is read: {@code
     * durationMs} covers the body. Error answers, transport failures (timeout, network error), a
     * 2xx whose body breaks off, and the presigned-URL download do not fire it. A hook that throws
     * is caught and swallowed.
     *
     * @param onResponse the callback, or {@code null} to clear
     * @return this builder
     */
    public Builder onResponse(@Nullable Consumer<ResponseEvent> onResponse) {
      this.onResponse = onResponse;
      return this;
    }

    /**
     * Validates the builder state and produces an immutable {@link PoliPageClient}.
     *
     * @return a configured, ready-to-use client
     * @throws page.poli.sdk.exception.PoliPageInvalidOptionsException if {@code apiKey} is missing
     *     or blank
     */
    public PoliPageClient build() {
      if (apiKey == null || apiKey.isBlank()) {
        throw new page.poli.sdk.exception.PoliPageInvalidOptionsException("apiKey is required");
      }
      PoliPageClientOptions opts =
          new PoliPageClientOptions(
              apiKey,
              baseUrl != null ? baseUrl : DEFAULT_BASE_URL,
              maxRetries != null ? maxRetries : DEFAULT_MAX_RETRIES,
              retryDelay != null ? retryDelay : DEFAULT_RETRY_DELAY,
              requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT,
              onRetry,
              onError,
              onRequest,
              onResponse);
      HttpClient http = newHttpClient();
      ObjectMapper mapper = newObjectMapper();
      Hooks hooks = new Hooks(opts.onRequest(), opts.onResponse(), opts.onRetry(), opts.onError());
      Transport transport =
          new HttpTransport(opts.baseUrl(), opts.apiKey(), opts.requestTimeout(), http);
      RetryLoop retry =
          new RetryLoop(
              opts.maxRetries(),
              opts.retryDelay(),
              opts.requestTimeout(),
              Backoff.defaultJitter(),
              Sleeper.THREAD,
              mapper,
              hooks);
      Download download = new Download(transport, opts.requestTimeout(), hooks);
      return new PoliPageClient(opts, new Calls(transport, mapper, retry, download, hooks));
    }
  }
}

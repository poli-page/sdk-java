package page.poli.sdk;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.input.ProjectModeInput;
import page.poli.sdk.internal.Backoff;
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
  private final Render render;
  private final Documents documents;
  private final RenderAsync renderAsync;
  private final DocumentsAsync documentsAsync;

  private PoliPageClient(
      PoliPageClientOptions options, Transport transport, ObjectMapper mapper, RetryLoop retry) {
    this.options = options;
    this.render = new Render(transport, mapper, retry);
    this.documents = new Documents(transport, mapper, retry);
    this.renderAsync = new RenderAsync(transport, mapper, retry);
    this.documentsAsync = new DocumentsAsync(transport, mapper, retry);
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
   * Render the input as a PDF and stream the bytes directly to {@code path}, overwriting any
   * existing file. Convenience over {@code render().pdfStream(input)} + manual copy.
   *
   * <p>The underlying stream is closed before this method returns, even on failure. The PDF is not
   * buffered in memory.
   *
   * @param input the render input — non-null
   * @param path destination file path — non-null
   * @throws PoliPageException on render failure or local file-write failure (code {@code
   *     "io_error"})
   */
  public void renderToFile(ProjectModeInput input, Path path) {
    try (InputStream stream = render.pdfStream(input)) {
      Files.copy(stream, path, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new PoliPageException(
          "io_error", 0, "Failed to write PDF to " + path + ": " + e.getMessage(), null, e);
    }
  }

  /**
   * Async variant of {@link #renderToFile(ProjectModeInput, Path)}.
   *
   * @param input the render input — non-null
   * @param path destination file path — non-null
   * @return a future that completes when the file has been written (or completes exceptionally on
   *     failure, with the cause wrapped in {@code CompletionException} per JDK convention)
   */
  public CompletableFuture<Void> renderToFileAsync(ProjectModeInput input, Path path) {
    return renderAsync
        .pdfStream(input)
        .thenAccept(
            stream -> {
              try (InputStream s = stream) {
                Files.copy(s, path, StandardCopyOption.REPLACE_EXISTING);
              } catch (IOException e) {
                throw new PoliPageException(
                    "io_error",
                    0,
                    "Failed to write PDF to " + path + ": " + e.getMessage(),
                    null,
                    e);
              }
            });
  }

  /** Package-private accessor used by tests to assert defaults. Not part of the public API. */
  PoliPageClientOptions options() {
    return options;
  }

  /**
   * Internal factory for the SDK's Jackson ObjectMapper. Configured per {@code sdk-java-plan.md}
   * §4: lenient on unknown wire fields, omit nulls on the way out.
   */
  private static ObjectMapper newObjectMapper() {
    return JsonMapper.builder()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .serializationInclusion(Include.NON_NULL)
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

    private Builder() {}

    /**
     * Sets the API key used for authentication. Required.
     *
     * <p>Test keys (prefix {@code pp_test_}) hit the develop environment; live keys (prefix {@code
     * pp_live_}) hit production. Find your keys at <a
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
     * <p>Retries fire on {@code 5xx} responses, {@code 429}, and network/timeout errors. {@code
     * 4xx} responses (other than {@code 429}) are never retried.
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
     * Sets the base delay between retry attempts. Defaults to {@code 500ms}. The actual delay
     * follows {@code retryDelay × 2^attempt} with jitter in {@code [0.5, 1.5)}, capped by any
     * {@code Retry-After} header (max 30s).
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
     * Sets the per-attempt request timeout. Defaults to {@code 60s}. Distinct from the underlying
     * TCP connect timeout (controlled by {@code java.net.http.HttpClient.Builder.connectTimeout}).
     *
     * <p>A timeout surfaces as a {@code PoliPageException} with {@code code() == "TIMEOUT"} and is
     * eligible for retry.
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
     * Registers a callback fired just before each retry sleep. Useful for metrics, structured logs,
     * or distributed-trace annotations without instrumenting the SDK's SLF4J output.
     *
     * <p>The hook is invoked synchronously on the thread waiting to retry. A hook that throws is
     * caught and swallowed — a faulty hook will never break the request. Set to {@code null} to
     * clear.
     *
     * @param onRetry the callback, or {@code null} to clear
     * @return this builder
     */
    public Builder onRetry(@Nullable Consumer<RetryEvent> onRetry) {
      this.onRetry = onRetry;
      return this;
    }

    /**
     * Registers a callback fired when the SDK is about to surface a terminal failure from the retry
     * loop (transport failure that wasn't recovered, or abort). Wire-mapped exceptions (4xx) raised
     * by {@link page.poli.sdk.internal.ErrorParsing} from the facade do not fire this hook —
     * they're not in scope of "retry exhausted".
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
     * Validates the builder state and produces an immutable {@link PoliPageClient}.
     *
     * @return a configured, ready-to-use client
     * @throws page.poli.sdk.exception.PoliPageInvalidOptionsException if {@code apiKey} is missing
     *     or blank
     */
    public PoliPageClient build() {
      if (apiKey == null || apiKey.isBlank()) {
        throw new page.poli.sdk.exception.PoliPageInvalidOptionsException(
            "apiKey is required: set it via PoliPageClient.builder().apiKey(...)");
      }
      PoliPageClientOptions opts =
          new PoliPageClientOptions(
              apiKey,
              baseUrl != null ? baseUrl : DEFAULT_BASE_URL,
              maxRetries != null ? maxRetries : DEFAULT_MAX_RETRIES,
              retryDelay != null ? retryDelay : DEFAULT_RETRY_DELAY,
              requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT,
              onRetry,
              onError);
      HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
      ObjectMapper mapper = newObjectMapper();
      Transport transport =
          new HttpTransport(opts.baseUrl(), opts.apiKey(), opts.requestTimeout(), http, mapper);
      RetryLoop retry =
          new RetryLoop(
              opts.maxRetries(),
              opts.retryDelay(),
              Backoff.defaultJitter(),
              Sleeper.THREAD,
              opts.onRetry(),
              opts.onError());
      return new PoliPageClient(opts, transport, mapper, retry);
    }
  }
}

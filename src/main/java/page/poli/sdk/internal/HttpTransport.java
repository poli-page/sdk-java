package page.poli.sdk.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Default {@link Transport}, backed by the JDK's {@link HttpClient}. Every request carries a {@link
 * SingleExchangeBody}, so the client never resends it by itself. An API request also carries the
 * attempt timeout as {@link HttpRequest.Builder#timeout(Duration)}; up to JDK 25 the JDK stops that
 * timer once the headers arrive, so {@link RetryLoop} also bounds the whole attempt, body included.
 * The presigned-URL download carries none: {@link Download} bounds it.
 */
public final class HttpTransport implements Transport {

  private static final String USER_AGENT = "poli-page-sdk-java/" + Version.VALUE;

  private final URI baseUrl;
  private final String apiKey;
  private final Duration requestTimeout;
  private final HttpClient http;

  /**
   * @param baseUrl absolute base URL — paths are resolved against this
   * @param apiKey value for the {@code Authorization: Bearer …} header
   * @param requestTimeout per-attempt timeout
   * @param http the JDK HTTP client
   */
  public HttpTransport(URI baseUrl, String apiKey, Duration requestTimeout, HttpClient http) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.requestTimeout = requestTimeout;
    this.http = http;
  }

  @Override
  public URI baseUrl() {
    return baseUrl;
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> send(
      String method,
      String path,
      byte @Nullable [] json,
      @Nullable String idempotencyKey,
      BodyHandler<T> handler) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(Urls.join(baseUrl, path))
            .timeout(requestTimeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT);
    if (json != null) {
      request.header("Content-Type", "application/json");
      request.method(method, SingleExchangeBody.of(json));
    } else {
      request.method(method, SingleExchangeBody.empty());
    }
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return http.sendAsync(request.build(), handler);
  }

  /**
   * {@inheritDoc}
   *
   * <p>No JDK request timeout: {@link Download} bounds the download itself, the whole of it for the
   * bytes and each wait for bytes for a stream. On JDK 26 and later the JDK's request timeout also
   * covers the body, so it would cut a large PDF that a caller streams at its own pace.
   */
  @Override
  public <T> CompletableFuture<HttpResponse<T>> download(URI url, BodyHandler<T> handler) {
    HttpRequest request =
        HttpRequest.newBuilder(url)
            .header("User-Agent", USER_AGENT)
            .method("GET", SingleExchangeBody.empty())
            .build();
    return http.sendAsync(request, handler);
  }
}

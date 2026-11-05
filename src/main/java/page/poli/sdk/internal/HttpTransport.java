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
 * SingleExchangeBody}, so the client never resends it by itself. No request carries a JDK timeout
 * ({@link HttpRequest.Builder#timeout(Duration)}): the JDK's timer reads the wall clock and
 * truncates the time left to whole milliseconds, so it can fire early, and up to JDK 25 it stops
 * once the headers arrive. {@link RetryLoop} bounds each API attempt, body included, and {@link
 * Download} bounds the presigned-URL download, both on the monotonic clock and never early.
 */
public final class HttpTransport implements Transport {

  private static final String USER_AGENT = "poli-page-sdk-java/" + Version.VALUE;

  private final URI baseUrl;
  private final String apiKey;
  private final HttpClient http;

  /**
   * @param baseUrl absolute base URL — paths are resolved against this
   * @param apiKey value for the {@code Authorization: Bearer …} header
   * @param http the JDK HTTP client
   */
  public HttpTransport(URI baseUrl, String apiKey, HttpClient http) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
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

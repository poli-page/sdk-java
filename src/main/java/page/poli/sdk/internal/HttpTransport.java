package page.poli.sdk.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Default {@link Transport} implementation backed by the JDK's {@link HttpClient} and Jackson's
 * {@link ObjectMapper}. Stateless apart from the injected configuration. Sync verbs use {@link
 * HttpClient#send(HttpRequest, HttpResponse.BodyHandler)}; async verbs use {@link
 * HttpClient#sendAsync(HttpRequest, HttpResponse.BodyHandler)} and share the same request-building
 * code.
 */
public final class HttpTransport implements Transport {

  private static final String USER_AGENT = "poli-page-sdk-java/" + Version.VALUE;

  private final URI baseUrl;
  private final String apiKey;
  private final Duration requestTimeout;
  private final HttpClient http;
  private final ObjectMapper mapper;

  /**
   * Construct a new transport.
   *
   * @param baseUrl absolute base URL — paths are resolved against this
   * @param apiKey value for the {@code Authorization: Bearer …} header
   * @param requestTimeout per-attempt deadline (also used by {@link HttpRequest.Builder#timeout})
   * @param http injected JDK HTTP client (shared with the rest of the SDK)
   * @param mapper injected Jackson ObjectMapper
   */
  public HttpTransport(
      URI baseUrl, String apiKey, Duration requestTimeout, HttpClient http, ObjectMapper mapper) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.requestTimeout = requestTimeout;
    this.http = http;
    this.mapper = mapper;
  }

  @Override
  public URI baseUrl() {
    return baseUrl;
  }

  // -- Sync verbs -----------------------------------------------------

  @Override
  public HttpResponse<byte[]> post(String path, Object body, String idempotencyKey)
      throws IOException, InterruptedException {
    return http.send(postRequest(path, body, idempotencyKey), BodyHandlers.ofByteArray());
  }

  @Override
  public HttpResponse<byte[]> get(String path) throws IOException, InterruptedException {
    return http.send(baselessRequest(path).GET().build(), BodyHandlers.ofByteArray());
  }

  @Override
  public HttpResponse<byte[]> delete(String path) throws IOException, InterruptedException {
    return http.send(baselessRequest(path).DELETE().build(), BodyHandlers.ofByteArray());
  }

  @Override
  public HttpResponse<byte[]> getPresigned(URI url) throws IOException, InterruptedException {
    return http.send(presignedRequest(url), BodyHandlers.ofByteArray());
  }

  @Override
  public HttpResponse<InputStream> getPresignedStream(URI url)
      throws IOException, InterruptedException {
    return http.send(presignedRequest(url), BodyHandlers.ofInputStream());
  }

  // -- Async verbs ----------------------------------------------------

  @Override
  public CompletableFuture<HttpResponse<byte[]>> postAsync(
      String path, Object body, String idempotencyKey) {
    return safeSendAsync(() -> postRequest(path, body, idempotencyKey), BodyHandlers.ofByteArray());
  }

  @Override
  public CompletableFuture<HttpResponse<byte[]>> getAsync(String path) {
    return safeSendAsync(() -> baselessRequest(path).GET().build(), BodyHandlers.ofByteArray());
  }

  @Override
  public CompletableFuture<HttpResponse<byte[]>> deleteAsync(String path) {
    return safeSendAsync(() -> baselessRequest(path).DELETE().build(), BodyHandlers.ofByteArray());
  }

  @Override
  public CompletableFuture<HttpResponse<byte[]>> getPresignedAsync(URI url) {
    return safeSendAsync(() -> presignedRequest(url), BodyHandlers.ofByteArray());
  }

  @Override
  public CompletableFuture<HttpResponse<InputStream>> getPresignedStreamAsync(URI url) {
    return safeSendAsync(() -> presignedRequest(url), BodyHandlers.ofInputStream());
  }

  // -- Request construction ------------------------------------------

  private HttpRequest postRequest(String path, Object body, String idempotencyKey) {
    byte[] payload = serialize(body);
    return HttpRequest.newBuilder(Urls.join(baseUrl, path))
        .timeout(requestTimeout)
        .header("Authorization", "Bearer " + apiKey)
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .header("User-Agent", USER_AGENT)
        .header("Idempotency-Key", idempotencyKey)
        .POST(BodyPublishers.ofByteArray(payload))
        .build();
  }

  private HttpRequest.Builder baselessRequest(String path) {
    return HttpRequest.newBuilder(Urls.join(baseUrl, path))
        .timeout(requestTimeout)
        .header("Authorization", "Bearer " + apiKey)
        .header("Accept", "application/json")
        .header("User-Agent", USER_AGENT);
  }

  private HttpRequest presignedRequest(URI url) {
    return HttpRequest.newBuilder(url)
        .timeout(requestTimeout)
        .header("User-Agent", USER_AGENT)
        .GET()
        .build();
  }

  /**
   * Build the request inside a try/catch so that a request-construction failure (e.g. {@link
   * UncheckedIOException} from JSON serialization) surfaces as a failed future, never as a thrown
   * exception from an async method. Keeps the async contract clean of checked or eager-throw
   * failure modes.
   */
  private <T> CompletableFuture<HttpResponse<T>> safeSendAsync(
      RequestSupplier supplier, HttpResponse.BodyHandler<T> handler) {
    HttpRequest request;
    try {
      request = supplier.get();
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
    return http.sendAsync(request, handler);
  }

  @FunctionalInterface
  private interface RequestSupplier {
    HttpRequest get();
  }

  private byte[] serialize(Object body) {
    try {
      return mapper.writeValueAsBytes(body);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(
          "Failed to serialize request body of type " + body.getClass().getName(), e);
    }
  }
}

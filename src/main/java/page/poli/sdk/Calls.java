package page.poli.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.internal.Download;
import page.poli.sdk.internal.Json;
import page.poli.sdk.internal.RetryLoop;
import page.poli.sdk.internal.Transport;
import page.poli.sdk.internal.Urls;

/**
 * What the four facades share: one API call through the retry loop, the presigned download, and
 * reading a 2xx JSON body.
 */
final class Calls {

  private static final String REQUEST_ID_HEADER = "x-request-id";

  private final Transport transport;
  private final ObjectMapper mapper;
  private final RetryLoop retry;
  private final Download download;

  Calls(Transport transport, ObjectMapper mapper, RetryLoop retry, Download download) {
    this.transport = transport;
    this.mapper = mapper;
    this.retry = retry;
    this.download = download;
  }

  Download download() {
    return download;
  }

  /** A blocking call: the 2xx answer, or the last error. */
  HttpResponse<byte[]> send(String method, String path) {
    return retry.execute(
        capture -> transport.send(method, path, null, null, capture), method, url(path));
  }

  /** A blocking {@code POST} with a JSON body; every attempt sends the same Idempotency-Key. */
  HttpResponse<byte[]> post(String path, Object body, @Nullable String idempotencyKey) {
    byte[] json = Json.bytes(mapper, body);
    String key = keyOf(idempotencyKey);
    return retry.execute(
        capture -> transport.send("POST", path, json, key, capture), "POST", url(path));
  }

  /** Async variant of {@link #send(String, String)}. */
  CompletableFuture<HttpResponse<byte[]>> sendAsync(String method, String path) {
    return retry.executeAsync(
        capture -> transport.send(method, path, null, null, capture), method, url(path));
  }

  /** Async variant of {@link #post(String, Object, String)}. */
  CompletableFuture<HttpResponse<byte[]>> postAsync(
      String path, Object body, @Nullable String idempotencyKey) {
    byte[] json;
    try {
      json = Json.bytes(mapper, body);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
    String key = keyOf(idempotencyKey);
    return retry.executeAsync(
        capture -> transport.send("POST", path, json, key, capture), "POST", url(path));
  }

  /** Read a 2xx JSON body. */
  <T> T json(HttpResponse<byte[]> response, String path, Class<T> type) {
    try {
      return mapper.readValue(response.body(), type);
    } catch (IOException e) {
      throw new PoliPageException(
          "invalid_response",
          response.statusCode(),
          "Failed to parse " + path + " response as " + type.getSimpleName(),
          response.headers().firstValue(REQUEST_ID_HEADER).orElse(null),
          e);
    }
  }

  private URI url(String path) {
    return Urls.join(transport.baseUrl(), path);
  }

  private static String keyOf(@Nullable String idempotencyKey) {
    return idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
  }
}

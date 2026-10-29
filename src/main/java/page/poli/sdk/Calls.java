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
import page.poli.sdk.internal.Futures;
import page.poli.sdk.internal.Hooks;
import page.poli.sdk.internal.Json;
import page.poli.sdk.internal.RetryLoop;
import page.poli.sdk.internal.Transport;
import page.poli.sdk.internal.Urls;

/**
 * What the four facades share: one API call through the retry loop, the presigned download, reading
 * a 2xx JSON body, and the futures the async facades return. Every error a call ends with fires
 * {@code onError} once, where it is raised.
 */
final class Calls {

  private static final String REQUEST_ID_HEADER = "x-request-id";

  private final Transport transport;
  private final ObjectMapper mapper;
  private final RetryLoop retry;
  private final Download download;
  private final Hooks hooks;
  private final Futures futures;

  Calls(Transport transport, ObjectMapper mapper, RetryLoop retry, Download download, Hooks hooks) {
    this.transport = transport;
    this.mapper = mapper;
    this.retry = retry;
    this.download = download;
    this.hooks = hooks;
    this.futures = new Futures(hooks);
  }

  Download download() {
    return download;
  }

  Futures futures() {
    return futures;
  }

  /** Fire {@code onError} with {@code error}, and return it to throw. */
  <E extends Throwable> E failed(E error) {
    hooks.error(error);
    return error;
  }

  /** A blocking call: the 2xx answer, or the last error. */
  HttpResponse<byte[]> send(String method, String path) {
    return retry.execute(
        capture -> transport.send(method, path, null, null, capture), method, url(path));
  }

  /** A blocking {@code POST} with a JSON body; every attempt sends the same Idempotency-Key. */
  HttpResponse<byte[]> post(String path, Object body, @Nullable String idempotencyKey) {
    byte[] json = serialize(body);
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
      json = serialize(body);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
    String key = keyOf(idempotencyKey);
    return retry.executeAsync(
        capture -> transport.send("POST", path, json, key, capture), "POST", url(path));
  }

  /** Read a 2xx JSON body; a body that does not parse is {@code invalid_response}. */
  <T> T json(HttpResponse<byte[]> response, String path, Class<T> type) {
    try {
      return mapper.readValue(response.body(), type);
    } catch (IOException e) {
      throw failed(
          new PoliPageException(
              "invalid_response",
              response.statusCode(),
              "Failed to parse " + path + " response as " + type.getSimpleName(),
              response.headers().firstValue(REQUEST_ID_HEADER).orElse(null),
              e));
    }
  }

  private byte[] serialize(Object body) {
    try {
      return Json.bytes(mapper, body);
    } catch (RuntimeException e) {
      throw failed(e);
    }
  }

  private URI url(String path) {
    return Urls.join(transport.baseUrl(), path);
  }

  private static String keyOf(@Nullable String idempotencyKey) {
    return idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
  }
}

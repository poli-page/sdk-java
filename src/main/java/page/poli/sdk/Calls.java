package page.poli.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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

  /**
   * Read a 2xx JSON body. A body that is not JSON (an HTML page from a proxy or a captive portal,
   * an empty body, JSON followed by anything else) is {@code invalid_response} with the message
   * {@code Invalid response: HTTP <status> body is not JSON}, the real 2xx status and the answer's
   * request id. JSON that does not fit {@code type} is {@code invalid_response} too, naming the
   * type. Both come after the retry loop, so neither is retried.
   */
  <T> T json(HttpResponse<byte[]> response, String path, Class<T> type) {
    JsonNode tree;
    try {
      tree =
          mapper
              .reader()
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(response.body());
    } catch (IOException e) {
      throw failed(invalidResponse(response, notJson(response), e));
    }
    if (tree == null || tree.isMissingNode()) {
      throw failed(invalidResponse(response, notJson(response), null));
    }
    T value;
    try {
      value = mapper.treeToValue(tree, type);
    } catch (IOException e) {
      throw failed(invalidResponse(response, wrongShape(path, type), e));
    }
    if (value == null) {
      // A literal JSON null binds to null: the answer is JSON, but not the expected object.
      throw failed(invalidResponse(response, wrongShape(path, type), null));
    }
    return value;
  }

  private static String wrongShape(String path, Class<?> type) {
    return "Failed to parse " + path + " response as " + type.getSimpleName();
  }

  private static String notJson(HttpResponse<byte[]> response) {
    return "Invalid response: HTTP " + response.statusCode() + " body is not JSON";
  }

  private static PoliPageException invalidResponse(
      HttpResponse<byte[]> response, String message, @Nullable Throwable cause) {
    return new PoliPageException(
        PoliPageErrorCode.INVALID_RESPONSE,
        response.statusCode(),
        message,
        response.headers().firstValue(REQUEST_ID_HEADER).orElse(null),
        cause);
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

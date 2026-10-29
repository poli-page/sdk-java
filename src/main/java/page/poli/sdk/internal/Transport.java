package page.poli.sdk.internal;

import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Internal HTTP seam: starts one HTTP exchange and returns its pending answer. Retries, timeouts
 * and error mapping live in {@link RetryLoop} and {@link Download}, around this interface.
 *
 * <p>Both verbs start the exchange asynchronously; a failure arrives as a failed future, never as a
 * thrown exception. Each request is sent once: the HTTP client never resends it by itself (see
 * {@link SingleExchangeBody}).
 */
public interface Transport {

  /**
   * Returns the base URL the transport is configured with.
   *
   * @return the absolute base URL
   */
  URI baseUrl();

  /**
   * Start an API request to {@code baseUrl + path}, with the {@code Authorization}, {@code Accept}
   * and {@code User-Agent} headers.
   *
   * @param method {@code GET}, {@code POST} or {@code DELETE}
   * @param path path relative to the base URL (e.g. {@code "/v1/render"})
   * @param json the JSON request body, sent with {@code Content-Type: application/json}, or {@code
   *     null} for none
   * @param idempotencyKey the {@code Idempotency-Key} header, or {@code null} for none
   * @param handler reads the answer's body
   * @param <T> the body type
   * @return the pending answer
   */
  <T> CompletableFuture<HttpResponse<T>> send(
      String method,
      String path,
      byte @Nullable [] json,
      @Nullable String idempotencyKey,
      BodyHandler<T> handler);

  /**
   * Start a plain {@code GET} of a presigned URL: no {@code Authorization}, no {@code
   * Idempotency-Key}.
   *
   * @param url the presigned URL
   * @param handler reads the answer's body
   * @param <T> the body type
   * @return the pending answer
   */
  <T> CompletableFuture<HttpResponse<T>> download(URI url, BodyHandler<T> handler);
}

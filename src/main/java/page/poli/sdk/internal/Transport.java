package page.poli.sdk.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Internal HTTP seam. Phase 2 exposes just the two verbs the {@link page.poli.sdk.Render} facade
 * needs:
 *
 * <ul>
 *   <li>{@link #post(String, Object, String)} — authenticated JSON POST to the SDK base URL.
 *   <li>{@link #getPresigned(URI)} — plain, unauthenticated GET to a presigned S3 URL.
 * </ul>
 *
 * <p>Later phases expand the interface with sync GET / DELETE (Phase 6) and async variants
 * (Phase 7). Retry orchestration (Phase 4) wraps an instance of this interface rather than living
 * inside it.
 */
public interface Transport {

  /**
   * Send a JSON POST to {@code baseUrl + path} with the standard auth / accept / idempotency
   * headers.
   *
   * @param path path relative to the configured base URL (e.g. {@code "/v1/render"})
   * @param body POJO serialized to JSON via the SDK's configured ObjectMapper
   * @param idempotencyKey value for the {@code Idempotency-Key} header
   * @return the raw response with bytes body — caller parses
   * @throws IOException on transport failure
   * @throws InterruptedException if the calling thread is interrupted
   */
  HttpResponse<byte[]> post(String path, Object body, String idempotencyKey)
      throws IOException, InterruptedException;

  /**
   * Send a GET to {@code baseUrl + path} with auth / accept / user-agent headers (no
   * {@code Content-Type}, no {@code Idempotency-Key} — per Node SDK {@code internal/http.ts}).
   *
   * @param path path relative to the configured base URL (e.g. {@code "/v1/documents/foo"})
   * @return the raw response with bytes body
   * @throws IOException on transport failure
   * @throws InterruptedException if the calling thread is interrupted
   */
  HttpResponse<byte[]> get(String path) throws IOException, InterruptedException;

  /**
   * Send a DELETE to {@code baseUrl + path} with auth / accept / user-agent headers.
   *
   * @param path path relative to the configured base URL
   * @return the raw response with bytes body — usually empty
   * @throws IOException on transport failure
   * @throws InterruptedException if the calling thread is interrupted
   */
  HttpResponse<byte[]> delete(String path) throws IOException, InterruptedException;

  /**
   * Fetch the body of a presigned S3 URL with a plain GET. The SDK MUST NOT include its Bearer
   * token here — the URL is pre-authorized.
   *
   * @param url the presigned URL returned in a {@code DocumentDescriptor}
   * @return response bytes, even on non-2xx status codes (caller decides)
   * @throws IOException on transport failure
   * @throws InterruptedException if the calling thread is interrupted
   */
  HttpResponse<byte[]> getPresigned(URI url) throws IOException, InterruptedException;

  /**
   * Stream-flavoured counterpart of {@link #getPresigned(URI)}: return the body as an
   * {@link InputStream} so the caller can read large PDFs without buffering the full payload.
   * The returned {@code InputStream} owns the HTTP connection; the caller MUST close it
   * (try-with-resources) or the connection is leaked from the pool.
   *
   * @param url the presigned URL returned in a {@code DocumentDescriptor}
   * @return response with an {@link InputStream} body
   * @throws IOException on transport failure
   * @throws InterruptedException if the calling thread is interrupted
   */
  HttpResponse<InputStream> getPresignedStream(URI url) throws IOException, InterruptedException;

  // -- Async counterparts ----------------------------------------------
  //
  // Every sync verb has a matching async counterpart returning a CompletableFuture. The async
  // path uses HttpClient.sendAsync(...) under the hood — no extra thread per request beyond what
  // the JDK's selector thread does. Transport failures arrive as a failed future
  // (CompletionException wrapping the original cause), not as a thrown exception, so the
  // signatures stay clean of checked exceptions.

  /** Async counterpart of {@link #post(String, Object, String)}. */
  CompletableFuture<HttpResponse<byte[]>> postAsync(
      String path, Object body, String idempotencyKey);

  /** Async counterpart of {@link #get(String)}. */
  CompletableFuture<HttpResponse<byte[]>> getAsync(String path);

  /** Async counterpart of {@link #delete(String)}. */
  CompletableFuture<HttpResponse<byte[]>> deleteAsync(String path);

  /** Async counterpart of {@link #getPresigned(URI)}. */
  CompletableFuture<HttpResponse<byte[]>> getPresignedAsync(URI url);

  /** Async counterpart of {@link #getPresignedStream(URI)}. */
  CompletableFuture<HttpResponse<InputStream>> getPresignedStreamAsync(URI url);
}

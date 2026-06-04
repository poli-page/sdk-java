package page.poli.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpHeaders;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import page.poli.sdk.PoliPageErrorCode;
import page.poli.sdk.exception.PoliPageDownloadException;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.exception.PoliPageNetworkException;
import page.poli.sdk.internal.ErrorParsing;
import page.poli.sdk.internal.RetryLoop;
import page.poli.sdk.internal.Transport;
import page.poli.sdk.model.DocumentDescriptor;
import page.poli.sdk.model.DocumentPreviewResult;
import page.poli.sdk.model.Thumbnail;
import page.poli.sdk.model.ThumbnailOptions;

/**
 * Blocking facade for the stored-documents API. Obtain via {@link PoliPageClient#documents()}.
 *
 * <ul>
 *   <li>{@link #get(String)} — retrieve a document descriptor with a fresh presigned URL
 *   <li>{@link #preview(String)} — fetch the stored HTML body + page count
 *   <li>{@link #thumbnails(String, ThumbnailOptions)} — request page thumbnails
 *   <li>{@link #delete(String)} — soft-delete a stored document
 * </ul>
 *
 * <p>All four methods run through the SDK's {@link RetryLoop}. {@code delete} is idempotent on the
 * server side; the SDK retries on 5xx/429 like the other verbs. A re-delete of an already-deleted
 * document surfaces as {@link page.poli.sdk.exception.PoliPageGoneException} (410), not an error to
 * swallow.
 */
public final class Documents {

  private static final String DOCUMENTS_PATH = "/v1/documents/";
  private static final String REQUEST_ID_HEADER = "x-request-id";
  private static final String RETRY_AFTER_HEADER = "retry-after";
  private static final String PAGE_COUNT_HEADER = "X-Document-Page-Count";

  private final Transport transport;
  private final ObjectMapper mapper;
  private final RetryLoop retry;

  Documents(Transport transport, ObjectMapper mapper, RetryLoop retry) {
    this.transport = transport;
    this.mapper = mapper;
    this.retry = retry;
  }

  /**
   * Retrieve a stored document descriptor with a fresh {@code presignedPdfUrl}.
   *
   * @param id the document ID — non-null, non-blank
   * @return the descriptor
   * @throws PoliPageException on any non-2xx response after retries, or transport failure
   */
  public DocumentDescriptor get(String id) {
    String path = DOCUMENTS_PATH + encode(id);
    HttpResponse<byte[]> response = retry.execute(() -> transport.get(path), "GET " + path);
    failIfNotSuccess(response, path);
    return parseJson(response, path, DocumentDescriptor.class);
  }

  /**
   * Fetch the stored document's HTML preview and the {@code X-Document-Page-Count} header,
   * assembled into a {@link DocumentPreviewResult}. The deployed API responds with {@code
   * text/html} directly (not a JSON envelope) — the SDK reads the body as UTF-8 text and the page
   * count from the header.
   *
   * @param id the document ID
   * @return the preview result; {@link DocumentPreviewResult#pageCount()} is {@code 0} if the
   *     header was missing or malformed
   * @throws PoliPageException on any non-2xx response after retries, or transport failure
   */
  public DocumentPreviewResult preview(String id) {
    String path = DOCUMENTS_PATH + encode(id) + "/preview";
    HttpResponse<byte[]> response = retry.execute(() -> transport.get(path), "GET " + path);
    failIfNotSuccess(response, path);
    String html = new String(response.body(), StandardCharsets.UTF_8);
    int pageCount = parsePageCount(header(response, PAGE_COUNT_HEADER));
    return new DocumentPreviewResult(html, pageCount);
  }

  /**
   * Request thumbnails of the stored document.
   *
   * <p>The deployed API wraps the request body under a {@code "thumbnails"} key and returns the
   * array under the same key — the SDK handles both translations so callers just deal in {@link
   * ThumbnailOptions} and {@code List<Thumbnail>}.
   *
   * @param id the document ID
   * @param options the thumbnail options
   * @return list of generated thumbnails
   * @throws PoliPageException on any non-2xx response after retries, or transport failure
   */
  public List<Thumbnail> thumbnails(String id, ThumbnailOptions options) {
    String path = DOCUMENTS_PATH + encode(id) + "/thumbnails";
    Map<String, ThumbnailOptions> wireBody = Map.of("thumbnails", options);
    String idempotencyKey =
        options.idempotencyKey() != null ? options.idempotencyKey() : UUID.randomUUID().toString();
    HttpResponse<byte[]> response =
        retry.execute(() -> transport.post(path, wireBody, idempotencyKey), "POST " + path);
    failIfNotSuccess(response, path);
    ThumbnailResponse wrap = parseJson(response, path, ThumbnailResponse.class);
    return wrap.thumbnails();
  }

  /**
   * Soft-delete a stored document. The deleted document can no longer be fetched; subsequent {@link
   * #get(String)} or {@link #preview(String)} calls will throw {@link
   * page.poli.sdk.exception.PoliPageGoneException} (HTTP 410).
   *
   * @param id the document ID
   * @throws PoliPageException on any non-2xx response after retries, or transport failure
   */
  public void delete(String id) {
    String path = DOCUMENTS_PATH + encode(id);
    HttpResponse<byte[]> response = retry.execute(() -> transport.delete(path), "DELETE " + path);
    failIfNotSuccess(response, path);
    // Response body intentionally ignored — spec §6.4.
  }

  /**
   * Fetch the PDF bytes from a descriptor's {@code presignedPdfUrl}. The URL has a short TTL (~15
   * minutes); if it expired, re-fetch with {@link #get(String)} and try again.
   *
   * <p>The presigned download is intentionally NOT retried — the URL is single-use against S3, and
   * an expired-signature error won't recover.
   *
   * @param descriptor the descriptor returned by {@link #get(String)} or {@link
   *     page.poli.sdk.Render#document(page.poli.sdk.input.ProjectModeInput)}
   * @return the PDF bytes
   * @throws PoliPageDownloadException on non-2xx or transport failure
   */
  public byte[] downloadPdf(DocumentDescriptor descriptor) {
    URI url = URI.create(descriptor.presignedPdfUrl());
    HttpResponse<byte[]> response;
    try {
      response = transport.getPresigned(url);
    } catch (HttpTimeoutException e) {
      throw new PoliPageNetworkException(
          PoliPageErrorCode.TIMEOUT, "downloadPdf timed out: " + e.getMessage(), e);
    } catch (IOException e) {
      throw new PoliPageDownloadException(
          PoliPageErrorCode.DOWNLOAD_FAILED,
          0,
          "Failed to download PDF from presigned URL: " + e.getMessage(),
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PoliPageException(PoliPageErrorCode.ABORTED, 0, "Download was interrupted", null, e);
    }
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      throw new PoliPageDownloadException(
          PoliPageErrorCode.DOWNLOAD_FAILED,
          status,
          "Failed to download PDF from presigned URL: HTTP " + status,
          null);
    }
    return response.body();
  }

  // -- internals -----------------------------------------------------

  private void failIfNotSuccess(HttpResponse<byte[]> response, String path) {
    int status = response.statusCode();
    if (status >= 200 && status < 300) {
      return;
    }
    throw ErrorParsing.toException(
        status,
        response.body(),
        header(response, REQUEST_ID_HEADER),
        header(response, RETRY_AFTER_HEADER),
        mapper);
  }

  private <T> T parseJson(HttpResponse<byte[]> response, String path, Class<T> type) {
    try {
      return mapper.readValue(response.body(), type);
    } catch (IOException e) {
      throw new PoliPageException(
          "invalid_response",
          response.statusCode(),
          "Failed to parse " + path + " response as " + type.getSimpleName(),
          header(response, REQUEST_ID_HEADER),
          e);
    }
  }

  /**
   * Percent-encode a path segment. {@code URLEncoder.encode} encodes for {@code
   * x-www-form-urlencoded} (spaces become {@code +}); the {@code +} -> {@code %20} substitution
   * fixes that for path segments.
   */
  private static String encode(String segment) {
    return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static int parsePageCount(@Nullable String headerValue) {
    if (headerValue == null) {
      return 0;
    }
    try {
      int n = Integer.parseInt(headerValue.trim());
      return n >= 0 ? n : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static @Nullable String header(HttpResponse<?> response, String name) {
    HttpHeaders headers = response.headers();
    return headers.firstValue(name).orElse(null);
  }

  /** Wire envelope: {@code {"thumbnails": [...]}}. */
  private record ThumbnailResponse(@JsonProperty("thumbnails") List<Thumbnail> thumbnails) {}
}

package page.poli.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import page.poli.sdk.exception.PoliPageDownloadException;
import page.poli.sdk.exception.PoliPageException;
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
 *   <li>{@link #thumbnails(String)} / {@link #thumbnails(String, ThumbnailOptions)} — request page
 *       thumbnails
 *   <li>{@link #delete(String)} — soft-delete a stored document
 * </ul>
 *
 * <p>All four methods run through the SDK's retry policy. {@code delete} is idempotent: the API
 * answers {@code 204} for an already-deleted document too, so a re-delete returns normally. The SDK
 * retries it like the other verbs. Interrupting the calling thread ends a call with {@code
 * aborted}.
 */
public final class Documents {

  private static final String DOCUMENTS_PATH = "/v1/documents/";
  private static final String PAGE_COUNT_HEADER = "X-Document-Page-Count";

  private final Calls calls;

  Documents(Calls calls) {
    this.calls = calls;
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
    return calls.json(calls.send("GET", path), path, DocumentDescriptor.class);
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
    HttpResponse<byte[]> response = calls.send("GET", path);
    String html = new String(response.body(), StandardCharsets.UTF_8);
    int pageCount = parsePageCount(header(response, PAGE_COUNT_HEADER));
    return new DocumentPreviewResult(html, pageCount);
  }

  /**
   * Request thumbnails of the stored document with the API defaults (400 px wide {@code jpeg},
   * every page). The request body is {@code {}}.
   *
   * @param id the document ID
   * @return list of generated thumbnails
   * @throws PoliPageException on any non-2xx response after retries, or transport failure
   */
  public List<Thumbnail> thumbnails(String id) {
    return thumbnails(id, ThumbnailOptions.builder().build());
  }

  /**
   * Request thumbnails of the stored document.
   *
   * <p>The deployed API wraps the request body under a {@code "thumbnails"} key and returns the
   * array under the same key — the SDK handles both translations so callers just deal in {@link
   * ThumbnailOptions} and {@code List<Thumbnail>}. Only the options that are set are sent; with
   * none set, the body is {@code {}}.
   *
   * @param id the document ID
   * @param options the thumbnail options
   * @return list of generated thumbnails
   * @throws PoliPageException on any non-2xx response after retries, or transport failure
   */
  public List<Thumbnail> thumbnails(String id, ThumbnailOptions options) {
    String path = DOCUMENTS_PATH + encode(id) + "/thumbnails";
    HttpResponse<byte[]> response =
        calls.post(path, thumbnailsWireBody(options), options.idempotencyKey());
    return calls.json(response, path, ThumbnailResponse.class).thumbnails();
  }

  /**
   * Soft-delete a stored document. The deleted document can no longer be fetched; subsequent {@link
   * #get(String)} or {@link #preview(String)} calls will throw {@link
   * page.poli.sdk.exception.PoliPageGoneException} (HTTP 410, {@code DOCUMENT_GONE}). Deleting an
   * already-deleted document also returns normally: the API answers {@code 204} either way.
   *
   * @param id the document ID
   * @throws PoliPageException on any non-2xx response after retries, or transport failure
   */
  public void delete(String id) {
    calls.send("DELETE", path(id));
    // Response body intentionally ignored — spec §7.5.
  }

  /**
   * Fetch the PDF bytes from a descriptor's {@code presignedPdfUrl}. The URL has a short TTL (~15
   * minutes); if it expired, re-fetch with {@link #get(String)} and try again.
   *
   * <p>The presigned download is never retried, and sends no {@code Authorization} header. A
   * non-2xx answer raises {@code DOWNLOAD_FAILED} with the storage status ({@code 403} for an
   * expired URL); a network failure or the client's timeout raise {@code DOWNLOAD_FAILED} without
   * one.
   *
   * @param descriptor the descriptor returned by {@link #get(String)} or {@link
   *     page.poli.sdk.Render#document(page.poli.sdk.input.RenderInput)}
   * @return the PDF bytes
   * @throws PoliPageDownloadException on non-2xx or transport failure
   * @throws PoliPageException {@code aborted} when the calling thread is interrupted
   */
  public byte[] downloadPdf(DocumentDescriptor descriptor) {
    return calls.download().bytes(descriptor.presignedPdfUrl());
  }

  // -- internals -----------------------------------------------------

  private static String path(String id) {
    return DOCUMENTS_PATH + encode(id);
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
    return response.headers().firstValue(name).orElse(null);
  }

  /**
   * Request envelope for the thumbnails call: {@code {"thumbnails": {...}}} when an option is set,
   * {@code {}} otherwise.
   */
  static Map<String, ThumbnailOptions> thumbnailsWireBody(ThumbnailOptions options) {
    boolean anySet =
        options.width() != null
            || options.format() != null
            || options.quality() != null
            || options.pages() != null;
    return anySet ? Map.of("thumbnails", options) : Map.of();
  }

  /** Wire envelope: {@code {"thumbnails": [...]}}. */
  private record ThumbnailResponse(@JsonProperty("thumbnails") List<Thumbnail> thumbnails) {}
}

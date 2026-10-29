package page.poli.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;
import page.poli.sdk.model.DocumentDescriptor;
import page.poli.sdk.model.DocumentPreviewResult;
import page.poli.sdk.model.Thumbnail;
import page.poli.sdk.model.ThumbnailOptions;

/**
 * Asynchronous counterpart of {@link Documents}. Obtain via {@link PoliPageClient#documentsAsync}.
 *
 * <p>Same methods, same wire contract as {@link Documents}; the return type becomes {@code
 * CompletableFuture<T>} and {@code delete} returns {@code CompletableFuture<Void>}. Exceptions
 * inside the chain wrap in {@link CompletionException} per JDK convention.
 *
 * <p>Cancelling a returned future ({@code cancel(true)}) aborts the call: the attempt in flight,
 * the retry wait or the download is cancelled and no further request is sent. {@code get()} and
 * {@code join()} then throw a {@code CancellationException} whose {@code getCause()} is the SDK's
 * {@code aborted} error, and {@code onError} fires once with it.
 */
public final class DocumentsAsync {

  private static final String DOCUMENTS_PATH = "/v1/documents/";
  private static final String PAGE_COUNT_HEADER = "X-Document-Page-Count";

  private final Calls calls;

  DocumentsAsync(Calls calls) {
    this.calls = calls;
  }

  /** Async variant of {@link Documents#get(String)}. */
  public CompletableFuture<DocumentDescriptor> get(String id) {
    String path = DOCUMENTS_PATH + encode(id);
    return calls
        .futures()
        .map(
            calls.sendAsync("GET", path),
            response -> calls.json(response, path, DocumentDescriptor.class));
  }

  /** Async variant of {@link Documents#preview(String)}. */
  public CompletableFuture<DocumentPreviewResult> preview(String id) {
    String path = DOCUMENTS_PATH + encode(id) + "/preview";
    return calls
        .futures()
        .map(
            calls.sendAsync("GET", path),
            response -> {
              String html = new String(response.body(), StandardCharsets.UTF_8);
              String count = response.headers().firstValue(PAGE_COUNT_HEADER).orElse(null);
              return new DocumentPreviewResult(html, parsePageCount(count));
            });
  }

  /**
   * Async variant of {@link Documents#thumbnails(String)}.
   *
   * @param id the document ID
   * @return a future of the generated thumbnails
   */
  public CompletableFuture<List<Thumbnail>> thumbnails(String id) {
    return thumbnails(id, ThumbnailOptions.builder().build());
  }

  /** Async variant of {@link Documents#thumbnails(String, ThumbnailOptions)}. */
  public CompletableFuture<List<Thumbnail>> thumbnails(String id, ThumbnailOptions options) {
    String path = DOCUMENTS_PATH + encode(id) + "/thumbnails";
    return calls
        .futures()
        .map(
            calls.postAsync(path, Documents.thumbnailsWireBody(options), options.idempotencyKey()),
            response -> calls.json(response, path, ThumbnailResponse.class).thumbnails());
  }

  /** Async variant of {@link Documents#delete(String)}. */
  public CompletableFuture<Void> delete(String id) {
    return calls
        .futures()
        .map(calls.sendAsync("DELETE", DOCUMENTS_PATH + encode(id)), response -> null);
  }

  /**
   * Async variant of {@link page.poli.sdk.Documents#downloadPdf(DocumentDescriptor)}.
   *
   * @param descriptor the descriptor whose {@code presignedPdfUrl} to fetch
   * @return a future of the PDF bytes; completes exceptionally with {@code
   *     CompletionException(PoliPageDownloadException)} on non-2xx or transport failure
   */
  public CompletableFuture<byte[]> downloadPdf(DocumentDescriptor descriptor) {
    return calls.futures().map(calls.download().bytesAsync(descriptor.presignedPdfUrl()), b -> b);
  }

  // -- internals -----------------------------------------------------

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

  /** Wire envelope: {@code {"thumbnails": [...]}}. */
  private record ThumbnailResponse(@JsonProperty("thumbnails") List<Thumbnail> thumbnails) {}
}

package page.poli.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.internal.ErrorParsing;
import page.poli.sdk.internal.RetryLoop;
import page.poli.sdk.internal.Transport;
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
 */
public final class DocumentsAsync {

  private static final String DOCUMENTS_PATH = "/v1/documents/";
  private static final String REQUEST_ID_HEADER = "x-request-id";
  private static final String RETRY_AFTER_HEADER = "retry-after";
  private static final String PAGE_COUNT_HEADER = "X-Document-Page-Count";

  private final Transport transport;
  private final ObjectMapper mapper;
  private final RetryLoop retry;

  DocumentsAsync(Transport transport, ObjectMapper mapper, RetryLoop retry) {
    this.transport = transport;
    this.mapper = mapper;
    this.retry = retry;
  }

  /** Async variant of {@link Documents#get(String)}. */
  public CompletableFuture<DocumentDescriptor> get(String id) {
    String path = DOCUMENTS_PATH + encode(id);
    return retry
        .executeAsync(() -> transport.getAsync(path), "GET " + path)
        .thenApply(response -> mapJson(response, path, DocumentDescriptor.class));
  }

  /** Async variant of {@link Documents#preview(String)}. */
  public CompletableFuture<DocumentPreviewResult> preview(String id) {
    String path = DOCUMENTS_PATH + encode(id) + "/preview";
    return retry
        .executeAsync(() -> transport.getAsync(path), "GET " + path)
        .thenApply(
            response -> {
              failIfNotSuccess(response, path);
              String html = new String(response.body(), StandardCharsets.UTF_8);
              int pageCount = parsePageCount(header(response, PAGE_COUNT_HEADER));
              return new DocumentPreviewResult(html, pageCount);
            });
  }

  /** Async variant of {@link Documents#thumbnails(String, ThumbnailOptions)}. */
  public CompletableFuture<List<Thumbnail>> thumbnails(String id, ThumbnailOptions options) {
    String path = DOCUMENTS_PATH + encode(id) + "/thumbnails";
    Map<String, ThumbnailOptions> wireBody = Map.of("thumbnails", options);
    String idempotencyKey =
        options.idempotencyKey() != null ? options.idempotencyKey() : UUID.randomUUID().toString();
    return retry
        .executeAsync(() -> transport.postAsync(path, wireBody, idempotencyKey), "POST " + path)
        .thenApply(
            response -> {
              ThumbnailResponse wrap = mapJson(response, path, ThumbnailResponse.class);
              return wrap.thumbnails();
            });
  }

  /** Async variant of {@link Documents#delete(String)}. */
  public CompletableFuture<Void> delete(String id) {
    String path = DOCUMENTS_PATH + encode(id);
    return retry
        .executeAsync(() -> transport.deleteAsync(path), "DELETE " + path)
        .thenAccept(response -> failIfNotSuccess(response, path));
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

  private <T> T mapJson(HttpResponse<byte[]> response, String path, Class<T> type) {
    failIfNotSuccess(response, path);
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

  /** Wire envelope: {@code {"thumbnails": [...]}}. */
  private record ThumbnailResponse(@JsonProperty("thumbnails") List<Thumbnail> thumbnails) {}
}

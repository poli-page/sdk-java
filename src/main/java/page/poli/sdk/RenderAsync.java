package page.poli.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;
import page.poli.sdk.exception.PoliPageDownloadException;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.input.ProjectModeInput;
import page.poli.sdk.input.RenderInput;
import page.poli.sdk.internal.ErrorParsing;
import page.poli.sdk.internal.RetryLoop;
import page.poli.sdk.internal.Transport;
import page.poli.sdk.model.DocumentDescriptor;
import page.poli.sdk.model.PreviewResult;

/**
 * Asynchronous counterpart of {@link Render}. Obtain via {@link PoliPageClient#renderAsync()}.
 *
 * <p>Same methods, same wire contract, same retry policy as {@link Render} — only the return type
 * changes ({@code T} → {@code CompletableFuture<T>}). Exceptions inside the chain are wrapped in
 * {@link CompletionException} by the JDK; unwrap with {@code ex.getCause()} or use {@code
 * .handle((res, err) -> { … })}.
 *
 * <p>Internally uses {@code HttpClient.sendAsync(...)} so no worker thread is blocked while waiting
 * on the network. The async retry loop uses {@link CompletableFuture#delayedExecutor} for back-off;
 * no thread sleeps between attempts.
 *
 * <p>Cancelling the returned future via {@code cancel(true)} marks the future cancelled per JDK
 * semantics; the in-flight HTTP exchange is best-effort aborted (see plan §7.2).
 */
public final class RenderAsync {

  private static final String RENDER_PATH = "/v1/render";
  private static final String PREVIEW_PATH = "/v1/render/preview";
  private static final String REQUEST_ID_HEADER = "x-request-id";
  private static final String RETRY_AFTER_HEADER = "retry-after";

  private final Transport transport;
  private final ObjectMapper mapper;
  private final RetryLoop retry;

  RenderAsync(Transport transport, ObjectMapper mapper, RetryLoop retry) {
    this.transport = transport;
    this.mapper = mapper;
    this.retry = retry;
  }

  /**
   * Async variant of {@link Render#pdf(ProjectModeInput)} — returns the PDF bytes as a future.
   *
   * @param input the render input — non-null
   * @return a future of PDF bytes
   */
  public CompletableFuture<byte[]> pdf(ProjectModeInput input) {
    return document(input)
        .thenCompose(d -> transport.getPresignedAsync(URI.create(d.presignedPdfUrl())))
        .thenApply(
            response -> {
              if (!isSuccess(response.statusCode())) {
                throw new PoliPageDownloadException(
                    PoliPageErrorCode.DOWNLOAD_FAILED,
                    response.statusCode(),
                    "Failed to download PDF from presigned URL: HTTP " + response.statusCode(),
                    null);
              }
              return response.body();
            });
  }

  /**
   * Async variant of {@link Render#pdfStream(ProjectModeInput)} — returns an {@link InputStream} as
   * a future. Same close-the-stream contract: wrap in {@code try-with-resources} or leak a
   * connection slot.
   *
   * @param input the render input — non-null
   * @return a future of the streaming body
   */
  public CompletableFuture<InputStream> pdfStream(ProjectModeInput input) {
    return document(input)
        .thenCompose(d -> transport.getPresignedStreamAsync(URI.create(d.presignedPdfUrl())))
        .thenApply(
            response -> {
              if (!isSuccess(response.statusCode())) {
                try {
                  response.body().close();
                } catch (IOException ignored) {
                  // already failing
                }
                throw new PoliPageDownloadException(
                    PoliPageErrorCode.DOWNLOAD_FAILED,
                    response.statusCode(),
                    "Failed to download PDF from presigned URL: HTTP " + response.statusCode(),
                    null);
              }
              return response.body();
            });
  }

  /**
   * Async variant of {@link Render#document(ProjectModeInput)}.
   *
   * @param input the render input — non-null
   * @return a future of the parsed descriptor
   */
  public CompletableFuture<DocumentDescriptor> document(ProjectModeInput input) {
    return postAndParseAsync(RENDER_PATH, input, DocumentDescriptor.class);
  }

  /**
   * Async variant of {@link Render#preview(RenderInput)}.
   *
   * @param input the render input — accepts either mode
   * @return a future of the parsed preview result
   */
  public CompletableFuture<PreviewResult> preview(RenderInput input) {
    return postAndParseAsync(PREVIEW_PATH, input, PreviewResult.class);
  }

  // -- internals -----------------------------------------------------

  private <T> CompletableFuture<T> postAndParseAsync(String path, Object body, Class<T> type) {
    String idempotencyKey = UUID.randomUUID().toString();
    return retry
        .executeAsync(() -> transport.postAsync(path, body, idempotencyKey), "POST " + path)
        .thenApply(response -> mapResponse(response, path, type));
  }

  private <T> T mapResponse(HttpResponse<byte[]> response, String path, Class<T> type) {
    if (!isSuccess(response.statusCode())) {
      throw ErrorParsing.toException(
          response.statusCode(),
          response.body(),
          header(response, REQUEST_ID_HEADER),
          header(response, RETRY_AFTER_HEADER),
          mapper);
    }
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

  private static boolean isSuccess(int statusCode) {
    return statusCode >= 200 && statusCode < 300;
  }

  private static @Nullable String header(HttpResponse<?> response, String name) {
    HttpHeaders headers = response.headers();
    return headers.firstValue(name).orElse(null);
  }
}

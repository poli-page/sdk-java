package page.poli.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import page.poli.sdk.exception.PoliPageDownloadException;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.exception.PoliPageNetworkException;
import page.poli.sdk.input.ProjectModeInput;
import page.poli.sdk.input.RenderInput;
import page.poli.sdk.internal.ErrorParsing;
import page.poli.sdk.internal.RetryLoop;
import page.poli.sdk.internal.Transport;
import page.poli.sdk.model.DocumentDescriptor;
import page.poli.sdk.model.PreviewResult;

/**
 * Blocking render facade. Obtain via {@link PoliPageClient#render()}.
 *
 * <p>Phase 5 ships the full surface:
 *
 * <ul>
 *   <li>{@link #pdf(ProjectModeInput)} — render and return bytes (two HTTP hops)
 *   <li>{@link #pdfStream(ProjectModeInput)} — same but return an {@link InputStream} so large
 *       PDFs don't have to fit in memory; <strong>caller must close the stream</strong>
 *   <li>{@link #preview(RenderInput)} — render an HTML preview without storing a document
 *   <li>{@link #document(ProjectModeInput)} — render, store, return the descriptor (skip download)
 * </ul>
 *
 * <p>The {@code /v1/render} and {@code /v1/render/preview} POSTs are retried per the client's
 * policy; the presigned-URL download is not (per {@code sdk-java-plan.md} §3.4).
 */
public final class Render {

  private static final String RENDER_PATH = "/v1/render";
  private static final String PREVIEW_PATH = "/v1/render/preview";
  private static final String REQUEST_ID_HEADER = "x-request-id";
  private static final String RETRY_AFTER_HEADER = "retry-after";

  private final Transport transport;
  private final ObjectMapper mapper;
  private final RetryLoop retry;

  Render(Transport transport, ObjectMapper mapper, RetryLoop retry) {
    this.transport = transport;
    this.mapper = mapper;
    this.retry = retry;
  }

  /**
   * Render the given input as a PDF and return the raw bytes.
   *
   * <p>Convenience over {@link #document(ProjectModeInput)} + the presigned download. For large
   * PDFs, prefer {@link #pdfStream(ProjectModeInput)} to avoid buffering the whole payload.
   *
   * @param input the render input — non-null
   * @return the PDF bytes
   * @throws PoliPageException on any non-2xx response after retries, or transport failure
   */
  public byte[] pdf(ProjectModeInput input) {
    DocumentDescriptor descriptor = document(input);
    HttpResponse<byte[]> response =
        sendOnce(() -> transport.getPresigned(URI.create(descriptor.presignedPdfUrl())),
            "GET presignedPdfUrl");
    if (!isSuccess(response.statusCode())) {
      throw new PoliPageDownloadException(
          PoliPageErrorCode.DOWNLOAD_FAILED,
          response.statusCode(),
          "Failed to download PDF from presigned URL: HTTP " + response.statusCode(),
          null);
    }
    return response.body();
  }

  /**
   * Render the given input as a PDF and return a streaming body. Use this for large documents
   * (multi-MB) to avoid buffering the full byte array in memory.
   *
   * <p><strong>The returned {@link InputStream} owns the underlying HTTP connection.</strong>
   * Wrap the call in {@code try-with-resources} (or call {@code close()} explicitly) — leaking
   * the stream leaks a slot from the JDK's HTTP/2 connection pool.
   *
   * <pre>{@code
   * try (InputStream pdf = client.render().pdfStream(input)) {
   *     Files.copy(pdf, Path.of("invoice.pdf"));
   * }
   * }</pre>
   *
   * @param input the render input — non-null
   * @return an {@link InputStream} reading the PDF body — caller must close
   * @throws PoliPageException on any non-2xx response after retries, or transport failure
   */
  public InputStream pdfStream(ProjectModeInput input) {
    DocumentDescriptor descriptor = document(input);
    HttpResponse<InputStream> response =
        sendOnceStream(
            () -> transport.getPresignedStream(URI.create(descriptor.presignedPdfUrl())),
            "GET presignedPdfUrl (stream)");
    if (!isSuccess(response.statusCode())) {
      // Best-effort close to release the connection before throwing.
      try {
        response.body().close();
      } catch (IOException ignored) {
        // already failing — swallow
      }
      throw new PoliPageDownloadException(
          PoliPageErrorCode.DOWNLOAD_FAILED,
          response.statusCode(),
          "Failed to download PDF from presigned URL: HTTP " + response.statusCode(),
          null);
    }
    return response.body();
  }

  /**
   * Render the given input and return the stored document's descriptor without downloading the
   * PDF. Useful when the caller wants to persist {@code documentId} and download on demand later
   * (e.g. via a signed URL handed out from another service).
   *
   * @param input the render input — non-null
   * @return the descriptor
   * @throws PoliPageException on any non-2xx response after retries, or transport failure
   */
  public DocumentDescriptor document(ProjectModeInput input) {
    return postAndParse(RENDER_PATH, input, DocumentDescriptor.class);
  }

  /**
   * Render the given input as an HTML preview without producing a stored document.
   *
   * <p>Accepts either {@link ProjectModeInput} or
   * {@link page.poli.sdk.input.InlineModeInput} via the sealed {@link RenderInput} supertype.
   *
   * @param input the render input — non-null
   * @return the parsed preview result
   * @throws PoliPageException on any non-2xx response after retries, or transport failure
   */
  public PreviewResult preview(RenderInput input) {
    return postAndParse(PREVIEW_PATH, input, PreviewResult.class);
  }

  // -- internals -----------------------------------------------------

  private <T> T postAndParse(String path, Object body, Class<T> responseType) {
    String idempotencyKey = UUID.randomUUID().toString();
    HttpResponse<byte[]> response =
        retry.execute(() -> transport.post(path, body, idempotencyKey), "POST " + path);

    if (!isSuccess(response.statusCode())) {
      throw ErrorParsing.toException(
          response.statusCode(),
          response.body(),
          header(response, REQUEST_ID_HEADER),
          header(response, RETRY_AFTER_HEADER),
          mapper);
    }

    try {
      return mapper.readValue(response.body(), responseType);
    } catch (IOException e) {
      throw new PoliPageException(
          "invalid_response",
          response.statusCode(),
          "Failed to parse " + path + " response as " + responseType.getSimpleName(),
          header(response, REQUEST_ID_HEADER),
          e);
    }
  }

  /** Functional shape of a byte-body I/O call that may throw I/O and interrupts. */
  @FunctionalInterface
  private interface ByteCall {
    HttpResponse<byte[]> send() throws IOException, InterruptedException;
  }

  /** Functional shape of a stream-body I/O call. */
  @FunctionalInterface
  private interface StreamCall {
    HttpResponse<InputStream> send() throws IOException, InterruptedException;
  }

  private static HttpResponse<byte[]> sendOnce(ByteCall call, String label) {
    try {
      return call.send();
    } catch (HttpTimeoutException e) {
      throw new PoliPageNetworkException(
          PoliPageErrorCode.TIMEOUT, label + " timed out: " + e.getMessage(), e);
    } catch (IOException e) {
      throw new PoliPageNetworkException(
          PoliPageErrorCode.NETWORK_ERROR, label + " failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PoliPageException(
          PoliPageErrorCode.ABORTED, 0, label + " was interrupted", null, e);
    }
  }

  private static HttpResponse<InputStream> sendOnceStream(StreamCall call, String label) {
    try {
      return call.send();
    } catch (HttpTimeoutException e) {
      throw new PoliPageNetworkException(
          PoliPageErrorCode.TIMEOUT, label + " timed out: " + e.getMessage(), e);
    } catch (IOException e) {
      throw new PoliPageNetworkException(
          PoliPageErrorCode.NETWORK_ERROR, label + " failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PoliPageException(
          PoliPageErrorCode.ABORTED, 0, label + " was interrupted", null, e);
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

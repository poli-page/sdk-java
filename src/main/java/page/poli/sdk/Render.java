package page.poli.sdk;

import java.io.InputStream;
import java.net.http.HttpResponse;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.input.RenderInput;
import page.poli.sdk.model.DocumentDescriptor;

/**
 * Blocking render facade. Obtain via {@link PoliPageClient#render()}.
 *
 * <ul>
 *   <li>{@link #pdf(RenderInput)} — render and return bytes (two HTTP hops)
 *   <li>{@link #pdfStream(RenderInput)} — same but return an {@link InputStream} so large PDFs
 *       don't have to fit in memory; <strong>caller must close the stream</strong>
 *   <li>{@link #document(RenderInput)} — render, store, return the descriptor (skip download)
 * </ul>
 *
 * <p>The {@code /v1/render} POST is retried per the client's policy; the presigned-URL download is
 * not. Interrupting the calling thread ends the call with {@code aborted}, during an attempt, a
 * retry wait or the download.
 */
public final class Render {

  private static final String RENDER_PATH = "/v1/render";

  private final Calls calls;

  Render(Calls calls) {
    this.calls = calls;
  }

  /**
   * Render the given input as a PDF and return the raw bytes.
   *
   * <p>Convenience over {@link #document(RenderInput)} + the presigned download. For large PDFs,
   * prefer {@link #pdfStream(RenderInput)} to avoid buffering the whole payload.
   *
   * @param input the render input — non-null
   * @return the PDF bytes
   * @throws PoliPageException on any non-2xx response after retries, or transport failure; a failed
   *     download raises {@code DOWNLOAD_FAILED}
   */
  public byte[] pdf(RenderInput input) {
    return calls.download().bytes(document(input).presignedPdfUrl());
  }

  /**
   * Render the given input as a PDF and return a streaming body. Use this for large documents
   * (multi-MB) to avoid buffering the full byte array in memory.
   *
   * <p><strong>The returned {@link InputStream} owns the underlying HTTP connection.</strong> Wrap
   * the call in {@code try-with-resources} (or call {@code close()} explicitly) — leaking the
   * stream leaks a slot from the JDK's HTTP connection pool. A read that fails because the
   * connection dropped throws {@code DOWNLOAD_FAILED} (an unchecked {@link PoliPageException}).
   *
   * <pre>{@code
   * try (InputStream pdf = client.render().pdfStream(input)) {
   *     Files.copy(pdf, Path.of("invoice.pdf"));
   * }
   * }</pre>
   *
   * @param input the render input — non-null
   * @return an {@link InputStream} reading the PDF body — caller must close
   * @throws PoliPageException on any non-2xx response after retries, or transport failure; a failed
   *     download raises {@code DOWNLOAD_FAILED}
   */
  public InputStream pdfStream(RenderInput input) {
    return calls.download().stream(document(input).presignedPdfUrl());
  }

  /**
   * Render the given input and return the stored document's descriptor without downloading the PDF.
   * Useful when the caller wants to persist {@code documentId} and download on demand later (e.g.
   * via a signed URL handed out from another service).
   *
   * @param input the render input — non-null
   * @return the descriptor
   * @throws PoliPageException on any non-2xx response after retries, or transport failure
   */
  public DocumentDescriptor document(RenderInput input) {
    HttpResponse<byte[]> response = calls.post(RENDER_PATH, input, input.idempotencyKey());
    return calls.json(response, RENDER_PATH, DocumentDescriptor.class);
  }
}

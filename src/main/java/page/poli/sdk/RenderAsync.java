package page.poli.sdk;

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import page.poli.sdk.input.RenderInput;
import page.poli.sdk.model.DocumentDescriptor;

/**
 * Asynchronous counterpart of {@link Render}. Obtain via {@link PoliPageClient#renderAsync()}.
 *
 * <p>Same methods, same wire contract, same retry policy as {@link Render} — only the return type
 * changes ({@code T} → {@code CompletableFuture<T>}). Exceptions inside the chain are wrapped in
 * {@link CompletionException} by the JDK; unwrap with {@code ex.getCause()} or use {@code
 * .handle((res, err) -> { … })}.
 *
 * <p>Internally uses {@code HttpClient.sendAsync(...)} so no worker thread is blocked while waiting
 * on the network, nor between attempts.
 *
 * <p>Cancelling the returned future ({@code cancel(true)}) aborts the call: the attempt in flight,
 * the retry wait or the download is cancelled, and no further request is sent. {@code get()} and
 * {@code join()} then throw a {@code CancellationException} whose {@code getCause()} is the SDK's
 * {@code aborted} {@link page.poli.sdk.exception.PoliPageException} ({@code Request was aborted}),
 * and {@code onError} fires once with it.
 */
public final class RenderAsync {

  private static final String RENDER_PATH = "/v1/render";

  private final Calls calls;

  RenderAsync(Calls calls) {
    this.calls = calls;
  }

  /**
   * Async variant of {@link Render#pdf(RenderInput)} — returns the PDF bytes as a future.
   *
   * @param input the render input — non-null
   * @return a future of PDF bytes
   */
  public CompletableFuture<byte[]> pdf(RenderInput input) {
    return calls
        .futures()
        .compose(document(input), d -> calls.download().bytesAsync(d.presignedPdfUrl()));
  }

  /**
   * Async variant of {@link Render#pdfStream(RenderInput)} — returns an {@link InputStream} as a
   * future. Same close-the-stream contract: wrap in {@code try-with-resources} or leak a connection
   * slot.
   *
   * @param input the render input — non-null
   * @return a future of the streaming body
   */
  public CompletableFuture<InputStream> pdfStream(RenderInput input) {
    return calls
        .futures()
        .compose(document(input), d -> calls.download().streamAsync(d.presignedPdfUrl()));
  }

  /**
   * Async variant of {@link Render#document(RenderInput)}.
   *
   * @param input the render input — non-null
   * @return a future of the parsed descriptor
   */
  public CompletableFuture<DocumentDescriptor> document(RenderInput input) {
    return calls
        .futures()
        .map(
            calls.postAsync(RENDER_PATH, input, input.idempotencyKey()),
            response -> calls.json(response, RENDER_PATH, DocumentDescriptor.class));
  }
}

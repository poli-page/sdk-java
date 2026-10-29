package page.poli.sdk.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Request body that lets {@code java.net.http} put its request on the wire once, so that every
 * resend is the SDK's own retry: counted by {@code maxRetries}, reported to {@code onRequest} and
 * {@code onRetry}, and spaced by the backoff.
 *
 * <p>The JDK client resends a request by itself when the connection closes before any byte of the
 * answer, on a pooled connection as on a fresh one: a {@code GET} always (once up to JDK 21, up to
 * {@code jdk.httpclient.redirects.retrylimit} times, 5 by default, in later JDKs), any method when
 * the JVM runs with {@code -Djdk.httpclient.enableAllMethodRetry=true}. There is no per-client
 * switch. A {@code POST /v1/render} resent that way could store a second document behind the SDK's
 * back, and a resent download would break the rule that the presigned-URL download is never
 * retried.
 *
 * <p>Each exchange the JDK opens for a request asks the body for its length once, when it writes
 * the request headers ({@code Http1Request}, {@code Stream} for HTTP/2, {@code Http3ExchangeImpl}):
 * a second such call is a resend, refused before anything is written. The refusal fails the attempt
 * with an {@link IOException}, which the SDK maps to {@code network_error} and retries itself.
 * Calls from outside the JDK's HTTP implementation are not counted, so the guard can only fall back
 * to the JDK's own behaviour, never refuse a first send.
 *
 * <p>A {@code GET} or {@code DELETE} gets an empty body with this guard. Up to JDK 21 that sends a
 * {@code Content-Length: 0} header, as {@code HttpRequest.Builder.GET()} itself did up to JDK 18.
 */
final class SingleExchangeBody implements BodyPublisher {

  /** Package of the JDK's HTTP client implementation, whose exchanges ask for the length. */
  private static final String JDK_HTTP_IMPLEMENTATION = "jdk.internal.net.http.";

  private static final StackWalker STACK = StackWalker.getInstance();

  private final BodyPublisher body;
  private final AtomicInteger exchanges = new AtomicInteger();

  private SingleExchangeBody(BodyPublisher body) {
    this.body = body;
  }

  /**
   * @param payload the request body
   * @return a publisher of {@code payload} for one exchange
   */
  static HttpRequest.BodyPublisher of(byte[] payload) {
    return new SingleExchangeBody(BodyPublishers.ofByteArray(payload));
  }

  /**
   * @return an empty body for one exchange, for a {@code GET} or a {@code DELETE}
   */
  static HttpRequest.BodyPublisher empty() {
    return new SingleExchangeBody(BodyPublishers.noBody());
  }

  @Override
  public long contentLength() {
    if (askedByAnExchange() && exchanges.getAndIncrement() > 0) {
      throw new ResendRefusedException();
    }
    return body.contentLength();
  }

  @Override
  public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
    body.subscribe(subscriber);
  }

  private static boolean askedByAnExchange() {
    // Frame 0 is this method, frame 1 contentLength(), frame 2 its caller.
    return STACK.walk(
        frames ->
            frames
                .skip(2)
                .findFirst()
                .map(frame -> frame.getClassName().startsWith(JDK_HTTP_IMPLEMENTATION))
                .orElse(false));
  }

  /** The JDK client tried to send a request a second time; the attempt fails instead. */
  static final class ResendRefusedException extends UncheckedIOException {

    ResendRefusedException() {
      super(
          new IOException(
              "the connection closed before any answer, and the HTTP client tried to send the"
                  + " request again: only the SDK retries a request"));
    }
  }
}

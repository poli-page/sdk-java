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
 * <p>Each exchange the JDK opens for a request asks the body for its length once, before it writes
 * any byte: {@code Http1Request.headers()}, {@code Stream.sendHeadersAsync()} for HTTP/2, {@code
 * Http3ExchangeImpl} (JDK 21 and 26 sources). Every JDK retry opens a new exchange and asks again:
 * the HTTP/1.1 dead-connection resend, the HTTP/2 retry of a stream the server refused (GOAWAY,
 * {@code REFUSED_STREAM}), and the retries of {@code jdk.httpclient.redirects.retrylimit}. Nothing
 * else calls this method, so a second call is a resend, refused before anything is written. The
 * refusal fails the attempt with an {@link IOException}, which the SDK maps to {@code
 * network_error} and retries itself. On HTTP/1.1 the JDK closes the connection when the headers
 * cannot be built, so a refused resend leaks nothing.
 *
 * <p>A {@code GET} or {@code DELETE} gets an empty body with this guard. Up to JDK 21 that sends a
 * {@code Content-Length: 0} header, as {@code HttpRequest.Builder.GET()} itself did up to JDK 18.
 */
final class SingleExchangeBody implements BodyPublisher {

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
    if (exchanges.getAndIncrement() > 0) {
      throw new ResendRefusedException();
    }
    return body.contentLength();
  }

  @Override
  public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
    body.subscribe(subscriber);
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

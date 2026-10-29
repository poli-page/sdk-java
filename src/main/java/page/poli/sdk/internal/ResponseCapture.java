package page.poli.sdk.internal;

import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import org.jspecify.annotations.Nullable;

/**
 * Body handler that remembers the status and headers of the answer before its body is read. When
 * the body then fails (a dropped connection, the attempt timeout), the SDK still knows which answer
 * it was reading: a non-2xx answer becomes a malformed error body with its real status and request
 * id (SDK spec §8.2), a 2xx answer a {@code network_error} or {@code timeout}.
 *
 * <p>One instance per HTTP exchange.
 *
 * @param <T> the body type
 */
public final class ResponseCapture<T> implements BodyHandler<T> {

  private final BodyHandler<T> body;
  private volatile @Nullable ResponseInfo info;

  /**
   * @param body the handler that reads the body
   */
  public ResponseCapture(BodyHandler<T> body) {
    this.body = body;
  }

  /**
   * @return a capture reading the body into a byte array
   */
  public static ResponseCapture<byte[]> ofByteArray() {
    return new ResponseCapture<>(HttpResponse.BodyHandlers.ofByteArray());
  }

  @Override
  public BodySubscriber<T> apply(ResponseInfo responseInfo) {
    this.info = responseInfo;
    return body.apply(responseInfo);
  }

  /**
   * @return the status and headers of the answer, or {@code null} when none arrived
   */
  public @Nullable ResponseInfo info() {
    return info;
  }
}

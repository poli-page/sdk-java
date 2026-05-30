package page.poli.sdk.exception;

import org.jspecify.annotations.Nullable;

/**
 * Transport-level failure: DNS resolution, connection refused, TLS handshake, mid-stream socket
 * error, per-request timeout. No HTTP status is available; {@link #statusCode()} returns {@code 0}.
 *
 * <p>The {@link #code()} value is either {@code "network_error"} (general transport failure) or
 * {@code "timeout"} (the per-request deadline elapsed). Both are retryable.
 */
public final class PoliPageNetworkException extends PoliPageException {

  /**
   * @param code one of {@code "network_error"} or {@code "timeout"}
   * @param message human-readable message
   * @param cause the underlying transport exception (typically {@link java.io.IOException} or
   *     {@link java.net.http.HttpTimeoutException}), or {@code null}
   */
  public PoliPageNetworkException(String code, String message, @Nullable Throwable cause) {
    super(code, 0, message, null, cause);
  }
}

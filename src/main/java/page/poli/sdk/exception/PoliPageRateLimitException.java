package page.poli.sdk.exception;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Rate limit or quota exceeded. HTTP {@code 429}. Codes: {@code RATE_LIMIT_EXCEEDED} (over 120
 * render requests per 60 seconds per API key), {@code QUOTA_EXCEEDED} (the Free tier's monthly
 * render quota is reached) and {@code OVERAGE_CAP_EXCEEDED} (a paid tier's monthly overage cap is
 * reached).
 *
 * <p>The SDK has already retried internally up to {@code maxRetries} before surfacing this; back
 * off further at the caller level if you see it. The optional {@link #retryAfter()} reports the
 * server's {@code Retry-After} header verbatim (capped at 30 seconds for retries inside the SDK).
 */
public final class PoliPageRateLimitException extends PoliPageException {

  private final @Nullable Duration retryAfter;

  /**
   * @param code the wire code
   * @param statusCode {@code 429}
   * @param message human-readable message
   * @param requestId the {@code x-request-id} response header, or {@code null}
   * @param retryAfter parsed value of the {@code Retry-After} header, or {@code null} if absent /
   *     unparseable
   */
  public PoliPageRateLimitException(
      String code,
      int statusCode,
      String message,
      @Nullable String requestId,
      @Nullable Duration retryAfter) {
    super(code, statusCode, message, requestId, null);
    this.retryAfter = retryAfter;
  }

  /**
   * @return the server's suggested back-off, or {@code null} if no {@code Retry-After} header was
   *     present / parseable
   */
  public @Nullable Duration retryAfter() {
    return retryAfter;
  }
}

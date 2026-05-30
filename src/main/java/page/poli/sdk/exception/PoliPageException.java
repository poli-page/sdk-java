package page.poli.sdk.exception;

import org.jspecify.annotations.Nullable;
import page.poli.sdk.PoliPageErrorCode;

/**
 * Base of every exception raised by the Poli Page SDK. {@code RuntimeException} because the SDK
 * follows the modern-JVM-client convention of unchecked exceptions (Stripe Java, AWS SDK v2,
 * Google Cloud client libraries all do the same).
 *
 * <p>The hierarchy is {@code sealed}: callers can pattern-match exhaustively against the eight
 * final subclasses plus this base for the unmapped tail (typically 5xx that don't fall into any
 * specific bucket).
 *
 * <pre>{@code
 * try {
 *     byte[] pdf = client.render().pdf(input);
 * } catch (PoliPageRateLimitException rl) {
 *     queueForLater(rl.retryAfter());
 * } catch (PoliPageAuthException auth) {
 *     refreshCredentials();
 * } catch (PoliPageException other) {
 *     logger.error("poli failure: code={} status={} requestId={}",
 *         other.code(), other.statusCode(), other.requestId(), other);
 *     throw other;
 * }
 * }</pre>
 *
 * <p>The wire codes are documented in {@link PoliPageErrorCode}.
 *
 * <p>The {@code statusCode} field uses {@code 0} as a sentinel for "no HTTP status available"
 * (network error, timeout, caller-aborted) — {@code 0} is not a valid HTTP status, so this never
 * collides with a real value.
 */
public sealed class PoliPageException extends RuntimeException
    permits PoliPageAuthException,
        PoliPageDownloadException,
        PoliPageGoneException,
        PoliPageNetworkException,
        PoliPageNotFoundException,
        PoliPagePaymentRequiredException,
        PoliPageRateLimitException,
        PoliPageValidationException {

  private final String code;
  private final int statusCode;
  private final @Nullable String requestId;

  /**
   * Construct a new exception.
   *
   * @param code the wire code (e.g. {@code "INVALID_API_KEY"}) or SDK-internal code (e.g.
   *     {@code "network_error"})
   * @param statusCode HTTP status code, or {@code 0} for non-HTTP failures
   * @param message human-readable message — typically the {@code message} field from the wire
   *     response, or a built-in fallback
   * @param requestId the {@code x-request-id} response header, or {@code null} if absent
   * @param cause the underlying {@link Throwable}, or {@code null}
   */
  public PoliPageException(
      String code,
      int statusCode,
      String message,
      @Nullable String requestId,
      @Nullable Throwable cause) {
    super(message, cause);
    this.code = code;
    this.statusCode = statusCode;
    this.requestId = requestId;
  }

  /**
   * @return the wire / SDK-internal code identifying this error precisely
   */
  public final String code() {
    return code;
  }

  /**
   * @return the HTTP status code, or {@code 0} for non-HTTP failures
   */
  public final int statusCode() {
    return statusCode;
  }

  /**
   * @return the upstream {@code x-request-id} header, or {@code null} when absent / non-HTTP
   */
  public final @Nullable String requestId() {
    return requestId;
  }

  /**
   * Whether the SDK considers this error retryable (5xx, 429, network failure, timeout). The SDK
   * has already retried internally up to {@code maxRetries}; this predicate is mainly useful when
   * an outer queue / scheduler decides whether to re-enqueue.
   *
   * @return {@code true} if the failure class is generally retryable
   */
  public boolean isRetryable() {
    if (this instanceof PoliPageNetworkException) {
      return true;
    }
    if (statusCode >= 500) {
      return true;
    }
    return statusCode == 429;
  }
}

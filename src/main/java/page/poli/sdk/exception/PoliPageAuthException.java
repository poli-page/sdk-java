package page.poli.sdk.exception;

import org.jspecify.annotations.Nullable;

/**
 * Authentication / authorization failure. Covers HTTP {@code 401} ({@code MISSING_API_KEY}, {@code
 * INVALID_API_KEY}) and HTTP {@code 403} ({@code FORBIDDEN}, {@code ENVIRONMENT_PROTECTED}, {@code
 * THUMBNAILS_NOT_AVAILABLE}, and the read-only organization states {@code
 * ORGANIZATION_PAYMENT_GRACE} and {@code ORGANIZATION_CANCELLED}). A purged organization is HTTP
 * {@code 410}, raised as {@link PoliPageGoneException}.
 */
public final class PoliPageAuthException extends PoliPageException {

  private static final long serialVersionUID = 1L;

  /**
   * @param code the wire code
   * @param statusCode {@code 401} or {@code 403}
   * @param message human-readable message
   * @param requestId the {@code x-request-id} response header, or {@code null}
   */
  public PoliPageAuthException(
      String code, int statusCode, String message, @Nullable String requestId) {
    super(code, statusCode, message, requestId, null);
  }

  /**
   * @param code the wire code
   * @param statusCode the HTTP status
   * @param message human-readable message
   * @param requestId the {@code x-request-id} response header, or {@code null}
   * @param cause the underlying failure, e.g. the error while the answer's body was read, or {@code
   *     null}
   */
  public PoliPageAuthException(
      String code,
      int statusCode,
      String message,
      @Nullable String requestId,
      @Nullable Throwable cause) {
    super(code, statusCode, message, requestId, cause);
  }
}

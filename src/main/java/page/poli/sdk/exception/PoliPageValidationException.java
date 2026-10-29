package page.poli.sdk.exception;

import org.jspecify.annotations.Nullable;

/**
 * The request payload failed validation. HTTP {@code 400} or {@code 422}. Covers codes like {@code
 * VALIDATION_ERROR}, {@code MISSING_DATA}, {@code MISSING_TEMPLATE_SLUG}, {@code
 * PROJECT_REQUIRED_FOR_DOCUMENT}, {@code ENVIRONMENT_MISMATCH}, {@code EMPTY_DOCUMENT}, {@code
 * TEMPLATE_ERROR}.
 *
 * <p>Not retryable — fix the input and try again.
 */
public final class PoliPageValidationException extends PoliPageException {

  /**
   * @param code the wire code
   * @param statusCode {@code 400} or {@code 422}
   * @param message human-readable message
   * @param requestId the {@code x-request-id} response header, or {@code null}
   */
  public PoliPageValidationException(
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
  public PoliPageValidationException(
      String code,
      int statusCode,
      String message,
      @Nullable String requestId,
      @Nullable Throwable cause) {
    super(code, statusCode, message, requestId, cause);
  }
}

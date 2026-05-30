package page.poli.sdk.exception;

import org.jspecify.annotations.Nullable;

/**
 * The request payload failed validation. HTTP {@code 400} or {@code 422}. Covers codes like {@code
 * VALIDATION_ERROR}, {@code MISSING_DATA}, {@code MISSING_PROJECT_OR_TEMPLATE}, {@code
 * MISSING_TEMPLATE_SLUG}, {@code INVALID_VERSION_FORMAT}, {@code VERSION_REQUIRED}, {@code
 * INVALID_VERSION_FOR_KEY_ENV}.
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
}

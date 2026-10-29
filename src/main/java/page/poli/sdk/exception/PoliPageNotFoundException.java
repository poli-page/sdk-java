package page.poli.sdk.exception;

import org.jspecify.annotations.Nullable;

/**
 * The requested resource does not exist. HTTP {@code 404}. Covers codes such as {@code NOT_FOUND},
 * {@code PROJECT_NOT_FOUND}, {@code PROJECT_NOT_IN_ENVIRONMENT}, {@code TEMPLATE_NOT_FOUND}, and
 * {@code DOCUMENT_NOT_FOUND}.
 */
public final class PoliPageNotFoundException extends PoliPageException {

  /**
   * @param code the wire code
   * @param statusCode {@code 404}
   * @param message human-readable message
   * @param requestId the {@code x-request-id} response header, or {@code null}
   */
  public PoliPageNotFoundException(
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
  public PoliPageNotFoundException(
      String code,
      int statusCode,
      String message,
      @Nullable String requestId,
      @Nullable Throwable cause) {
    super(code, statusCode, message, requestId, cause);
  }
}

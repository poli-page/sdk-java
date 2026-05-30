package page.poli.sdk.exception;

import org.jspecify.annotations.Nullable;

/**
 * The requested resource does not exist. HTTP {@code 404}. Covers codes {@code NOT_FOUND}, {@code
 * VERSION_NOT_FOUND}, and {@code DOCUMENT_NOT_FOUND}.
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
}

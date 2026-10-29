package page.poli.sdk.exception;

import org.jspecify.annotations.Nullable;

/**
 * The requested resource existed once but is permanently unavailable. HTTP {@code 410}. Codes:
 * {@code DOCUMENT_GONE} (the document was deleted, or is a {@code sandbox} document older than 30
 * days) and {@code ORGANIZATION_PURGED} (the organization is purged: every request is refused).
 */
public final class PoliPageGoneException extends PoliPageException {

  private static final long serialVersionUID = 1L;

  /**
   * @param code the wire code
   * @param statusCode {@code 410}
   * @param message human-readable message
   * @param requestId the {@code x-request-id} response header, or {@code null}
   */
  public PoliPageGoneException(
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
  public PoliPageGoneException(
      String code,
      int statusCode,
      String message,
      @Nullable String requestId,
      @Nullable Throwable cause) {
    super(code, statusCode, message, requestId, cause);
  }
}

package page.poli.sdk.exception;

import org.jspecify.annotations.Nullable;

/**
 * The requested resource existed once but is permanently unavailable. HTTP {@code 410}. Typically a
 * soft-deleted document ({@code GONE}) or an organization that was purged ({@code
 * ORGANIZATION_PURGED}).
 */
public final class PoliPageGoneException extends PoliPageException {

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
}

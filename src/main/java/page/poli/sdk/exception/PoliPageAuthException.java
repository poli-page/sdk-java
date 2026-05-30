package page.poli.sdk.exception;

import org.jspecify.annotations.Nullable;

/**
 * Authentication / authorization failure. Covers HTTP {@code 401} (unauthorized — missing or
 * invalid API key) and HTTP {@code 403} (forbidden — key valid but rejected, e.g. {@code
 * ORGANIZATION_CANCELLED} or {@code ORGANIZATION_PURGED}).
 */
public final class PoliPageAuthException extends PoliPageException {

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
}

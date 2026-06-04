package page.poli.sdk.exception;

import page.poli.sdk.PoliPageErrorCode;

/**
 * Builder-level validation failure raised by {@link page.poli.sdk.PoliPageClient.Builder#build()}.
 * Always has {@code code = "invalid_options"} and {@code statusCode = 0} — never goes on the wire.
 * Mirrors sdk-node's {@code new PoliPageError('apiKey is required', 'invalid_options')} thrown from
 * its constructor.
 */
public final class PoliPageInvalidOptionsException extends PoliPageException {

  /**
   * @param message human-readable explanation of which option was missing or invalid
   */
  public PoliPageInvalidOptionsException(String message) {
    super(PoliPageErrorCode.INVALID_OPTIONS, 0, message, null, null);
  }
}

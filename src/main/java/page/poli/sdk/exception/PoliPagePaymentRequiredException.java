package page.poli.sdk.exception;

import org.jspecify.annotations.Nullable;

/**
 * The organization has unpaid invoices and rendering is suspended. HTTP {@code 402}, code
 * {@code PAYMENT_REQUIRED}. Resolve in the billing portal — there is nothing the SDK can do.
 */
public final class PoliPagePaymentRequiredException extends PoliPageException {

  /**
   * @param code the wire code
   * @param statusCode {@code 402}
   * @param message human-readable message
   * @param requestId the {@code x-request-id} response header, or {@code null}
   */
  public PoliPagePaymentRequiredException(
      String code, int statusCode, String message, @Nullable String requestId) {
    super(code, statusCode, message, requestId, null);
  }
}

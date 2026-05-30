package page.poli.sdk;

/**
 * String constants for every error {@code code} the Poli Page API may return, plus the SDK's own
 * transport / lifecycle codes. Use them for fine-grained branching when the {@link
 * page.poli.sdk.exception.PoliPageException} subclass alone isn't precise enough:
 *
 * <pre>{@code
 * switch (ex.code()) {
 *     case PoliPageErrorCode.QUOTA_EXCEEDED    -> waitForReset();
 *     case PoliPageErrorCode.OVERAGE_CAP_EXCEEDED -> escalateToOps();
 *     default                                  -> rethrow(ex);
 * }
 * }</pre>
 *
 * <p>The constant <strong>values</strong> are kept verbatim with the wire / Node SDK forms — that's
 * why API codes are {@code SCREAMING_SNAKE_CASE} but the SDK-internal codes are lowercase ({@code
 * "network_error"}, not {@code "NETWORK_ERROR"}). Constant <strong>names</strong> follow Java's
 * idiomatic uppercase. Comparing {@code ex.code()} against these constants therefore matches by
 * value, not by name.
 */
public final class PoliPageErrorCode {

  // -- Auth -------------------------------------------------------------

  /** API key is missing from the request. HTTP 401. */
  public static final String MISSING_API_KEY = "MISSING_API_KEY";

  /** API key is present but invalid or revoked. HTTP 401. */
  public static final String INVALID_API_KEY = "INVALID_API_KEY";

  /** Caller is authenticated but not authorized for this resource. HTTP 403. */
  public static final String FORBIDDEN = "FORBIDDEN";

  // -- Billing / org lifecycle -----------------------------------------

  /** Subscription has unpaid invoices and rendering is suspended. HTTP 402. */
  public static final String PAYMENT_REQUIRED = "PAYMENT_REQUIRED";

  /** Organization has been cancelled. HTTP 403. */
  public static final String ORGANIZATION_CANCELLED = "ORGANIZATION_CANCELLED";

  /** Organization data has been purged after cancellation. HTTP 410. */
  public static final String ORGANIZATION_PURGED = "ORGANIZATION_PURGED";

  // -- Not found / gone -------------------------------------------------

  /** Generic 404. */
  public static final String NOT_FOUND = "NOT_FOUND";

  /** Specific 404: requested template version does not exist. */
  public static final String VERSION_NOT_FOUND = "VERSION_NOT_FOUND";

  /** Specific 404: requested document does not exist. */
  public static final String DOCUMENT_NOT_FOUND = "DOCUMENT_NOT_FOUND";

  /** Resource existed but was deleted. HTTP 410. */
  public static final String GONE = "GONE";

  // -- Validation -------------------------------------------------------

  /** Generic 400 validation error. */
  public static final String VALIDATION_ERROR = "VALIDATION_ERROR";

  /** The {@code data} field is missing from the render payload. HTTP 400. */
  public static final String MISSING_DATA = "MISSING_DATA";

  /** Neither {@code project} nor {@code template} was provided. HTTP 400. */
  public static final String MISSING_PROJECT_OR_TEMPLATE = "MISSING_PROJECT_OR_TEMPLATE";

  /** Inline-mode render is missing the inline template slug. HTTP 400. */
  public static final String MISSING_TEMPLATE_SLUG = "MISSING_TEMPLATE_SLUG";

  /** {@code version} is not valid semver. HTTP 400. */
  public static final String INVALID_VERSION_FORMAT = "INVALID_VERSION_FORMAT";

  /** Live keys require an explicit {@code version}; the draft is not addressable. HTTP 400. */
  public static final String VERSION_REQUIRED = "VERSION_REQUIRED";

  /** The supplied {@code version} doesn't exist for this key's environment. HTTP 400. */
  public static final String INVALID_VERSION_FOR_KEY_ENV = "INVALID_VERSION_FOR_KEY_ENV";

  // -- Rate / quota -----------------------------------------------------

  /** Monthly quota exceeded. HTTP 429. */
  public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";

  /** Paid overage cap exceeded. HTTP 429. */
  public static final String OVERAGE_CAP_EXCEEDED = "OVERAGE_CAP_EXCEEDED";

  // -- Server -----------------------------------------------------------

  /** Server-side failure. HTTP 5xx. */
  public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  // -- SDK-internal -----------------------------------------------------

  /** Transport-level failure (DNS, refused, TLS, mid-stream socket). No HTTP status. */
  public static final String NETWORK_ERROR = "network_error";

  /** Per-request deadline elapsed. No HTTP status. */
  public static final String TIMEOUT = "timeout";

  /** Caller-aborted (thread interruption or {@code CompletableFuture.cancel(true)}). */
  public static final String ABORTED = "aborted";

  /** Builder-level validation failure (caught locally — never goes on the wire). */
  public static final String INVALID_OPTIONS = "invalid_options";

  /** Presigned PDF URL fetch failed. */
  public static final String DOWNLOAD_FAILED = "download_failed";

  private PoliPageErrorCode() {}
}

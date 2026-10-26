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
 * <p>The API's set of codes is open: {@code ex.code()} is whatever string the API returns,
 * including codes not listed here, so keep a {@code default} branch.
 *
 * <p>The constant <strong>values</strong> are kept verbatim with the wire / Node SDK forms — that's
 * why API codes are {@code SCREAMING_SNAKE_CASE} but the SDK-internal codes are lowercase ({@code
 * "network_error"}, not {@code "NETWORK_ERROR"}). Constant <strong>names</strong> follow Java's
 * idiomatic uppercase. Comparing {@code ex.code()} against these constants therefore matches by
 * value, not by name.
 */
public final class PoliPageErrorCode {

  // -- Auth / access ---------------------------------------------------

  /** API key is missing from the request. HTTP 401. */
  public static final String MISSING_API_KEY = "MISSING_API_KEY";

  /** API key is present but invalid or revoked. HTTP 401. */
  public static final String INVALID_API_KEY = "INVALID_API_KEY";

  /** Caller is authenticated but not authorized for this resource. HTTP 403. */
  public static final String FORBIDDEN = "FORBIDDEN";

  /** The target environment is protected. HTTP 403. */
  public static final String ENVIRONMENT_PROTECTED = "ENVIRONMENT_PROTECTED";

  /** Thumbnails are not available for this organization. HTTP 403. */
  public static final String THUMBNAILS_NOT_AVAILABLE = "THUMBNAILS_NOT_AVAILABLE";

  // -- Billing / org lifecycle -----------------------------------------

  /** Organization is in its payment grace period. HTTP 403. */
  public static final String ORGANIZATION_PAYMENT_GRACE = "ORGANIZATION_PAYMENT_GRACE";

  /** Organization has been cancelled. HTTP 403. */
  public static final String ORGANIZATION_CANCELLED = "ORGANIZATION_CANCELLED";

  /** Organization data has been purged after cancellation. HTTP 410. */
  public static final String ORGANIZATION_PURGED = "ORGANIZATION_PURGED";

  /** Organization is being migrated. HTTP 503. */
  public static final String ORGANIZATION_MIGRATING = "ORGANIZATION_MIGRATING";

  // -- Not found / gone -------------------------------------------------

  /** Generic 404. */
  public static final String NOT_FOUND = "NOT_FOUND";

  /** Specific 404: the project does not exist. */
  public static final String PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND";

  /** Specific 404: the project has no copy in the key's environment. */
  public static final String PROJECT_NOT_IN_ENVIRONMENT = "PROJECT_NOT_IN_ENVIRONMENT";

  /** Specific 404: the template does not exist in the project. */
  public static final String TEMPLATE_NOT_FOUND = "TEMPLATE_NOT_FOUND";

  /** Specific 404: requested document does not exist. */
  public static final String DOCUMENT_NOT_FOUND = "DOCUMENT_NOT_FOUND";

  /** The document existed but was deleted. HTTP 410. */
  public static final String DOCUMENT_GONE = "DOCUMENT_GONE";

  // -- Validation -------------------------------------------------------

  /** Generic 400 validation error. */
  public static final String VALIDATION_ERROR = "VALIDATION_ERROR";

  /** The {@code data} field is missing from the render payload. HTTP 400. */
  public static final String MISSING_DATA = "MISSING_DATA";

  /** The template slug is missing from the render payload. HTTP 400. */
  public static final String MISSING_TEMPLATE_SLUG = "MISSING_TEMPLATE_SLUG";

  /** A document render needs a {@code project}. HTTP 400. */
  public static final String PROJECT_REQUIRED_FOR_DOCUMENT = "PROJECT_REQUIRED_FOR_DOCUMENT";

  /** The request does not match the key's environment. HTTP 400. */
  public static final String ENVIRONMENT_MISMATCH = "ENVIRONMENT_MISMATCH";

  /** The rendered document is empty. HTTP 400. */
  public static final String EMPTY_DOCUMENT = "EMPTY_DOCUMENT";

  /** The template could not be rendered. HTTP 400. */
  public static final String TEMPLATE_ERROR = "TEMPLATE_ERROR";

  /** The render request body is too large. HTTP 413. */
  public static final String RENDER_BODY_TOO_LARGE = "RENDER_BODY_TOO_LARGE";

  /** The rendered document is too large. HTTP 413. */
  public static final String DOCUMENT_TOO_LARGE = "DOCUMENT_TOO_LARGE";

  // -- Rate / quota -----------------------------------------------------

  /** Monthly quota exceeded. HTTP 429. */
  public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";

  /** Paid overage cap exceeded. HTTP 429. */
  public static final String OVERAGE_CAP_EXCEEDED = "OVERAGE_CAP_EXCEEDED";

  /** Request rate limit exceeded. HTTP 429. */
  public static final String RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";

  // -- Server -----------------------------------------------------------

  /** Server-side failure. HTTP 5xx. Also the code the SDK uses when an error body is not JSON. */
  public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  /**
   * A publish locks the project in the key's environment. HTTP 503 with {@code Retry-After};
   * retried like any 5xx.
   */
  public static final String PUBLISH_IN_PROGRESS = "PUBLISH_IN_PROGRESS";

  /** Storage access was denied. HTTP 502. */
  public static final String STORAGE_ACCESS_DENIED = "STORAGE_ACCESS_DENIED";

  /** The render did not finish in time. HTTP 504. */
  public static final String RENDER_TIMEOUT = "RENDER_TIMEOUT";

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

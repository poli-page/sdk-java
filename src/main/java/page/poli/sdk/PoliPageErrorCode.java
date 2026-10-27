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
 * <p>Each API code's Javadoc gives the HTTP status and meaning from the API's own definition (API
 * spec §4.2 error table and the API error classes).
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

  /** HTTP 401. The request has no {@code Authorization: Bearer pp_…} API key. */
  public static final String MISSING_API_KEY = "MISSING_API_KEY";

  /** HTTP 401. The API key is unknown, revoked, or bound to a deleted environment. */
  public static final String INVALID_API_KEY = "INVALID_API_KEY";

  /**
   * HTTP 403. The API key's organization or environment does not match the resource: a document of
   * another organization, or of another environment than the key's.
   */
  public static final String FORBIDDEN = "FORBIDDEN";

  /**
   * HTTP 403. The environment is protected: only an {@code owner} or {@code admin} caller may
   * publish into it or delete its documents.
   */
  public static final String ENVIRONMENT_PROTECTED = "ENVIRONMENT_PROTECTED";

  /** HTTP 403. Thumbnails are a paid-tier feature: the organization is on the Free tier. */
  public static final String THUMBNAILS_NOT_AVAILABLE = "THUMBNAILS_NOT_AVAILABLE";

  // -- Billing / org lifecycle -----------------------------------------

  /**
   * HTTP 403. The organization is in payment grace, which is read-only: a write (render,
   * thumbnails, delete) is refused, reads pass.
   */
  public static final String ORGANIZATION_PAYMENT_GRACE = "ORGANIZATION_PAYMENT_GRACE";

  /**
   * HTTP 403. The organization is cancelled, which is read-only until it is purged: a write
   * (render, thumbnails, delete) is refused, reads pass.
   */
  public static final String ORGANIZATION_CANCELLED = "ORGANIZATION_CANCELLED";

  /** HTTP 410. The organization is purged: every request is refused. */
  public static final String ORGANIZATION_PURGED = "ORGANIZATION_PURGED";

  /**
   * HTTP 503. A write while the organization's storage is being migrated (Free to paid) or
   * switched; refused until it ends.
   */
  public static final String ORGANIZATION_MIGRATING = "ORGANIZATION_MIGRATING";

  // -- Not found / gone -------------------------------------------------

  /**
   * HTTP 404. Generic resource not found. An unknown route answers a plain-text {@code 404} without
   * this code.
   */
  public static final String NOT_FOUND = "NOT_FOUND";

  /** HTTP 404. No project with this slug in the key's organization. */
  public static final String PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND";

  /**
   * HTTP 404. The project has no published copy in the key's environment: it was never published
   * there, or was removed from it.
   */
  public static final String PROJECT_NOT_IN_ENVIRONMENT = "PROJECT_NOT_IN_ENVIRONMENT";

  /** HTTP 404. The template is absent from the environment's published copy of the project. */
  public static final String TEMPLATE_NOT_FOUND = "TEMPLATE_NOT_FOUND";

  /**
   * HTTP 404. The document does not exist; for a preview or thumbnails, also a document with no
   * stored HTML.
   */
  public static final String DOCUMENT_NOT_FOUND = "DOCUMENT_NOT_FOUND";

  /** HTTP 410. The document was deleted, or is a {@code sandbox} document older than 30 days. */
  public static final String DOCUMENT_GONE = "DOCUMENT_GONE";

  // -- Validation -------------------------------------------------------

  /**
   * HTTP 400. The request failed validation, for example an unknown body field; the field errors
   * are in the body's {@code issues}.
   */
  public static final String VALIDATION_ERROR = "VALIDATION_ERROR";

  /** HTTP 400. The render body has no {@code data} object. */
  public static final String MISSING_DATA = "MISSING_DATA";

  /** HTTP 400. The render body names a project but no template slug. */
  public static final String MISSING_TEMPLATE_SLUG = "MISSING_TEMPLATE_SLUG";

  /** HTTP 400. The render body has no {@code project}. */
  public static final String PROJECT_REQUIRED_FOR_DOCUMENT = "PROJECT_REQUIRED_FOR_DOCUMENT";

  /** HTTP 400. The API key caller named another environment than its own. */
  public static final String ENVIRONMENT_MISMATCH = "ENVIRONMENT_MISMATCH";

  /** HTTP 400. The render produced zero pages: the body is empty, or all its content was pruned. */
  public static final String EMPTY_DOCUMENT = "EMPTY_DOCUMENT";

  /**
   * HTTP 400. The rendering engine rejected the template, including a partial or asset the rendered
   * copy does not hold.
   */
  public static final String TEMPLATE_ERROR = "TEMPLATE_ERROR";

  /** HTTP 413. The request body is over the whole-body cap, 2 MB on {@code /v1/render}. */
  public static final String RENDER_BODY_TOO_LARGE = "RENDER_BODY_TOO_LARGE";

  /**
   * HTTP 413. The document exceeds a rendering engine size cap: 200 pages, element count or nesting
   * depth.
   */
  public static final String DOCUMENT_TOO_LARGE = "DOCUMENT_TOO_LARGE";

  // -- Rate / quota -----------------------------------------------------

  /**
   * HTTP 429. The Free tier's monthly render quota is reached; Free has no overage. {@code
   * Retry-After} is the time left until the monthly reset.
   */
  public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";

  /**
   * HTTP 429. A paid tier's monthly overage cap is reached. {@code Retry-After} is the time left
   * until the monthly reset.
   */
  public static final String OVERAGE_CAP_EXCEEDED = "OVERAGE_CAP_EXCEEDED";

  /**
   * HTTP 429. Over a rate limit: on {@code /v1/render}, 120 requests per 60 seconds per API key.
   * Has {@code Retry-After}.
   */
  public static final String RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";

  // -- Server -----------------------------------------------------------

  /**
   * HTTP 500. Unexpected server error. The SDK also uses this code, with the real status, when an
   * error body is not JSON.
   */
  public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  /**
   * HTTP 503 with {@code Retry-After}: a publish still holds the project in this environment after
   * the render waited 10 seconds. Retried like any 5xx. (The API answers 409 on publish,
   * remove-project, project delete and transfer, which the SDK does not call.)
   */
  public static final String PUBLISH_IN_PROGRESS = "PUBLISH_IN_PROGRESS";

  /**
   * HTTP 502. The organization's bring-your-own (BYO) bucket refuses access: its role cannot be
   * assumed, or it refuses an operation.
   */
  public static final String STORAGE_ACCESS_DENIED = "STORAGE_ACCESS_DENIED";

  /** HTTP 504. The rendering engine's measurement time budget expired. */
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

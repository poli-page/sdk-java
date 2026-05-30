package page.poli.sdk.exception;

import org.jspecify.annotations.Nullable;

/**
 * The presigned PDF URL fetch failed. Common cause: the URL's short TTL (typically ~15 minutes)
 * elapsed before the SDK got to download it. Re-render to get a fresh URL.
 *
 * <p>{@link #statusCode()} carries the upstream S3 status (e.g. {@code 403} for an expired
 * signature), or {@code 0} for a transport-level failure with no HTTP response.
 */
public final class PoliPageDownloadException extends PoliPageException {

  /**
   * @param code the SDK-internal code; typically {@code "download_failed"}
   * @param statusCode the S3 status code, or {@code 0} for transport failures
   * @param message human-readable message
   * @param cause the underlying transport exception, or {@code null}
   */
  public PoliPageDownloadException(
      String code, int statusCode, String message, @Nullable Throwable cause) {
    super(code, statusCode, message, null, cause);
  }
}

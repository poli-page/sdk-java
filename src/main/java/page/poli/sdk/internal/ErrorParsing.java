package page.poli.sdk.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;
import page.poli.sdk.PoliPageErrorCode;
import page.poli.sdk.exception.PoliPageAuthException;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.exception.PoliPageGoneException;
import page.poli.sdk.exception.PoliPageNotFoundException;
import page.poli.sdk.exception.PoliPagePaymentRequiredException;
import page.poli.sdk.exception.PoliPageRateLimitException;
import page.poli.sdk.exception.PoliPageValidationException;

/**
 * Pure function: map an HTTP error response (status + body + selected headers) to a
 * {@link PoliPageException} subclass. No I/O, no global state — entirely testable from a unit
 * test with byte arrays.
 *
 * <p>Body parsing is lenient: an empty body, a non-JSON body, or a JSON object missing
 * {@code code} / {@code message} all degrade gracefully to status-derived defaults rather than
 * masking the original failure with a parse exception.
 */
public final class ErrorParsing {

  private ErrorParsing() {}

  /**
   * Map the upstream HTTP error to the matching SDK exception subclass.
   *
   * @param statusCode the HTTP status code (must be non-2xx — the caller decides)
   * @param body raw response body bytes; may be empty
   * @param requestId value of the {@code x-request-id} header, or {@code null}
   * @param retryAfterHeader value of the {@code Retry-After} header, or {@code null}
   * @param mapper the SDK's shared ObjectMapper, used to parse the JSON envelope
   * @return a concrete {@link PoliPageException} subclass for the status, or the base type for
   *     unmapped statuses
   */
  public static PoliPageException toException(
      int statusCode,
      byte[] body,
      @Nullable String requestId,
      @Nullable String retryAfterHeader,
      ObjectMapper mapper) {
    Parsed parsed = parseBody(body, mapper);
    String code = parsed.code != null ? parsed.code : defaultCodeForStatus(statusCode);
    String message =
        parsed.message != null ? parsed.message : ("HTTP " + statusCode);

    return switch (statusCode) {
      case 400, 422 -> new PoliPageValidationException(code, statusCode, message, requestId);
      case 401, 403 -> new PoliPageAuthException(code, statusCode, message, requestId);
      case 402 -> new PoliPagePaymentRequiredException(code, statusCode, message, requestId);
      case 404 -> new PoliPageNotFoundException(code, statusCode, message, requestId);
      case 410 -> new PoliPageGoneException(code, statusCode, message, requestId);
      case 429 -> new PoliPageRateLimitException(
          code, statusCode, message, requestId, parseRetryAfter(retryAfterHeader));
      default -> new PoliPageException(code, statusCode, message, requestId, null);
    };
  }

  /**
   * Parse the {@code Retry-After} header value. Accepts both forms defined by RFC 7231 §7.1.3:
   * delta-seconds (an integer) or HTTP-date.
   *
   * <p>Returns {@code null} when the header is absent, blank, or unparseable. Past HTTP-dates
   * collapse to {@link Duration#ZERO}. The retry loop is responsible for capping the result (the
   * SDK uses a 30-second cap); this method returns the raw parsed value so the exception's
   * {@code retryAfter()} accessor sees what the server said.
   *
   * @param headerValue raw header value, or {@code null}
   * @return the parsed duration, or {@code null} if unparseable / absent
   */
  public static @Nullable Duration parseRetryAfter(@Nullable String headerValue) {
    if (headerValue == null) {
      return null;
    }
    String trimmed = headerValue.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    // Form 1: delta-seconds.
    try {
      long seconds = Long.parseLong(trimmed);
      if (seconds < 0) {
        return Duration.ZERO;
      }
      return Duration.ofSeconds(seconds);
    } catch (NumberFormatException ignored) {
      // fall through to HTTP-date
    }
    // Form 2: HTTP-date (RFC 1123).
    try {
      ZonedDateTime when = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
      Duration delta = Duration.between(ZonedDateTime.now(ZoneOffset.UTC), when);
      return delta.isNegative() ? Duration.ZERO : delta;
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  private static Parsed parseBody(@Nullable byte[] body, ObjectMapper mapper) {
    if (body == null || body.length == 0) {
      return Parsed.EMPTY;
    }
    try {
      JsonNode root = mapper.readTree(body);
      if (root == null || !root.isObject()) {
        return Parsed.EMPTY;
      }
      return new Parsed(textOrNull(root, "code"), textOrNull(root, "message"));
    } catch (IOException ignored) {
      return Parsed.EMPTY;
    }
  }

  private static @Nullable String textOrNull(JsonNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || !node.isTextual()) {
      return null;
    }
    String value = node.asText();
    return value.isEmpty() ? null : value;
  }

  private static String defaultCodeForStatus(int statusCode) {
    return switch (statusCode) {
      case 400, 422 -> PoliPageErrorCode.VALIDATION_ERROR;
      case 401 -> PoliPageErrorCode.INVALID_API_KEY;
      case 402 -> PoliPageErrorCode.PAYMENT_REQUIRED;
      case 403 -> PoliPageErrorCode.FORBIDDEN;
      case 404 -> PoliPageErrorCode.NOT_FOUND;
      case 410 -> PoliPageErrorCode.GONE;
      case 429 -> PoliPageErrorCode.QUOTA_EXCEEDED;
      default -> statusCode >= 500 ? PoliPageErrorCode.INTERNAL_ERROR : "UNKNOWN";
    };
  }

  private record Parsed(@Nullable String code, @Nullable String message) {
    private static final Parsed EMPTY = new Parsed(null, null);
  }
}

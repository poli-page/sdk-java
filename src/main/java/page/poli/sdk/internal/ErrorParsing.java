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
import page.poli.sdk.exception.PoliPageRateLimitException;
import page.poli.sdk.exception.PoliPageValidationException;

/**
 * Pure function: map an HTTP error response (status + body + selected headers) to a {@link
 * PoliPageException} subclass. No I/O, no global state — entirely testable from a unit test with
 * byte arrays.
 *
 * <p>The API's error body is {@code { error, detail?, issues?, requestId }}. The code is the body's
 * {@code code} field, else its {@code error} field, else {@code unknown_error}; the message is
 * {@code detail}, else {@code title}, else {@code message}, else {@code HTTP <status>}. A body that
 * is not JSON (empty included) gives {@code INTERNAL_ERROR} with the real status. The code is never
 * inferred from the status, and an unknown code is returned verbatim.
 */
public final class ErrorParsing {

  private static final String UNKNOWN_ERROR = "unknown_error";

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
    String code = parsed.code != null ? parsed.code : UNKNOWN_ERROR;
    String message = parsed.message != null ? parsed.message : ("HTTP " + statusCode);
    return build(code, statusCode, message, requestId, retryAfterHeader, null);
  }

  /**
   * Map an error answer whose body failed while it was read (a dropped connection, the attempt
   * timeout): a malformed error body, like one that is not JSON (SDK spec §8.2). The code is {@code
   * INTERNAL_ERROR} and the message {@code HTTP <status>}, never taken from the bytes received so
   * far; the real status and request id are kept, so the status decides the retry.
   *
   * @param statusCode the HTTP status of the answer
   * @param requestId value of the {@code x-request-id} header, or {@code null}
   * @param retryAfterHeader value of the {@code Retry-After} header, or {@code null}
   * @param cause the read failure
   * @return the exception for the status
   */
  public static PoliPageException malformedBody(
      int statusCode,
      @Nullable String requestId,
      @Nullable String retryAfterHeader,
      Throwable cause) {
    return build(
        PoliPageErrorCode.INTERNAL_ERROR,
        statusCode,
        "HTTP " + statusCode,
        requestId,
        retryAfterHeader,
        cause);
  }

  private static PoliPageException build(
      String code,
      int statusCode,
      String message,
      @Nullable String requestId,
      @Nullable String retryAfterHeader,
      @Nullable Throwable cause) {
    return switch (statusCode) {
      case 400, 422 -> new PoliPageValidationException(code, statusCode, message, requestId, cause);
      case 401, 403 -> new PoliPageAuthException(code, statusCode, message, requestId, cause);
      case 404 -> new PoliPageNotFoundException(code, statusCode, message, requestId, cause);
      case 410 -> new PoliPageGoneException(code, statusCode, message, requestId, cause);
      case 429 ->
          new PoliPageRateLimitException(
              code, statusCode, message, requestId, parseRetryAfter(retryAfterHeader), cause);
      default -> new PoliPageException(code, statusCode, message, requestId, cause);
    };
  }

  /**
   * Parse the {@code Retry-After} header value: delta-seconds (digits only) or an HTTP-date (RFC
   * 9110 §10.2.3).
   *
   * <p>Returns {@code null} when the header is absent, blank, or in any other form ({@code "1.5"},
   * {@code "-1"}, {@code "soon"}), so the retry loop falls back to its backoff. Past HTTP-dates
   * collapse to {@link Duration#ZERO}; a number of seconds too large for a {@code long} saturates.
   * The retry loop clamps the result to 30 seconds; this method returns the value the server sent
   * so the exception's {@code retryAfter()} accessor reports it.
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
    if (isDigits(trimmed)) {
      try {
        return Duration.ofSeconds(Long.parseLong(trimmed));
      } catch (NumberFormatException tooLarge) {
        return Duration.ofSeconds(Long.MAX_VALUE);
      }
    }
    try {
      ZonedDateTime when = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
      Duration delta = Duration.between(ZonedDateTime.now(ZoneOffset.UTC), when);
      return delta.isNegative() ? Duration.ZERO : delta;
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  private static boolean isDigits(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  private static Parsed parseBody(@Nullable byte[] body, ObjectMapper mapper) {
    if (body == null || body.length == 0) {
      return Parsed.NOT_JSON;
    }
    try {
      JsonNode root = mapper.readTree(body);
      if (root == null || root.isMissingNode()) {
        return Parsed.NOT_JSON;
      }
      if (!root.isObject()) {
        return Parsed.EMPTY;
      }
      String code = textOrNull(root, "code");
      if (code == null) {
        code = textOrNull(root, "error");
      }
      // RFC 7807 preference for message: detail > title > legacy message field.
      String message = textOrNull(root, "detail");
      if (message == null) {
        message = textOrNull(root, "title");
      }
      if (message == null) {
        message = textOrNull(root, "message");
      }
      return new Parsed(code, message);
    } catch (IOException ignored) {
      return Parsed.NOT_JSON;
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

  private record Parsed(@Nullable String code, @Nullable String message) {
    private static final Parsed EMPTY = new Parsed(null, null);
    private static final Parsed NOT_JSON = new Parsed(PoliPageErrorCode.INTERNAL_ERROR, null);
  }
}

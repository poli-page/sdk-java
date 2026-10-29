package page.poli.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import page.poli.sdk.PoliPageErrorCode;
import page.poli.sdk.exception.PoliPageAuthException;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.exception.PoliPageGoneException;
import page.poli.sdk.exception.PoliPageNotFoundException;
import page.poli.sdk.exception.PoliPageRateLimitException;
import page.poli.sdk.exception.PoliPageValidationException;

class ErrorParsingTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The API's error body: {@code { error, detail?, issues?, requestId }}. */
  private static byte[] json(String code, String detail) {
    return ("{\"error\":\"" + code + "\",\"detail\":\"" + detail + "\",\"requestId\":\"req_1\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] bytes(String body) {
    return body.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] empty() {
    return new byte[0];
  }

  @Nested
  class StatusMapping {

    @Test
    void status_400_maps_to_validation_exception() {
      PoliPageException ex =
          ErrorParsing.toException(400, json("VALIDATION_ERROR", "bad"), null, null, MAPPER);
      assertThat(ex).isInstanceOf(PoliPageValidationException.class);
      assertThat(ex.code()).isEqualTo("VALIDATION_ERROR");
      assertThat(ex.statusCode()).isEqualTo(400);
      assertThat(ex.getMessage()).isEqualTo("bad");
    }

    @Test
    void status_422_maps_to_validation_exception() {
      PoliPageException ex =
          ErrorParsing.toException(
              422, json("MISSING_DATA", "data is required"), null, null, MAPPER);
      assertThat(ex).isInstanceOf(PoliPageValidationException.class);
      assertThat(ex.code()).isEqualTo("MISSING_DATA");
    }

    @Test
    void status_401_maps_to_auth_exception() {
      PoliPageException ex =
          ErrorParsing.toException(401, json("INVALID_API_KEY", "no good"), null, null, MAPPER);
      assertThat(ex).isInstanceOf(PoliPageAuthException.class);
      assertThat(ex.code()).isEqualTo("INVALID_API_KEY");
    }

    @Test
    void status_403_maps_to_auth_exception_preserving_org_cancelled_code() {
      PoliPageException ex =
          ErrorParsing.toException(
              403, json("ORGANIZATION_CANCELLED", "cancelled"), null, null, MAPPER);
      assertThat(ex).isInstanceOf(PoliPageAuthException.class);
      assertThat(ex.code()).isEqualTo("ORGANIZATION_CANCELLED");
    }

    @Test
    void status_403_payment_grace_maps_to_auth_exception() {
      PoliPageException ex =
          ErrorParsing.toException(
              403, json("ORGANIZATION_PAYMENT_GRACE", "pay"), null, null, MAPPER);
      assertThat(ex).isInstanceOf(PoliPageAuthException.class);
      assertThat(ex.code()).isEqualTo(PoliPageErrorCode.ORGANIZATION_PAYMENT_GRACE);
    }

    @Test
    void status_402_maps_to_base_exception() {
      PoliPageException ex =
          ErrorParsing.toException(402, json("SOMETHING", "pay up"), null, null, MAPPER);
      assertThat(ex.getClass()).isEqualTo(PoliPageException.class);
      assertThat(ex.code()).isEqualTo("SOMETHING");
      assertThat(ex.statusCode()).isEqualTo(402);
    }

    @Test
    void status_404_maps_to_not_found_exception_preserving_document_not_found_code() {
      PoliPageException ex =
          ErrorParsing.toException(404, json("DOCUMENT_NOT_FOUND", "missing"), null, null, MAPPER);
      assertThat(ex).isInstanceOf(PoliPageNotFoundException.class);
      assertThat(ex.code()).isEqualTo("DOCUMENT_NOT_FOUND");
    }

    @Test
    void status_404_template_not_found_surfaces_code_and_detail() {
      PoliPageException ex =
          ErrorParsing.toException(
              404,
              bytes(
                  "{\"error\":\"TEMPLATE_NOT_FOUND\","
                      + "\"detail\":\"Template 'invoice' not found in project 'billing'\"}"),
              null,
              null,
              MAPPER);
      assertThat(ex).isInstanceOf(PoliPageNotFoundException.class);
      assertThat(ex.code()).isEqualTo(PoliPageErrorCode.TEMPLATE_NOT_FOUND);
      assertThat(ex.getMessage()).isEqualTo("Template 'invoice' not found in project 'billing'");
    }

    @Test
    void status_410_maps_to_gone_exception() {
      PoliPageException ex =
          ErrorParsing.toException(410, json("DOCUMENT_GONE", "deleted"), null, null, MAPPER);
      assertThat(ex).isInstanceOf(PoliPageGoneException.class);
      assertThat(ex.code()).isEqualTo(PoliPageErrorCode.DOCUMENT_GONE);
    }

    @Test
    void status_429_maps_to_rate_limit_exception_with_retry_after() {
      PoliPageException ex =
          ErrorParsing.toException(429, json("QUOTA_EXCEEDED", "slow down"), null, "60", MAPPER);
      assertThat(ex).isInstanceOf(PoliPageRateLimitException.class);
      assertThat(((PoliPageRateLimitException) ex).retryAfter()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void status_429_with_no_retry_after_header_yields_null() {
      PoliPageException ex =
          ErrorParsing.toException(429, json("OVERAGE_CAP_EXCEEDED", "cap"), null, null, MAPPER);
      assertThat(((PoliPageRateLimitException) ex).retryAfter()).isNull();
    }

    @Test
    void status_500_maps_to_base_exception() {
      PoliPageException ex =
          ErrorParsing.toException(500, json("INTERNAL_ERROR", "boom"), null, null, MAPPER);
      assertThat(ex.getClass()).isEqualTo(PoliPageException.class);
      assertThat(ex.code()).isEqualTo("INTERNAL_ERROR");
      assertThat(ex.isRetryable()).isTrue();
    }

    @Test
    void status_503_publish_in_progress_surfaces_its_code() {
      PoliPageException ex =
          ErrorParsing.toException(
              503, json("PUBLISH_IN_PROGRESS", "publishing"), null, "1", MAPPER);
      assertThat(ex.getClass()).isEqualTo(PoliPageException.class);
      assertThat(ex.code()).isEqualTo(PoliPageErrorCode.PUBLISH_IN_PROGRESS);
      assertThat(ex.statusCode()).isEqualTo(503);
      assertThat(ex.isRetryable()).isTrue();
    }
  }

  @Nested
  class CodeResolution {

    @Test
    void reads_code_from_error_field() {
      PoliPageException ex =
          ErrorParsing.toException(
              400, bytes("{\"error\":\"ENVIRONMENT_MISMATCH\"}"), null, null, MAPPER);
      assertThat(ex.code()).isEqualTo("ENVIRONMENT_MISMATCH");
    }

    @Test
    void code_field_wins_over_error_field() {
      PoliPageException ex =
          ErrorParsing.toException(
              400, bytes("{\"code\":\"FROM_CODE\",\"error\":\"FROM_ERROR\"}"), null, null, MAPPER);
      assertThat(ex.code()).isEqualTo("FROM_CODE");
    }

    @Test
    void unknown_code_passes_through_verbatim() {
      PoliPageException ex =
          ErrorParsing.toException(418, json("SOME_FUTURE_CODE", "new"), null, null, MAPPER);
      assertThat(ex.code()).isEqualTo("SOME_FUTURE_CODE");
      assertThat(ex.getMessage()).isEqualTo("new");
      assertThat(ex.statusCode()).isEqualTo(418);
    }

    @Test
    void json_object_without_code_or_error_gives_unknown_error_not_a_status_guess() {
      for (int status : new int[] {400, 401, 402, 403, 404, 410, 429, 500}) {
        PoliPageException ex =
            ErrorParsing.toException(
                status, bytes("{\"otherField\":\"ignored\"}"), null, null, MAPPER);
        assertThat(ex.code()).as("status %d", status).isEqualTo("unknown_error");
        assertThat(ex.getMessage()).isEqualTo("HTTP " + status);
      }
    }

    @Test
    void non_object_json_gives_unknown_error() {
      PoliPageException ex =
          ErrorParsing.toException(500, bytes("[\"a\", \"b\"]"), null, null, MAPPER);
      assertThat(ex.code()).isEqualTo("unknown_error");
    }

    @Test
    void empty_body_gives_internal_error_with_real_status() {
      PoliPageException ex = ErrorParsing.toException(400, empty(), null, null, MAPPER);
      assertThat(ex).isInstanceOf(PoliPageValidationException.class);
      assertThat(ex.code()).isEqualTo(PoliPageErrorCode.INTERNAL_ERROR);
      assertThat(ex.statusCode()).isEqualTo(400);
      assertThat(ex.getMessage()).isEqualTo("HTTP 400");
    }

    @Test
    void whitespace_only_body_gives_internal_error() {
      PoliPageException ex = ErrorParsing.toException(502, bytes("  \n "), null, null, MAPPER);
      assertThat(ex.code()).isEqualTo(PoliPageErrorCode.INTERNAL_ERROR);
      assertThat(ex.statusCode()).isEqualTo(502);
    }

    @Test
    void non_json_body_gives_internal_error_with_real_status() {
      PoliPageException ex =
          ErrorParsing.toException(404, bytes("<html>Not Found</html>"), null, null, MAPPER);
      assertThat(ex).isInstanceOf(PoliPageNotFoundException.class);
      assertThat(ex.code()).isEqualTo(PoliPageErrorCode.INTERNAL_ERROR);
      assertThat(ex.statusCode()).isEqualTo(404);
      assertThat(ex.getMessage()).isEqualTo("HTTP 404");
    }
  }

  @Nested
  class MessageResolution {

    @Test
    void detail_wins_over_title_and_message() {
      PoliPageException ex =
          ErrorParsing.toException(
              400,
              bytes("{\"error\":\"X\",\"detail\":\"d\",\"title\":\"t\",\"message\":\"m\"}"),
              null,
              null,
              MAPPER);
      assertThat(ex.getMessage()).isEqualTo("d");
    }

    @Test
    void title_wins_over_message() {
      PoliPageException ex =
          ErrorParsing.toException(
              400,
              bytes("{\"error\":\"X\",\"title\":\"t\",\"message\":\"m\"}"),
              null,
              null,
              MAPPER);
      assertThat(ex.getMessage()).isEqualTo("t");
    }

    @Test
    void message_is_the_last_body_fallback() {
      PoliPageException ex =
          ErrorParsing.toException(
              400, bytes("{\"error\":\"X\",\"message\":\"m\"}"), null, null, MAPPER);
      assertThat(ex.getMessage()).isEqualTo("m");
    }

    @Test
    void no_message_field_uses_http_status() {
      PoliPageException ex =
          ErrorParsing.toException(400, bytes("{\"error\":\"X\"}"), null, null, MAPPER);
      assertThat(ex.code()).isEqualTo("X");
      assertThat(ex.getMessage()).isEqualTo("HTTP 400");
    }
  }

  @Nested
  class RetryAfterParsing {

    @Test
    void parses_integer_seconds() {
      assertThat(ErrorParsing.parseRetryAfter("60")).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void parses_zero_seconds() {
      assertThat(ErrorParsing.parseRetryAfter("0")).isEqualTo(Duration.ZERO);
    }

    @Test
    void parses_large_seconds_uncapped() {
      // The retry loop caps for sleep purposes; parseRetryAfter returns the raw value.
      assertThat(ErrorParsing.parseRetryAfter("3600")).isEqualTo(Duration.ofSeconds(3600));
    }

    @Test
    void a_negative_number_is_not_delta_seconds() {
      // RFC 9110 §10.2.3: delta-seconds is 1*DIGIT. The retry loop falls back to its backoff.
      assertThat(ErrorParsing.parseRetryAfter("-5")).isNull();
      assertThat(ErrorParsing.parseRetryAfter("1.5")).isNull();
      assertThat(ErrorParsing.parseRetryAfter("+5")).isNull();
    }

    @Test
    void a_number_too_large_for_a_long_saturates() {
      assertThat(ErrorParsing.parseRetryAfter("99999999999999999999999"))
          .isEqualTo(Duration.ofSeconds(Long.MAX_VALUE));
    }

    @Test
    void null_returns_null() {
      assertThat(ErrorParsing.parseRetryAfter(null)).isNull();
    }

    @Test
    void empty_string_returns_null() {
      assertThat(ErrorParsing.parseRetryAfter("")).isNull();
      assertThat(ErrorParsing.parseRetryAfter("   ")).isNull();
    }

    @Test
    void garbage_returns_null() {
      assertThat(ErrorParsing.parseRetryAfter("soon-please")).isNull();
    }

    @Test
    void http_date_in_the_future_yields_positive_duration() {
      ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusMinutes(10);
      String header = future.format(DateTimeFormatter.RFC_1123_DATE_TIME);
      Duration parsed = ErrorParsing.parseRetryAfter(header);
      assertThat(parsed).isNotNull();
      // Allow a small wall-clock skew between header generation and the parse call.
      assertThat(parsed).isBetween(Duration.ofMinutes(9), Duration.ofMinutes(11));
    }

    @Test
    void http_date_in_the_past_collapses_to_zero() {
      // RFC 1123 requires the day-of-week to actually match the date — Oct 21 2000 was a Sat.
      // JDK's RFC_1123_DATE_TIME parser validates this; a mismatch fails to parse and returns
      // null, which would defeat the purpose of this test.
      String header = "Sat, 21 Oct 2000 07:28:00 GMT";
      assertThat(ErrorParsing.parseRetryAfter(header)).isEqualTo(Duration.ZERO);
    }
  }

  @Nested
  class RequestIdPropagation {

    @Test
    void request_id_is_extracted_from_argument() {
      PoliPageException ex =
          ErrorParsing.toException(404, json("NOT_FOUND", "x"), "req_test_42", null, MAPPER);
      assertThat(ex.requestId()).isEqualTo("req_test_42");
    }

    @Test
    void null_request_id_yields_null() {
      PoliPageException ex =
          ErrorParsing.toException(404, json("NOT_FOUND", "x"), null, null, MAPPER);
      assertThat(ex.requestId()).isNull();
    }
  }

  @Nested
  class Predicates {

    @Test
    void rate_limit_is_retryable() {
      PoliPageException ex =
          ErrorParsing.toException(429, json("RATE_LIMIT_EXCEEDED", "x"), null, "1", MAPPER);
      assertThat(ex.isRetryable()).isTrue();
    }

    @Test
    void quota_overage_and_storage_access_are_not_retryable() {
      assertThat(
              ErrorParsing.toException(429, json("QUOTA_EXCEEDED", "x"), null, "1", MAPPER)
                  .isRetryable())
          .isFalse();
      assertThat(
              ErrorParsing.toException(429, json("OVERAGE_CAP_EXCEEDED", "x"), null, "1", MAPPER)
                  .isRetryable())
          .isFalse();
      assertThat(
              ErrorParsing.toException(502, json("STORAGE_ACCESS_DENIED", "x"), null, null, MAPPER)
                  .isRetryable())
          .isFalse();
    }

    @Test
    void a_malformed_error_body_keeps_the_status_and_request_id() {
      java.io.IOException cut = new java.io.IOException("cut");
      PoliPageException ex = ErrorParsing.malformedBody(429, "req_1", "7", cut);
      assertThat(ex).isInstanceOf(PoliPageRateLimitException.class);
      assertThat(ex.code()).isEqualTo(PoliPageErrorCode.INTERNAL_ERROR);
      assertThat(ex.getMessage()).isEqualTo("HTTP 429");
      assertThat(ex.requestId()).isEqualTo("req_1");
      assertThat(ex.getCause()).isSameAs(cut);
      assertThat(((PoliPageRateLimitException) ex).retryAfter()).isEqualTo(Duration.ofSeconds(7));
      for (int status : new int[] {400, 401, 404, 410, 503}) {
        PoliPageException other = ErrorParsing.malformedBody(status, null, null, cut);
        assertThat(other.statusCode()).isEqualTo(status);
        assertThat(other.getCause()).isSameAs(cut);
      }
    }

    @Test
    void server_5xx_is_retryable() {
      PoliPageException ex =
          ErrorParsing.toException(502, json("INTERNAL_ERROR", "x"), null, null, MAPPER);
      assertThat(ex.isRetryable()).isTrue();
    }

    @Test
    void validation_4xx_is_not_retryable() {
      PoliPageException ex =
          ErrorParsing.toException(400, json("VALIDATION_ERROR", "x"), null, null, MAPPER);
      assertThat(ex.isRetryable()).isFalse();
    }

    @Test
    void auth_is_not_retryable() {
      PoliPageException ex =
          ErrorParsing.toException(401, json("INVALID_API_KEY", "x"), null, null, MAPPER);
      assertThat(ex.isRetryable()).isFalse();
    }
  }
}

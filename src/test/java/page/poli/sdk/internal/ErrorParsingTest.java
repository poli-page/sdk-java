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
import page.poli.sdk.exception.PoliPagePaymentRequiredException;
import page.poli.sdk.exception.PoliPageRateLimitException;
import page.poli.sdk.exception.PoliPageValidationException;

class ErrorParsingTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static byte[] json(String code, String message) {
    return ("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}")
        .getBytes(StandardCharsets.UTF_8);
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
    void status_402_maps_to_payment_required_exception() {
      PoliPageException ex =
          ErrorParsing.toException(402, json("PAYMENT_REQUIRED", "pay up"), null, null, MAPPER);
      assertThat(ex).isInstanceOf(PoliPagePaymentRequiredException.class);
      assertThat(ex.code()).isEqualTo("PAYMENT_REQUIRED");
    }

    @Test
    void status_404_maps_to_not_found_exception_preserving_document_not_found_code() {
      PoliPageException ex =
          ErrorParsing.toException(404, json("DOCUMENT_NOT_FOUND", "missing"), null, null, MAPPER);
      assertThat(ex).isInstanceOf(PoliPageNotFoundException.class);
      assertThat(ex.code()).isEqualTo("DOCUMENT_NOT_FOUND");
    }

    @Test
    void status_410_maps_to_gone_exception() {
      PoliPageException ex =
          ErrorParsing.toException(410, json("GONE", "deleted"), null, null, MAPPER);
      assertThat(ex).isInstanceOf(PoliPageGoneException.class);
      assertThat(ex.code()).isEqualTo("GONE");
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
    void status_503_with_empty_body_uses_status_defaults() {
      PoliPageException ex = ErrorParsing.toException(503, empty(), null, null, MAPPER);
      assertThat(ex.getClass()).isEqualTo(PoliPageException.class);
      assertThat(ex.code()).isEqualTo(PoliPageErrorCode.INTERNAL_ERROR);
      assertThat(ex.getMessage()).isEqualTo("HTTP 503");
    }
  }

  @Nested
  class BodyParsing {

    @Test
    void empty_body_falls_back_to_status_defaults_for_400() {
      PoliPageException ex = ErrorParsing.toException(400, empty(), null, null, MAPPER);
      assertThat(ex.code()).isEqualTo(PoliPageErrorCode.VALIDATION_ERROR);
      assertThat(ex.getMessage()).isEqualTo("HTTP 400");
    }

    @Test
    void empty_body_falls_back_for_401() {
      PoliPageException ex = ErrorParsing.toException(401, empty(), null, null, MAPPER);
      assertThat(ex.code()).isEqualTo(PoliPageErrorCode.INVALID_API_KEY);
    }

    @Test
    void malformed_json_falls_back_to_status_defaults() {
      byte[] garbage = "not json {{}".getBytes(StandardCharsets.UTF_8);
      PoliPageException ex = ErrorParsing.toException(404, garbage, null, null, MAPPER);
      assertThat(ex).isInstanceOf(PoliPageNotFoundException.class);
      assertThat(ex.code()).isEqualTo(PoliPageErrorCode.NOT_FOUND);
      assertThat(ex.getMessage()).isEqualTo("HTTP 404");
    }

    @Test
    void json_object_without_code_or_message_falls_back() {
      byte[] body = "{\"otherField\":\"ignored\"}".getBytes(StandardCharsets.UTF_8);
      PoliPageException ex = ErrorParsing.toException(400, body, null, null, MAPPER);
      assertThat(ex.code()).isEqualTo(PoliPageErrorCode.VALIDATION_ERROR);
      assertThat(ex.getMessage()).isEqualTo("HTTP 400");
    }

    @Test
    void json_with_only_message_uses_default_code() {
      byte[] body = "{\"message\":\"explicit\"}".getBytes(StandardCharsets.UTF_8);
      PoliPageException ex = ErrorParsing.toException(400, body, null, null, MAPPER);
      assertThat(ex.code()).isEqualTo(PoliPageErrorCode.VALIDATION_ERROR);
      assertThat(ex.getMessage()).isEqualTo("explicit");
    }

    @Test
    void json_with_only_code_uses_default_message() {
      byte[] body = "{\"code\":\"CUSTOM_CODE\"}".getBytes(StandardCharsets.UTF_8);
      PoliPageException ex = ErrorParsing.toException(400, body, null, null, MAPPER);
      assertThat(ex.code()).isEqualTo("CUSTOM_CODE");
      assertThat(ex.getMessage()).isEqualTo("HTTP 400");
    }

    @Test
    void non_object_json_falls_back_to_defaults() {
      byte[] body = "[\"a\", \"b\"]".getBytes(StandardCharsets.UTF_8);
      PoliPageException ex = ErrorParsing.toException(500, body, null, null, MAPPER);
      assertThat(ex.code()).isEqualTo(PoliPageErrorCode.INTERNAL_ERROR);
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
    void negative_seconds_collapse_to_zero() {
      assertThat(ErrorParsing.parseRetryAfter("-5")).isEqualTo(Duration.ZERO);
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
          ErrorParsing.toException(429, json("QUOTA_EXCEEDED", "x"), null, "1", MAPPER);
      assertThat(ex.isRetryable()).isTrue();
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

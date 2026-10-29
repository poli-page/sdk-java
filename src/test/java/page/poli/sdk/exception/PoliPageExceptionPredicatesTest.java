package page.poli.sdk.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import page.poli.sdk.PoliPageErrorCode;

class PoliPageExceptionPredicatesTest {

  @Nested
  class IsAuthError {

    @Test
    void true_for_401() {
      PoliPageException e = new PoliPageAuthException("INVALID_API_KEY", 401, "no", null);
      assertThat(e.isAuthError()).isTrue();
    }

    @Test
    void true_for_403() {
      PoliPageException e = new PoliPageAuthException("FORBIDDEN", 403, "no", null);
      assertThat(e.isAuthError()).isTrue();
    }

    @Test
    void false_for_400() {
      PoliPageException e = new PoliPageValidationException("VALIDATION_ERROR", 400, "no", null);
      assertThat(e.isAuthError()).isFalse();
    }

    @Test
    void false_for_500() {
      PoliPageException e = new PoliPageException("INTERNAL_ERROR", 500, "no", null, null);
      assertThat(e.isAuthError()).isFalse();
    }

    @Test
    void false_for_network_error_no_status() {
      PoliPageException e = new PoliPageNetworkException("network_error", "dns", null);
      assertThat(e.isAuthError()).isFalse();
    }
  }

  @Nested
  class IsRateLimitError {

    @Test
    void true_for_429() {
      PoliPageException e =
          new PoliPageRateLimitException("QUOTA_EXCEEDED", 429, "limit", null, null);
      assertThat(e.isRateLimitError()).isTrue();
    }

    @Test
    void false_for_401() {
      PoliPageException e = new PoliPageAuthException("INVALID_API_KEY", 401, "no", null);
      assertThat(e.isRateLimitError()).isFalse();
    }

    @Test
    void false_for_503() {
      PoliPageException e = new PoliPageException("INTERNAL_ERROR", 503, "no", null, null);
      assertThat(e.isRateLimitError()).isFalse();
    }
  }

  @Nested
  class IsValidationError {

    @Test
    void true_for_400() {
      PoliPageException e = new PoliPageValidationException("VALIDATION_ERROR", 400, "no", null);
      assertThat(e.isValidationError()).isTrue();
    }

    @Test
    void false_for_422() {
      // Reference (sdk-node) treats validation as 400-only; 422 is unmapped per error.ts:104-117.
      PoliPageException e = new PoliPageValidationException("VALIDATION_ERROR", 422, "no", null);
      assertThat(e.isValidationError()).isFalse();
    }

    @Test
    void false_for_401() {
      PoliPageException e = new PoliPageAuthException("INVALID_API_KEY", 401, "no", null);
      assertThat(e.isValidationError()).isFalse();
    }
  }

  @Nested
  class IsNetworkError {

    @Test
    void true_for_network_error_code() {
      PoliPageException e = new PoliPageNetworkException("network_error", "dns", null);
      assertThat(e.isNetworkError()).isTrue();
    }

    @Test
    void true_for_timeout_code() {
      PoliPageException e = new PoliPageNetworkException("timeout", "deadline", null);
      assertThat(e.isNetworkError()).isTrue();
    }

    @Test
    void false_for_aborted() {
      PoliPageException e = new PoliPageException("aborted", 0, "user cancel", null, null);
      assertThat(e.isNetworkError()).isFalse();
    }

    @Test
    void false_for_api_error() {
      PoliPageException e = new PoliPageValidationException("VALIDATION_ERROR", 400, "no", null);
      assertThat(e.isNetworkError()).isFalse();
    }
  }

  @Nested
  class IsRetryable {

    @Test
    void true_for_5xx_429_network_error_and_timeout() {
      assertThat(new PoliPageException("INTERNAL_ERROR", 500, "x", null, null).isRetryable())
          .isTrue();
      assertThat(new PoliPageException("PUBLISH_IN_PROGRESS", 503, "x", null, null).isRetryable())
          .isTrue();
      assertThat(
              new PoliPageRateLimitException("RATE_LIMIT_EXCEEDED", 429, "x", null, null)
                  .isRetryable())
          .isTrue();
      assertThat(new PoliPageNetworkException("network_error", "x", null).isRetryable()).isTrue();
      assertThat(new PoliPageNetworkException("timeout", "x", null).isRetryable()).isTrue();
    }

    @Test
    void false_for_the_codes_waiting_cannot_fix() {
      assertThat(
              new PoliPageRateLimitException("QUOTA_EXCEEDED", 429, "x", null, null).isRetryable())
          .isFalse();
      assertThat(
              new PoliPageRateLimitException("OVERAGE_CAP_EXCEEDED", 429, "x", null, null)
                  .isRetryable())
          .isFalse();
      assertThat(new PoliPageException("STORAGE_ACCESS_DENIED", 502, "x", null, null).isRetryable())
          .isFalse();
    }

    @Test
    void false_for_download_failed_whatever_the_storage_status() {
      for (int status : new int[] {0, 403, 429, 500, 503}) {
        PoliPageException e =
            new PoliPageDownloadException(
                PoliPageErrorCode.DOWNLOAD_FAILED, status, "Failed to download PDF", null);
        assertThat(e.isRetryable()).as("storage status %d", status).isFalse();
      }
    }

    @Test
    void false_for_aborted_and_client_errors() {
      assertThat(new PoliPageException("aborted", 0, "x", null, null).isRetryable()).isFalse();
      assertThat(new PoliPageValidationException("VALIDATION_ERROR", 400, "x", null).isRetryable())
          .isFalse();
      assertThat(new PoliPageInvalidOptionsException("apiKey is required").isRetryable()).isFalse();
    }
  }
}

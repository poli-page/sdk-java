package page.poli.sdk.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import page.poli.sdk.PoliPageErrorCode;

/**
 * {@code toPayload()} is the HTTP body an integration answers with: exactly {@code code}, {@code
 * message}, {@code status} and {@code requestId}, spelled so, nulls included.
 */
class ErrorPayloadTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static JsonNode written(PoliPageException error) {
    return JSON.valueToTree(error.toPayload());
  }

  private static List<String> keys(JsonNode node) {
    List<String> keys = new ArrayList<>();
    node.fieldNames().forEachRemaining(keys::add);
    return keys;
  }

  @Test
  void a_json_mapper_writes_exactly_the_four_keys_nulls_included() {
    JsonNode api =
        written(
            new PoliPageNotFoundException(
                PoliPageErrorCode.TEMPLATE_NOT_FOUND, 404, "Template not found.", "req_1"));
    JsonNode sdk = written(new PoliPageInvalidOptionsException("apiKey is required"));

    assertThat(keys(api)).containsExactly("code", "message", "status", "requestId");
    assertThat(keys(sdk)).containsExactly("code", "message", "status", "requestId");
    assertThat(api.get("code").asText()).isEqualTo("TEMPLATE_NOT_FOUND");
    assertThat(api.get("message").asText()).isEqualTo("Template not found.");
    assertThat(api.get("status").asInt()).isEqualTo(404);
    assertThat(api.get("requestId").asText()).isEqualTo("req_1");
    assertThat(sdk.get("status").isNull()).isTrue();
    assertThat(sdk.get("requestId").isNull()).isTrue();
  }

  @Test
  void invalid_response_answers_502_and_keeps_its_real_2xx_status() {
    PoliPageException error =
        new PoliPageException(
            PoliPageErrorCode.INVALID_RESPONSE,
            200,
            "Invalid response: HTTP 200 body is not JSON",
            "req_invalid",
            null);

    assertThat(error.toPayload())
        .isEqualTo(
            new ErrorPayload(
                "invalid_response",
                "Invalid response: HTTP 200 body is not JSON",
                502,
                "req_invalid"));
    assertThat(error.statusCode()).isEqualTo(200);
    assertThat(error.isRetryable()).isFalse();
  }

  @Test
  void network_error_answers_503_and_timeout_504() {
    assertThat(
            new PoliPageNetworkException(
                    PoliPageErrorCode.NETWORK_ERROR, "Network request failed", null)
                .toPayload()
                .status())
        .isEqualTo(503);
    assertThat(
            new PoliPageNetworkException(
                    PoliPageErrorCode.TIMEOUT, "Request timed out after 1000ms", null)
                .toPayload()
                .status())
        .isEqualTo(504);
  }

  @Test
  void errors_without_a_status_answer_a_null_status() {
    PoliPageException io =
        new PoliPageException(
            PoliPageErrorCode.IO_ERROR, 0, "Failed to write PDF: denied", null, null);
    PoliPageException download =
        new PoliPageDownloadException(
            PoliPageErrorCode.DOWNLOAD_FAILED, 0, "Failed to download PDF: timeout", null);

    assertThat(io.toPayload().status()).isNull();
    assertThat(io.isRetryable()).isFalse();
    assertThat(download.toPayload().status()).isNull();
  }

  @Test
  void a_download_failure_with_a_storage_status_answers_that_status() {
    PoliPageException expired =
        new PoliPageDownloadException(
            PoliPageErrorCode.DOWNLOAD_FAILED, 403, "Failed to download PDF: HTTP 403", null);

    assertThat(expired.toPayload().status()).isEqualTo(403);
    assertThat(expired.isRetryable()).isFalse();
  }
}

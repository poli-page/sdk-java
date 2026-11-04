package page.poli.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import page.poli.sdk.contract.MockServer;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.input.RenderInput;

/**
 * A 2xx answer whose body is not JSON, from a method that expects JSON, is {@code
 * invalid_response}: the real 2xx status, the answer's request id, {@code Invalid response: HTTP
 * <status> body is not JSON}, never retried, and {@code 502} in {@code toPayload()}.
 */
class InvalidResponseTest {

  private static final RenderInput INPUT =
      RenderInput.builder().project("billing").template("invoice").data(Map.of()).build();

  private static MockServer.Answer answer(int status, String contentType, String body) {
    return new MockServer.Answer(
        status,
        Map.of("Content-Type", contentType, "x-request-id", "req_invalid"),
        body.getBytes(StandardCharsets.UTF_8));
  }

  private static MockServer.Answer html() {
    return answer(200, "text/html", "<html><body><h1>Welcome</h1></body></html>");
  }

  private static PoliPageClient client(MockServer server, List<Throwable> errors) {
    return PoliPageClient.builder()
        .apiKey("pp_sandbox_00000000000000000000000000000000")
        .baseUrl(URI.create(server.origin()))
        .maxRetries(2)
        .retryDelay(Duration.ofMillis(1))
        .onError(errors::add)
        .build();
  }

  private static Consumer<PoliPageException> notJson(int status) {
    return e -> {
      assertThat(e.code()).isEqualTo(PoliPageErrorCode.INVALID_RESPONSE);
      assertThat(e.getMessage())
          .isEqualTo("Invalid response: HTTP " + status + " body is not JSON");
      assertThat(e.statusCode()).isEqualTo(status);
      assertThat(e.requestId()).isEqualTo("req_invalid");
      assertThat(e.isRetryable()).isFalse();
      assertThat(e.toPayload().status()).isEqualTo(502);
    };
  }

  @Test
  void an_html_page_is_invalid_response_on_every_json_method_and_is_not_retried() {
    try (MockServer server = MockServer.start((index, origin) -> html())) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk = client(server, errors);

      assertThatThrownBy(() -> sdk.documents().get("doc_abc123"))
          .isInstanceOfSatisfying(PoliPageException.class, notJson(200));
      assertThatThrownBy(() -> sdk.render().document(INPUT))
          .isInstanceOfSatisfying(PoliPageException.class, notJson(200));
      assertThatThrownBy(() -> sdk.render().pdf(INPUT))
          .isInstanceOfSatisfying(PoliPageException.class, notJson(200));
      assertThatThrownBy(() -> sdk.documents().thumbnails("doc_abc123"))
          .isInstanceOfSatisfying(PoliPageException.class, notJson(200));
      assertThatThrownBy(() -> sdk.documentsAsync().get("doc_abc123").join())
          .cause()
          .isInstanceOfSatisfying(PoliPageException.class, notJson(200));
      assertThatThrownBy(() -> sdk.renderAsync().document(INPUT).join())
          .cause()
          .isInstanceOfSatisfying(PoliPageException.class, notJson(200));

      assertThat(server.requests()).as("one request per call, no retry").hasSize(6);
      assertThat(errors).hasSize(6);
    }
  }

  @Test
  void an_empty_body_is_not_json() {
    try (MockServer server = MockServer.start(List.of(answer(201, "application/json", "")))) {
      PoliPageClient sdk = client(server, new CopyOnWriteArrayList<>());

      assertThatThrownBy(() -> sdk.documents().get("doc_abc123"))
          .isInstanceOfSatisfying(PoliPageException.class, notJson(201));
    }
  }

  @Test
  void json_followed_by_something_else_is_not_json() {
    try (MockServer server =
        MockServer.start(
            List.of(answer(200, "application/json", "{\"documentId\":\"d\"}<html></html>")))) {
      PoliPageClient sdk = client(server, new CopyOnWriteArrayList<>());

      assertThatThrownBy(() -> sdk.documents().get("doc_abc123"))
          .isInstanceOfSatisfying(PoliPageException.class, notJson(200));
    }
  }

  @Test
  void a_json_null_is_invalid_response_not_a_null_result() {
    try (MockServer server =
        MockServer.start(
            List.of(
                answer(200, "application/json", "null"),
                answer(200, "application/json", "null")))) {
      PoliPageClient sdk = client(server, new CopyOnWriteArrayList<>());

      assertThatThrownBy(() -> sdk.documents().get("doc_abc123"))
          .isInstanceOfSatisfying(
              PoliPageException.class,
              e -> {
                assertThat(e.code()).isEqualTo(PoliPageErrorCode.INVALID_RESPONSE);
                assertThat(e.getMessage())
                    .isEqualTo(
                        "Failed to parse /v1/documents/doc_abc123 response as DocumentDescriptor");
              });
      assertThatThrownBy(() -> sdk.documents().thumbnails("doc_abc123"))
          .isInstanceOfSatisfying(
              PoliPageException.class,
              e -> assertThat(e.code()).isEqualTo(PoliPageErrorCode.INVALID_RESPONSE));
    }
  }

  @Test
  void json_of_the_wrong_shape_is_invalid_response_naming_the_type() {
    try (MockServer server = MockServer.start(List.of(answer(200, "application/json", "[1,2]")))) {
      PoliPageClient sdk = client(server, new CopyOnWriteArrayList<>());

      assertThatThrownBy(() -> sdk.documents().get("doc_abc123"))
          .isInstanceOfSatisfying(
              PoliPageException.class,
              e -> {
                assertThat(e.code()).isEqualTo(PoliPageErrorCode.INVALID_RESPONSE);
                assertThat(e.getMessage())
                    .isEqualTo(
                        "Failed to parse /v1/documents/doc_abc123 response as DocumentDescriptor");
                assertThat(e.statusCode()).isEqualTo(200);
                assertThat(e.isRetryable()).isFalse();
              });
      assertThat(server.requests()).hasSize(1);
    }
  }
}

package page.poli.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import page.poli.sdk.contract.MockServer;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.input.RenderInput;

/** {@code onError} fires once with every error a call ends with (SDK spec §4.1). */
class OnErrorTest {

  private static final RenderInput INPUT =
      RenderInput.builder().project("billing").template("invoice").data(Map.of()).build();

  private static MockServer.Answer json(String body) {
    return new MockServer.Answer(
        200, Map.of("Content-Type", "application/json"), body.getBytes(StandardCharsets.UTF_8));
  }

  private static PoliPageClient client(MockServer server, List<Throwable> errors) {
    return PoliPageClient.builder()
        .apiKey("pp_sandbox_00000000000000000000000000000000")
        .baseUrl(URI.create(server.origin()))
        .onError(errors::add)
        .build();
  }

  @Test
  void an_unparsable_2xx_body_is_invalid_response_and_fires_on_error() {
    try (MockServer server = MockServer.start(List.of(json("not json"), json("[1,2]")))) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk = client(server, errors);

      assertThatThrownBy(() -> sdk.documents().get("doc_abc123"))
          .isInstanceOfSatisfying(
              PoliPageException.class, e -> assertThat(e.code()).isEqualTo("invalid_response"));
      assertThatThrownBy(() -> sdk.documentsAsync().get("doc_abc123").join())
          .isInstanceOf(CompletionException.class)
          .cause()
          .isInstanceOfSatisfying(
              PoliPageException.class, e -> assertThat(e.code()).isEqualTo("invalid_response"));

      assertThat(errors).hasSize(2);
      assertThat(errors)
          .allSatisfy(
              e -> assertThat(((PoliPageException) e).code()).isEqualTo("invalid_response"));
    }
  }

  @Test
  void a_file_that_cannot_be_written_is_io_error_and_fires_on_error(@TempDir Path dir) {
    String descriptor =
        "{\"documentId\":\"d\",\"organizationId\":\"o\",\"environment\":\"sandbox\","
            + "\"format\":\"A4\",\"pageCount\":1,\"sizeBytes\":1,\"createdAt\":\"x\","
            + "\"metadata\":{},\"presignedPdfUrl\":\"%s/storage/d.pdf\",\"expiresAt\":\"x\"}";
    try (MockServer server =
        MockServer.start(
            (index, origin) ->
                index % 2 == 0
                    ? json(String.format(descriptor, origin))
                    : new MockServer.Answer(200, Map.of(), new byte[] {'%', 'P'}))) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk = client(server, errors);
      Path notAFile = dir.resolve("missing-dir").resolve("out.pdf");

      assertThatThrownBy(() -> sdk.renderToFile(INPUT, notAFile))
          .isInstanceOfSatisfying(
              PoliPageException.class,
              e -> {
                assertThat(e.code()).isEqualTo("io_error");
                // The message names the target, not the hidden temporary file.
                assertThat(e.getMessage())
                    .isEqualTo("Failed to write PDF to " + notAFile + ": NoSuchFileException");
                assertThat(e.getCause().getMessage()).endsWith(".part");
              });
      assertThatThrownBy(() -> sdk.renderToFileAsync(INPUT, notAFile).join())
          .cause()
          .isInstanceOfSatisfying(
              PoliPageException.class, e -> assertThat(e.code()).isEqualTo("io_error"));

      assertThat(errors).hasSize(2);
    }
  }

  @Test
  void an_api_error_fires_on_error_once_after_the_retries() {
    MockServer.Answer busy =
        new MockServer.Answer(
            503,
            Map.of("Content-Type", "application/json"),
            "{\"error\":\"ORGANIZATION_MIGRATING\"}".getBytes(StandardCharsets.UTF_8));
    try (MockServer server = MockServer.start(List.of(busy, busy, busy))) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk =
          PoliPageClient.builder()
              .apiKey("pp_sandbox_00000000000000000000000000000000")
              .baseUrl(URI.create(server.origin()))
              .retryDelay(java.time.Duration.ofMillis(1))
              .onError(errors::add)
              .build();

      assertThatThrownBy(() -> sdk.documents().delete("doc_abc123"))
          .isInstanceOf(PoliPageException.class);

      assertThat(errors).hasSize(1);
      assertThat(server.requests()).hasSize(3);
    }
  }
}

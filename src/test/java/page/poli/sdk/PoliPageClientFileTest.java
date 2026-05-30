package page.poli.sdk;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.exception.PoliPageNotFoundException;
import page.poli.sdk.input.ProjectModeInput;

@WireMockTest
class PoliPageClientFileTest {

  private static final String TEST_KEY = "pp_test_secret";
  private static final byte[] PDF_BYTES =
      "%PDF-1.4\nfile pdf body".getBytes(StandardCharsets.UTF_8);
  private static final String PRESIGNED_PATH = "/presigned/file.pdf";

  private static final String FULL_DESCRIPTOR_JSON_TEMPLATE =
      "{"
          + "\"documentId\":\"doc_file\","
          + "\"organizationId\":\"org_42\","
          + "\"environment\":\"sandbox\","
          + "\"format\":\"A4\","
          + "\"pageCount\":1,"
          + "\"sizeBytes\":100,"
          + "\"createdAt\":\"2026-05-29T10:00:00Z\","
          + "\"metadata\":{},"
          + "\"presignedPdfUrl\":\"%s\","
          + "\"expiresAt\":\"2026-05-29T10:15:00Z\""
          + "}";

  private static PoliPageClient newClient(WireMockRuntimeInfo wm) {
    return PoliPageClient.builder()
        .apiKey(TEST_KEY)
        .baseUrl(URI.create(wm.getHttpBaseUrl()))
        .maxRetries(0)
        .build();
  }

  private static void stubHappyPath(WireMockRuntimeInfo wm) {
    String presigned = wm.getHttpBaseUrl() + PRESIGNED_PATH;
    stubFor(
        post(urlEqualTo("/v1/render"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(String.format(FULL_DESCRIPTOR_JSON_TEMPLATE, presigned))));
    stubFor(
        get(urlPathEqualTo(PRESIGNED_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/pdf")
                    .withBody(PDF_BYTES)));
  }

  private static ProjectModeInput simpleInput() {
    return ProjectModeInput.builder()
        .project("billing")
        .template("invoice")
        .version("1.0.0")
        .data(Map.of("k", "v"))
        .build();
  }

  @Test
  void renderToFile_writes_pdf_bytes_to_target(WireMockRuntimeInfo wm, @TempDir Path tmp)
      throws IOException {
    stubHappyPath(wm);
    Path target = tmp.resolve("invoice.pdf");

    newClient(wm).renderToFile(simpleInput(), target);

    assertThat(Files.readAllBytes(target)).isEqualTo(PDF_BYTES);
  }

  @Test
  void renderToFile_overwrites_existing_file(WireMockRuntimeInfo wm, @TempDir Path tmp)
      throws IOException {
    stubHappyPath(wm);
    Path target = tmp.resolve("invoice.pdf");
    Files.writeString(target, "old contents that should be overwritten");

    newClient(wm).renderToFile(simpleInput(), target);

    assertThat(Files.readAllBytes(target)).isEqualTo(PDF_BYTES);
  }

  @Test
  void renderToFile_propagates_render_404_as_PoliPageNotFoundException(
      WireMockRuntimeInfo wm, @TempDir Path tmp) {
    stubFor(
        post(urlEqualTo("/v1/render"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"code\":\"VERSION_NOT_FOUND\",\"message\":\"x\"}")));

    assertThatThrownBy(() -> newClient(wm).renderToFile(simpleInput(), tmp.resolve("nope.pdf")))
        .isInstanceOf(PoliPageNotFoundException.class);
  }

  @Test
  void renderToFile_wraps_local_io_failure_with_io_error_code(
      WireMockRuntimeInfo wm, @TempDir Path tmp) {
    stubHappyPath(wm);
    // Writing into a non-existent directory triggers IOException from Files.copy.
    Path bogusPath = tmp.resolve("no/such/dir/out.pdf");

    assertThatThrownBy(() -> newClient(wm).renderToFile(simpleInput(), bogusPath))
        .isInstanceOf(PoliPageException.class)
        .satisfies(
            t -> {
              PoliPageException ex = (PoliPageException) t;
              assertThat(ex.code()).isEqualTo("io_error");
              assertThat(ex.statusCode()).isZero();
              assertThat(ex.getCause()).isInstanceOf(IOException.class);
            });
  }

  @Test
  void renderToFileAsync_writes_pdf_bytes(WireMockRuntimeInfo wm, @TempDir Path tmp)
      throws IOException {
    stubHappyPath(wm);
    Path target = tmp.resolve("async.pdf");

    newClient(wm).renderToFileAsync(simpleInput(), target).join();

    assertThat(Files.readAllBytes(target)).isEqualTo(PDF_BYTES);
  }

  @Test
  void renderToFileAsync_wraps_render_failure_in_CompletionException(
      WireMockRuntimeInfo wm, @TempDir Path tmp) {
    stubFor(
        post(urlEqualTo("/v1/render"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"code\":\"VERSION_NOT_FOUND\",\"message\":\"x\"}")));

    assertThatThrownBy(
            () -> newClient(wm).renderToFileAsync(simpleInput(), tmp.resolve("x.pdf")).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(PoliPageNotFoundException.class);
  }
}

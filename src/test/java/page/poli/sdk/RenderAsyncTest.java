package page.poli.sdk;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import page.poli.sdk.exception.PoliPageNotFoundException;
import page.poli.sdk.input.RenderInput;
import page.poli.sdk.model.DocumentDescriptor;

@WireMockTest
class RenderAsyncTest {

  private static final String TEST_KEY = "pp_sandbox_secret";
  private static final byte[] PDF_BYTES = "%PDF-1.4\nasync pdf".getBytes(StandardCharsets.UTF_8);
  private static final String PRESIGNED_PATH = "/presigned/async.pdf";

  private static final String FULL_DESCRIPTOR_JSON_TEMPLATE =
      "{"
          + "\"documentId\":\"doc_async\","
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

  private static PoliPageClient newClientWithRetries(WireMockRuntimeInfo wm) {
    return PoliPageClient.builder()
        .apiKey(TEST_KEY)
        .baseUrl(URI.create(wm.getHttpBaseUrl()))
        .maxRetries(2)
        .retryDelay(Duration.ofMillis(1))
        .build();
  }

  private static RenderInput simpleInput() {
    return RenderInput.builder()
        .project("billing")
        .template("invoice")
        .data(Map.of("k", "v"))
        .build();
  }

  private static void stubDescriptorReturning(WireMockRuntimeInfo wm, String presignedUrl) {
    stubFor(
        post(urlEqualTo("/v1/render"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(String.format(FULL_DESCRIPTOR_JSON_TEMPLATE, presignedUrl))));
  }

  private static void stubPresignedReturnsPdf() {
    stubFor(
        get(urlPathEqualTo(PRESIGNED_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/pdf")
                    .withBody(PDF_BYTES)));
  }

  @Test
  void pdf_returns_bytes_via_future(WireMockRuntimeInfo wm) {
    stubDescriptorReturning(wm, wm.getHttpBaseUrl() + PRESIGNED_PATH);
    stubPresignedReturnsPdf();

    byte[] pdf = newClient(wm).renderAsync().pdf(simpleInput()).join();

    assertThat(pdf).isEqualTo(PDF_BYTES);
  }

  @Test
  void pdfStream_returns_InputStream_via_future(WireMockRuntimeInfo wm) throws Exception {
    stubDescriptorReturning(wm, wm.getHttpBaseUrl() + PRESIGNED_PATH);
    stubPresignedReturnsPdf();

    try (InputStream stream = newClient(wm).renderAsync().pdfStream(simpleInput()).join()) {
      assertThat(stream.readAllBytes()).isEqualTo(PDF_BYTES);
    }
  }

  @Test
  void document_returns_descriptor_via_future(WireMockRuntimeInfo wm) {
    stubDescriptorReturning(wm, "https://s3.example/x.pdf");

    DocumentDescriptor d = newClient(wm).renderAsync().document(simpleInput()).join();

    assertThat(d.documentId()).isEqualTo("doc_async");
    assertThat(d.presignedPdfUrl()).isEqualTo("https://s3.example/x.pdf");
  }

  @Test
  void error_wraps_in_CompletionException_with_typed_cause(WireMockRuntimeInfo wm) {
    stubFor(
        post(urlEqualTo("/v1/render"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":\"TEMPLATE_NOT_FOUND\",\"detail\":\"x\"}")));

    assertThatThrownBy(() -> newClient(wm).renderAsync().document(simpleInput()).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(PoliPageNotFoundException.class)
        .satisfies(
            t ->
                assertThat(((PoliPageNotFoundException) t.getCause()).code())
                    .isEqualTo(PoliPageErrorCode.TEMPLATE_NOT_FOUND));
  }

  @Test
  void async_retry_recovers_from_500(WireMockRuntimeInfo wm) {
    String scenario = "async-retry";
    stubFor(
        post(urlEqualTo("/v1/render"))
            .inScenario(scenario)
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("attempt-2"));
    stubFor(
        post(urlEqualTo("/v1/render"))
            .inScenario(scenario)
            .whenScenarioStateIs("attempt-2")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        String.format(FULL_DESCRIPTOR_JSON_TEMPLATE, "https://s3.example/x.pdf"))));

    DocumentDescriptor d = newClientWithRetries(wm).renderAsync().document(simpleInput()).join();

    assertThat(d.documentId()).isEqualTo("doc_async");
  }
}
